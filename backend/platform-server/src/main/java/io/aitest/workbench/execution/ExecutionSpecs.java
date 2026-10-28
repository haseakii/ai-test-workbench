package io.aitest.workbench.execution;

import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ProviderJson;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.material.Materials;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static io.aitest.contract.ProviderTypes.*;
import static io.aitest.workbench.infrastructure.Store.*;

/** Builds the fixed, single-node synthetic delegation inside T_DISPATCH; performs no writes. */
public final class ExecutionSpecs {
 public static final String PROVIDER="persistent-synthetic";
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final Store db;private final Materials materials;
 public ExecutionSpecs(Store db,Materials materials){this.db=Objects.requireNonNull(db);this.materials=Objects.requireNonNull(materials);}

 public ExecutionSpec spec(UUID binding){
  var row=db.one("SELECT execution_spec_json FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));
  if(row.get("execution_spec_json")==null)throw new Problem(409,"EXECUTION_SPEC_UNAVAILABLE");
  return ProviderJson.read(row.get("execution_spec_json").toString(),ExecutionSpec.class);
 }

 public ExecutionSpec build(UUID bindingId,UUID ticketId,Instant notBefore,Instant notAfter,CapabilitySnapshot capabilities){
  Objects.requireNonNull(ticketId);Objects.requireNonNull(capabilities);
  if(!notBefore.isBefore(notAfter))throw new Problem(409,"ADMISSION_EXPIRED");
  var b=db.one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(bindingId));
  var p=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(bindingId));
  if(!"approved".equals(p.get("state"))||Boolean.TRUE.equals(b.get("stopped"))||b.get("closed_at")!=null)throw new Problem(409,"ADMISSION_BLOCKED");
  var plan=db.object(p.get("plan_json"));String operationHash=hex(p.get("operation_hash"));
  if(notAfter.isAfter(Instant.parse(plan.get("validUntil").toString())))throw new Problem(409,"ADMISSION_EXPIRED");
  if(!CanonicalJson.hash(db.json(plan)).equals(operationHash))throw new Problem(409,"INTEGRITY_MISMATCH");
  UUID conversation=UUID.fromString(uuid(b.get("conversation_id")));
  var c=db.one("SELECT * FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));UUID owner=UUID.fromString(uuid(c.get("subject_id")));
  String environmentId=uuid(c.get("environment_id"));long environmentRevision=number(plan.get("environmentRevision"));
  if(!environmentId.equals(plan.get("environmentId")))throw new Problem(409,"INTEGRITY_MISMATCH");
  var environment=version(environmentId,environmentRevision);var dependencies=db.object(plan.get("dependencyRevisions"));
  String workflowId=environment.get("workflowRef").toString(),modelId=environment.get("modelRef").toString();
  long workflowRevision=number(dependencies.get(workflowId)),modelRevision=number(dependencies.get(modelId));
  var workflow=version(workflowId,workflowRevision);var model=version(modelId,modelRevision);
  var connection=new ConnectionRef(PROVIDER,environmentId,environmentRevision);
  if(!connection.equals(capabilities.connection())||Instant.parse(capabilities.observedAt()).isAfter(notBefore)||!notBefore.isBefore(Instant.parse(capabilities.validUntil())))throw new Problem(409,"CAPABILITY_SNAPSHOT_STALE");

  String contextId=Objects.toString(plan.get("contextManifestRef"),"");String contextHash=Objects.toString(plan.get("contextManifestHash"),"");
  var contextRow=db.one("SELECT * FROM context_manifests WHERE tenant_id=? AND id=? AND conversation_id=?",id(TENANT),id(contextId),id(conversation));
  var context=db.object(contextRow.get("manifest_json"));
  if(!"active".equals(contextRow.get("state"))||!contextHash.equals(hex(contextRow.get("manifest_hash")))||!contextHash.equals(CanonicalJson.hash(db.json(context))))throw new Problem(409,"CONTEXT_STALE");
  if(number(context.get("inputRevision"))!=number(plan.get("inputRevision"))||number(context.get("authorizationRevision"))!=number(p.get("authorization_revision"))||!owner.toString().equals(context.get("subjectId")))throw new Problem(409,"CONTEXT_STALE");
  String grantRef=context.get("authorizationRef").toString();var inputs=new ArrayList<ExecutionSpecInputsItem>();
  for(String field:List.of("detailAttachmentId","reportAttachmentId")){
   String attachmentId=plan.get(field).toString(),sha=plan.get(field+"Hash").toString();
   var source=db.one("SELECT sha256,state,source_active FROM attachments WHERE tenant_id=? AND conversation_id=? AND id=?",id(TENANT),id(conversation),id(attachmentId));
   if(!Boolean.TRUE.equals(source.get("source_active"))||!"ready".equals(source.get("state"))||!sha.equals(hex(source.get("sha256"))))throw new Problem(409,"SOURCE_REVOKED_OR_CHANGED");
   inputs.add(new ExecutionSpecInputsItem(attachmentId,sha,grantRef+":attachment:"+attachmentId+":"+sha));
  }
  var runtime=db.object(context.get("runtime"));var quota=quotaSnapshot(bindingId);String period=quota.get("period").toString();
  if(!owner.toString().equals(quota.get("subjectId"))||!QuotaLedger.modelAccount(plan.get("model").toString()).equals(quota.get("modelAccountKey")))throw new Problem(409,"QUOTA_BINDING_CONFLICT");
  String totalRef=accountRef(owner,period,"total"),modelKey=quota.get("modelAccountKey").toString();
  var prep=new ExecutionSpecPreparation(number(plan.get("inputRevision")),environmentRevision,environmentRevision,
   configurationRef(modelId,modelRevision),CanonicalJson.hash(db.json(model)),
   "context:"+contextId+":attachments",CanonicalJson.hash(db.json(context.get("attachments"))),
   "approval:"+uuid(p.get("id"))+":"+number(p.get("revision"))+":"+operationHash,
   configurationRef(workflowId,workflowRevision),CanonicalJson.hash(db.json(workflow)),
   "context:"+contextId+":runtime",CanonicalJson.hash(db.json(runtime)),
   contextId,contextHash,number(p.get("authorization_revision")));
  var budget=new ExecutionSpecBudget("quota-reservation:"+TENANT+":"+bindingId+":"+period+":total:"+modelKey,
   CanonicalJson.hash(db.json(quota)),bindingId.toString(),owner.toString(),0,1,null,null,
   "block_new_billable_actions",null,totalRef,bindingId.toString(),false,"adapter_only");
  var body=new LinkedHashMap<String,Object>();body.put("contractVersion","initial-draft");body.put("purpose","investigation");body.put("tenantId",TENANT.toString());body.put("conversationId",conversation.toString());
  // This D2 workflow has exactly one node; neither reference claims an external task/run.
  body.put("workflowRunId",uuid(b.get("node_run_id")));body.put("workflowVersion",configurationRef(workflowId,workflowRevision));body.put("nodeRunId",uuid(b.get("node_run_id")));body.put("bindingId",bindingId.toString());body.put("ownershipEpoch",1);
  body.put("connection",connection);body.put("capabilitySnapshotHash",CanonicalJson.hash(ProviderJson.write(capabilities)));body.put("agentId","deterministic-csv-v1");
  // There is no independent system table in this synthetic slice. The environment is its scope.
  body.put("systemId",environmentId);body.put("environmentId",environmentId);body.put("primaryRepositoryId",null);body.put("deployedCommit",null);body.put("codeCommit",null);
  body.put("objective","Apply the approved reconcile_csv_readonly_v1 scope; report minus detail, with no model or external resource call.");
  body.put("preparation",prep);body.put("inputs",inputs);body.put("executionIdentityRef",identity(bindingId,owner));body.put("workspaceAuthorizationRef","fixture-workspace:"+materials.scope());body.put("resourceAuthorizationRefs",inputs.stream().map(ExecutionSpecInputsItem::authorizationRef).toList());
  body.put("modelRequest",new ExecutionSpecModelRequest(plan.get("model").toString(),modelId,modelRevision));body.put("skills",List.of());body.put("budget",budget);body.put("deadline",ContextManifests.time(notAfter));
  body.put("outputContract",new ExecutionSpecOutputContract("fixture:csv-evidence-v1",List.of("text/csv","application/json"),true));
  String contentHash=CanonicalJson.hash(JSON.writeValueAsString(body));
  if(p.get("authority_json")==null)throw new Problem(409,"APPROVAL_AUTHORITY_MISSING");
  String authority="approval:"+uuid(p.get("id"))+":authority:"+CanonicalJson.hash(db.json(db.object(p.get("authority_json"))));
  body.put("admission",new AdmissionTicket(ticketId.toString(),bindingId.toString(),contentHash,number(p.get("authorization_revision")),uuid(p.get("id")),number(p.get("revision")),ContextManifests.time(notBefore),ContextManifests.time(notAfter),authority));
  return ProviderJson.read(JSON.writeValueAsString(body),ExecutionSpec.class);
 }

 /** Persist this JSON beside the spec in the same transaction; live account balances can change. */
 public Map<String,Object> quotaSnapshot(UUID binding){
  var reservation=db.one("SELECT * FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
  if(!"reserved".equals(reservation.get("state"))||reservation.get("total_account_key")==null||reservation.get("model_account_key")==null)throw new Problem(409,"QUOTA_SCOPE_UNKNOWN");
  String total=new String((byte[])reservation.get("total_account_key"),StandardCharsets.UTF_8),model=new String((byte[])reservation.get("model_account_key"),StandardCharsets.UTF_8),period=reservation.get("period_key").toString();
  if(!"total".equals(total)||!model.startsWith("model:")||model.length()==6||!LocalDate.parse(period).toString().equals(period))throw new Problem(409,"QUOTA_SCOPE_UNKNOWN");
  UUID subject=UUID.fromString(uuid(reservation.get("subject_id")));var accounts=new ArrayList<Map<String,Object>>();
  for(String account:List.of(model,total)){
   var row=db.one("SELECT turn_limit,consumed,reserved FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND period_key=? AND account_key=?",id(TENANT),id(subject),period,key(account));
   accounts.add(Map.of("accountRef",accountRef(subject,period,account),"accountKey",account,"turnLimit",number(row.get("turn_limit")),"consumed",number(row.get("consumed")),"reserved",number(row.get("reserved"))));
  }
  return Map.of("tenantId",TENANT.toString(),"bindingId",binding.toString(),"subjectId",subject.toString(),"period",period,"totalAccountKey",total,"modelAccountKey",model,"state","reserved","accounts",List.copyOf(accounts));
 }
 private Map<String,Object> version(String reference,long revision){
  var row=db.one("SELECT content_json,content_hash FROM config_versions WHERE tenant_id=? AND object_id=? AND revision=?",id(TENANT),id(reference),revision);var content=db.object(row.get("content_json"));
  if(!hex(row.get("content_hash")).equals(CanonicalJson.hash(db.json(content))))throw new Problem(409,"INTEGRITY_MISMATCH");return content;
 }
 private static String configurationRef(String id,long revision){return "configuration:"+id+":"+revision;}
 public static String accountRef(UUID subject,String period,String account){return "quota-account:"+TENANT+":"+subject+":"+period+":"+account;}
 public static String identity(UUID binding,UUID owner){return "platform-delegation:"+binding+":"+owner;}
 public static BindingRef binding(Map<String,Object> row){return new BindingRef(uuid(row.get("id")),uuid(row.get("node_run_id")),PROVIDER);}
 public static SubmissionRef submission(Map<String,Object> row){var b=binding(row);return new SubmissionRef(b.bindingId(),b.nodeRunId(),PROVIDER,TENANT.toString(),b.bindingId(),hex(row.get("spec_hash")));}
 public static CallContext context(Map<String,Object> row,UUID owner,Instant now){UUID binding=UUID.fromString(uuid(row.get("id")));return new CallContext("initial-draft",UUID.randomUUID().toString(),TENANT.toString(),new CallContextSubjectContext(owner.toString(),identity(binding,owner)),ContextManifests.time(now.plusSeconds(30)));}
 public static CommandRef command(String method,UUID binding,Object body,long expectedRevision){
  Objects.requireNonNull(body);String key=binding+":"+method;return new CommandRef(UUID.nameUUIDFromBytes((method+binding).getBytes(StandardCharsets.UTF_8)).toString(),key,CanonicalJson.hash(JSON.writeValueAsString(Map.of("method",method,"body",body))),expectedRevision);
 }
 public static String hash(ExecutionSpec spec){return CanonicalJson.hash(ProviderJson.write(spec));}
 public static String contentHash(ExecutionSpec spec){var content=new LinkedHashMap<>(JSON.readValue(ProviderJson.write(spec),Map.class));content.remove("admission");return CanonicalJson.hash(JSON.writeValueAsString(content));}
}
