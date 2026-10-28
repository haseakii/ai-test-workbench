package io.aitest.workbench.execution;

import io.aitest.contract.CanonicalJson;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.Scope.Actor;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.material.Materials;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;

/** Fixed CSV preparation only. No model, provider call, arbitrary tool or long-term memory. */
public final class ContextManifests {
 private final Store db;private final Scope scope;private final Materials materials;
 public ContextManifests(Store db,Scope scope,Materials materials){this.db=db;this.scope=scope;this.materials=materials;}
 public static String time(Instant t){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(t);}
 public static void invalidate(Store db,UUID conversation,String reason){
  db.update("UPDATE context_manifests SET state='invalidated',invalidation_reason=? WHERE tenant_id=? AND conversation_id=? AND state='active'",reason,id(TENANT),id(conversation));
  db.update("UPDATE understanding_runs SET state='stale' WHERE tenant_id=? AND conversation_id=? AND state IN ('ready','needs_input')",id(TENANT),id(conversation));
  if(!reason.equals("INPUT_CHANGED")){
   db.update("UPDATE execution_bindings SET stopped=TRUE WHERE tenant_id=? AND conversation_id=? AND closed_at IS NULL",id(TENANT),id(conversation));
   // Revocation must remain reconcilable even when it precedes approval/outbox creation.
   for(var binding:db.rows("SELECT id FROM execution_bindings WHERE tenant_id=? AND conversation_id=? AND closed_at IS NULL",id(TENANT),id(conversation))){
    if(db.rows("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),binding.get("id")).isEmpty())db.update("INSERT INTO outbox(tenant_id,id,binding_id,state) VALUES(?,?,?,'sending')",id(TENANT),id(UUID.randomUUID()),binding.get("id"));
    else db.update("UPDATE outbox SET lease_until=NULL WHERE tenant_id=? AND binding_id=? AND state<>'done'",id(TENANT),binding.get("id"));
   }
  }
 }
 public Map<String,Object> runtime(){
  String fingerprint;
  try(var bytes=Materials.class.getResourceAsStream("Materials.class");var rules=ContextManifests.class.getResourceAsStream("ContextManifests.class")){
   fingerprint=CanonicalJson.hash(db.json(Map.of("csv",CanonicalJson.hashBytes(Objects.requireNonNull(bytes).readAllBytes()),"rules",CanonicalJson.hashBytes(Objects.requireNonNull(rules).readAllBytes()))));
  }
  catch(java.io.IOException e){throw new IllegalStateException("Cannot identify the fixed CSV runtime");}
  return Map.of("program","deterministic-csv-v1","algorithm","report-minus-detail-v1","codeHash",fingerprint,"modelCalls",0,"tools",List.of("csv.reconcile.readonly.v1"));
 }
 private List<Map<String,Object>> configurations(Map<String,Object> c){
  var env=db.one("SELECT * FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),c.get("environment_id"));
  var refs=new TreeMap<String,Long>();refs.put(uuid(env.get("id")),number(env.get("revision")));
  db.object(db.object(env.get("published_json")).get("dependencyRevisions")).forEach((k,v)->refs.put(k,number(v)));
  var result=new ArrayList<Map<String,Object>>();
  refs.forEach((key,revision)->{var version=db.one("SELECT content_json FROM config_versions WHERE tenant_id=? AND object_id=? AND revision=?",id(TENANT),id(key),revision);result.add(Map.of("objectId",key,"revision",revision,"sha256",CanonicalJson.hash(db.json(db.object(version.get("content_json"))))));});
  return result;
 }
 private Map<String,Object> content(Actor actor,UUID conversation,String purpose,Map<String,Object> answers){
  var c=scope.ownConversation(actor,conversation);var grant=db.one("SELECT revision FROM grants WHERE tenant_id=? AND subject_id=? AND environment_id=? AND active=TRUE",id(TENANT),id(actor.id()),c.get("environment_id"));
  var messages=db.rows("SELECT id,input_revision,text_content FROM messages WHERE tenant_id=? AND conversation_id=? ORDER BY input_revision",id(TENANT),id(conversation)).stream().map(r->Map.<String,Object>of("messageId",uuid(r.get("id")),"inputRevision",number(r.get("input_revision")),"sha256",CanonicalJson.hashBytes(r.get("text_content").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"characters",r.get("text_content").toString().length())).toList();
  var files=db.rows("SELECT id,sha256,bytes,file_name FROM attachments WHERE tenant_id=? AND conversation_id=? AND state='ready' AND source_active=TRUE ORDER BY id",id(TENANT),id(conversation)).stream().map(r->Map.<String,Object>of("attachmentId",uuid(r.get("id")),"sha256",hex(r.get("sha256")),"bytes",number(r.get("bytes")),"name",r.get("file_name"))).toList();
  var runtime=new LinkedHashMap<>(runtime());if(purpose.equals("understanding"))runtime.put("tools",List.of());
  var manifest=new LinkedHashMap<String,Object>();manifest.put("version","csv-context-v1");manifest.put("ruleset","csv-scope-rules-v1");manifest.put("purpose",purpose);manifest.put("tenantId",TENANT.toString());manifest.put("conversationId",conversation.toString());manifest.put("subjectId",actor.id().toString());manifest.put("inputRevision",number(c.get("input_revision")));manifest.put("authorizationRevision",scope.actor(actor.alias()).revision());manifest.put("environmentId",uuid(c.get("environment_id")));manifest.put("authorizationRef","grant:"+actor.id()+":"+uuid(c.get("environment_id"))+":"+number(grant.get("revision")));manifest.put("messages",messages);manifest.put("attachments",files);manifest.put("configurations",configurations(c));manifest.put("runtime",runtime);manifest.put("tools",purpose.equals("understanding")?List.of():List.of("csv.reconcile.readonly.v1"));manifest.put("providedScope",answers);return manifest;
 }
 public Map<String,Object> freeze(Actor actor,UUID conversation,String purpose,Map<String,Object> answers){
  var value=content(actor,conversation,purpose,answers);String hash=CanonicalJson.hash(db.json(value));
  var prior=db.rows("SELECT * FROM context_manifests WHERE tenant_id=? AND conversation_id=? AND purpose=? AND manifest_hash=?",id(TENANT),id(conversation),purpose,hex(hash));
  if(!prior.isEmpty()){if(!prior.getFirst().get("state").equals("active"))throw new Problem(409,"CONTEXT_STALE");return prior.getFirst();}
  UUID ref=UUID.randomUUID();db.update("INSERT INTO context_manifests(tenant_id,id,conversation_id,subject_id,purpose,input_revision,authorization_revision,manifest_hash,manifest_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id(TENANT),id(ref),id(conversation),id(actor.id()),purpose,value.get("inputRevision"),value.get("authorizationRevision"),hex(hash),db.json(value),LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC));
  return db.one("SELECT * FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(ref));
 }
 public Map<String,Object> understand(Actor actor,UUID conversation,Map<String,Object> answers){
  var manifest=freeze(actor,conversation,"understanding",answers);var output=rules(actor,conversation,answers);String outputHash=CanonicalJson.hash(db.json(output));
  var prior=db.rows("SELECT * FROM understanding_runs WHERE tenant_id=? AND context_manifest_id=? AND output_hash=? AND state<>'stale'",id(TENANT),manifest.get("id"),hex(outputHash));Map<String,Object> run;
  if(prior.isEmpty()){
   UUID ref=UUID.randomUUID();db.update("INSERT INTO understanding_runs VALUES(?,?,?,?,?,'understanding',?,0,?,?,?,?)",id(TENANT),id(ref),id(conversation),manifest.get("id"),manifest.get("input_revision"),"[]",output.get("state"),db.json(output),hex(outputHash),LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC));
   run=db.one("SELECT * FROM understanding_runs WHERE tenant_id=? AND id=?",id(TENANT),id(ref));
   db.audit(actor.id(),"understanding.prepare",ref,Map.of("purpose","understanding","modelCalls",0,"tools",List.of(),"contextManifestHash",hex(manifest.get("manifest_hash"))));
   db.event(conversation,"understanding.prepared",Map.of("id",ref.toString(),"inputRevision",number(manifest.get("input_revision")),"contextManifestRef",uuid(manifest.get("id")),"contextManifestHash",hex(manifest.get("manifest_hash")),"state",output.get("state"),"modelCalls",0,"tools",List.of()));
  }else run=prior.getFirst();
  var response=new LinkedHashMap<>(output);response.put("id",uuid(run.get("id")));response.put("inputRevision",number(manifest.get("input_revision")));response.put("contextManifestRef",uuid(manifest.get("id")));response.put("contextManifestHash",hex(manifest.get("manifest_hash")));response.put("outputHash",outputHash);response.put("purpose","understanding");response.put("tools",List.of());response.put("modelCalls",0);return response;
 }
 private Map<String,Object> rules(Actor actor,UUID conversation,Map<String,Object> answers){
  var c=scope.ownConversation(actor,conversation);var env=db.object(scope.environment(actor,UUID.fromString(uuid(c.get("environment_id")))).get("published_json"));String zone=Objects.toString(env.get("timeZone"),"UTC");
  var files=db.rows("SELECT id,file_name FROM attachments WHERE tenant_id=? AND conversation_id=? AND state='ready' AND source_active=TRUE ORDER BY id",id(TENANT),id(conversation));
  var suggestion=new LinkedHashMap<String,Object>();for(String key:List.of("detailAttachmentId","reportAttachmentId","from","to","currency","status"))suggestion.put(key,null);suggestion.put("timeZone",zone);
  for(var file:files){String name=file.get("file_name").toString().toLowerCase(Locale.ROOT);String key=name.equals("detail.csv")?"detailAttachmentId":name.equals("report.csv")?"reportAttachmentId":null;if(key!=null){if(suggestion.get(key)!=null)suggestion.put(key,"");else suggestion.put(key,uuid(file.get("id")));}}
  suggestion.putAll(answers);var questions=new ArrayList<String>();
  boolean chosen=true;for(String key:List.of("detailAttachmentId","reportAttachmentId"))if(suggestion.get(key)==null||suggestion.get(key).toString().isEmpty()){chosen=false;questions.add(key.equals("detailAttachmentId")?"请选择明细 CSV 材料。":"请选择报表 CSV 材料。");}
  if(chosen){
   var entries=new ArrayList<Materials.Entry>();for(String key:List.of("detailAttachmentId","reportAttachmentId"))entries.addAll(materials.parse(materials.source(conversation,UUID.fromString(suggestion.get(key).toString()))));
   var currencies=new TreeSet<String>();var statuses=new TreeSet<String>();entries.forEach(r->{currencies.add(r.currency());statuses.add(r.status());});
   if(suggestion.get("currency")==null&&currencies.size()==1)suggestion.put("currency",currencies.first());if(suggestion.get("status")==null&&statuses.size()==1)suggestion.put("status",statuses.first());
   ZoneId timeZone=ZoneId.of(suggestion.get("timeZone").toString());Instant first=entries.stream().map(Materials.Entry::occurredAt).min(Comparator.naturalOrder()).orElseThrow(),last=entries.stream().map(Materials.Entry::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
   if(suggestion.get("from")==null)suggestion.put("from",time(first.atZone(timeZone).toLocalDate().atStartOfDay(timeZone).toInstant()));if(suggestion.get("to")==null)suggestion.put("to",time(last.atZone(timeZone).toLocalDate().plusDays(1).atStartOfDay(timeZone).toInstant()));
  }
  for(String key:List.of("from","to","currency","status"))if(suggestion.get(key)==null)questions.add("请明确检查范围字段："+key+"。");
  return Map.of("state",questions.isEmpty()?"ready":"needs_input","suggestedScope",suggestion,"questions",questions,"ruleVersion","csv-scope-rules-v1","confirmationRequired",true);
 }
 public void verify(UUID reference,String expectedHash,UUID conversation,boolean firstAdmission){
  var row=db.one("SELECT * FROM context_manifests WHERE tenant_id=? AND id=? AND conversation_id=?",id(TENANT),id(reference),id(conversation));var value=db.object(row.get("manifest_json"));
  if(!hex(row.get("manifest_hash")).equals(expectedHash)||!CanonicalJson.hash(db.json(value)).equals(expectedHash))throw new Problem(409,"INTEGRITY_MISMATCH");
  if(row.get("state").equals("invalidated")&&(firstAdmission||!Objects.equals(row.get("invalidation_reason"),"INPUT_CHANGED")))throw new Problem(409,"CONTEXT_STALE");
  UUID subject=UUID.fromString(uuid(row.get("subject_id")));var alias=db.one("SELECT CONVERT(alias USING utf8mb4) alias FROM subjects WHERE id=?",id(subject)).get("alias").toString();
  if(firstAdmission){var current=content(scope.actor(alias),conversation,row.get("purpose").toString(),db.object(value.get("providedScope")));if(!CanonicalJson.hash(db.json(current)).equals(expectedHash))throw new Problem(409,"CONTEXT_STALE");}
  for(Object object:(List<?>)value.get("attachments")){var file=db.object(object);var source=db.one("SELECT sha256,source_active,state FROM attachments WHERE tenant_id=? AND conversation_id=? AND id=?",id(TENANT),id(conversation),id(file.get("attachmentId").toString()));if(!Boolean.TRUE.equals(source.get("source_active"))||!source.get("state").equals("ready")||!hex(source.get("sha256")).equals(file.get("sha256")))throw new Problem(409,"SOURCE_REVOKED_OR_CHANGED");}
  for(Object object:(List<?>)value.get("configurations")){var ref=db.object(object);var version=db.one("SELECT content_json FROM config_versions WHERE tenant_id=? AND object_id=? AND revision=?",id(TENANT),id(ref.get("objectId").toString()),number(ref.get("revision")));if(!CanonicalJson.hash(db.json(db.object(version.get("content_json")))).equals(ref.get("sha256")))throw new Problem(409,"INTEGRITY_MISMATCH");}
 }
 public void verifyForExecution(UUID reference,String hash,UUID conversation){
  verify(reference,hash,conversation,false);var row=db.one("SELECT * FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(reference));var c=db.one("SELECT input_revision FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));
  if(!row.get("state").equals("active")||number(row.get("input_revision"))!=number(c.get("input_revision")))throw new Problem(409,"CONTEXT_STALE");
  if(!CanonicalJson.hash(db.json(db.object(row.get("manifest_json")).get("runtime"))).equals(CanonicalJson.hash(db.json(runtime()))))throw new Problem(409,"CONTEXT_STALE");
 }
 public Map<String,Object> read(Actor actor,UUID ref){return db.transaction(()->{var row=db.one("SELECT * FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(ref));UUID conversation=UUID.fromString(uuid(row.get("conversation_id")));scope.ownConversation(actor,conversation);verify(ref,hex(row.get("manifest_hash")),conversation,true);return Map.of("id",ref.toString(),"sha256",hex(row.get("manifest_hash")),"state",row.get("state"),"manifest",db.object(row.get("manifest_json")));});}
}
