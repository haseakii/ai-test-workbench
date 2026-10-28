package io.aitest.workbench.execution;

import io.aitest.contract.CanonicalJson;
import io.aitest.workbench.configuration.ConfigurationService;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.material.Materials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class ContextManifestTest {
 @TempDir Path blobs;
 DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
 UUID conversation(DispatchAtomicityTest.Fixture f){return UUID.fromString(f.conversations().create(f.owner(),f.environment(),UUID.randomUUID().toString()).get("id").toString());}
 UUID conversation(DispatchAtomicityTest.Fixture f,Map<String,Object> p){return UUID.fromString(uuid(f.db().one("SELECT conversation_id FROM approvals WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("approvalId").toString())).get("conversation_id")));}
 Materials materials(DispatchAtomicityTest.Fixture f)throws java.io.IOException{return new Materials(f.db(),f.scope(),blobs.toString());}
 @SuppressWarnings("unchecked") Map<String,Object> plan(Map<String,Object> p){return (Map<String,Object>)p.get("plan");}
 @Test void collectingQuestionsPersistsIndependentUnderstandingWithoutExecutionOrModel()throws Exception {
  var f=helper().fixture();UUID c=conversation(f);String key=UUID.randomUUID().toString();
  var result=f.preparation().understand(f.owner(),c,0,key);
  assertEquals("needs_input",result.get("state"));assertEquals(List.of(),result.get("tools"));assertEquals(0,((Number)result.get("modelCalls")).intValue());assertEquals("understanding",result.get("purpose"));assertTrue(((List<?>)result.get("questions")).size()>=4);
  assertEquals(CanonicalJson.hash(f.db().json(result)),CanonicalJson.hash(f.db().json(f.preparation().understand(f.owner(),c,0,key))));
  for(String table:List.of("execution_bindings","outbox","quota_reservations"))assertTrue(f.db().rows("SELECT * FROM "+table+" WHERE tenant_id=? AND "+(table.equals("execution_bindings")?"conversation_id":"binding_id")+"=?",id(TENANT),id(c)).isEmpty());
  assertEquals(1,f.db().rows("SELECT id FROM understanding_runs WHERE tenant_id=? AND conversation_id=?",id(TENANT),id(c)).size());
 }
 @Test void csvRulesUseBusinessCalendarAcrossDstAndRequireConfirmation()throws Exception {
  var f=helper().fixture();UUID c=conversation(f);
  // A new immutable published environment version is legitimate test configuration.
  var config=new ConfigurationService(f.db(),f.scope());var admin=f.scope().actor("admin");
  var row=f.db().one("SELECT * FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),id(f.environment()));var content=new LinkedHashMap<>(f.db().object(row.get("published_json")));content.remove("dependencyRevisions");content.put("timeZone","America/New_York");
  var dependencies=new LinkedHashMap<String,Long>();f.db().object(f.db().object(row.get("published_json")).get("dependencyRevisions")).forEach((k,v)->dependencies.put(k,number(v)));
  config.draft(admin,f.environment(),number(row.get("revision")),CanonicalJson.hash(f.db().json(f.db().object(row.get("draft_json")))),content,UUID.randomUUID().toString());
  config.publish(admin,List.of(new ConfigurationService.Publication(f.environment().toString(),number(row.get("revision")),dependencies)),UUID.randomUUID().toString());
  byte[] csv="order_id,amount,currency,status,occurred_at\r\nO1,1.00,EUR,completed,2026-03-08T07:00:00Z\r\n".getBytes(StandardCharsets.UTF_8);
  for(String name:List.of("detail.csv","report.csv"))materials(f).upload(f.owner(),c,new MockMultipartFile("file",name,"text/csv",csv),UUID.randomUUID().toString());
  var result=f.preparation().understand(f.owner(),c,2,UUID.randomUUID().toString());var suggested=f.db().object(result.get("suggestedScope"));
  assertEquals("ready",result.get("state"));assertEquals("2026-03-08T05:00:00.000000Z",suggested.get("from"));assertEquals("2026-03-09T04:00:00.000000Z",suggested.get("to"));assertEquals("EUR",suggested.get("currency"));assertEquals("completed",suggested.get("status"));assertEquals(true,result.get("confirmationRequired"));
 }
 @Test void planBindsRecomputableDigestsWithoutCopyingMessageBodies()throws Exception {
  var h=helper();var f=h.fixture();var p=h.prepared(f,false);try{
   var plan=plan(p);var context=f.preparation().context(f.owner(),UUID.fromString(plan.get("contextManifestRef").toString()));var manifest=f.db().object(context.get("manifest"));
   assertEquals(plan.get("contextManifestHash"),CanonicalJson.hash(f.db().json(manifest)));assertEquals("investigation",manifest.get("purpose"));assertEquals(2,((List<?>)manifest.get("attachments")).size());assertEquals(3,((List<?>)manifest.get("configurations")).size());
   assertFalse(f.db().json(manifest).contains("Synthetic transaction probe"));assertTrue(f.db().json(manifest).contains(CanonicalJson.hashBytes("Synthetic transaction probe".getBytes(StandardCharsets.UTF_8))));
   assertEquals(plan.get("contextManifestRef"),uuid(f.db().one("SELECT context_manifest_id FROM approvals WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("approvalId").toString())).get("context_manifest_id")));
  }finally{h.close(f,p);}
 }
 @Test void newInputInvalidatesManifestUnderstandingAndPlanInStorage()throws Exception {
  var h=helper();var f=h.fixture();var p=h.prepared(f,false);UUID c=conversation(f,p);try{
   var snapshot=f.conversations().snapshot(f.owner(),c);f.conversations().message(f.owner(),c,UUID.randomUUID().toString(),"Revised evidence",number(snapshot.get("revision")),UUID.randomUUID().toString());
   assertEquals("invalidated",f.db().one("SELECT state FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(plan(p).get("contextManifestRef").toString())).get("state"));
   assertEquals("stale",f.db().one("SELECT state FROM understanding_runs WHERE tenant_id=? AND id=?",id(TENANT),id(plan(p).get("understandingRef").toString())).get("state"));
   assertThrows(Store.Problem.class,()->f.preparation().context(f.owner(),UUID.fromString(plan(p).get("contextManifestRef").toString())));
   assertThrows(Store.Problem.class,()->f.preparation().decide(f.reviewer(),UUID.fromString(p.get("approvalId").toString()),true,p.get("operationHash").toString(),1,"Reviewed fixed scope",UUID.randomUUID().toString()));
  }finally{h.close(f,p);}
 }
 @Test void sourceRevocationInvalidatesDerivedContextAndBlocksBodyRead()throws Exception {
  var h=helper();var f=h.fixture();var p=h.prepared(f,false);try{
   materials(f).revoke(f.owner(),UUID.fromString(plan(p).get("detailAttachmentId").toString()),UUID.randomUUID().toString());
   var row=f.db().one("SELECT state,invalidation_reason FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(plan(p).get("contextManifestRef").toString()));assertEquals("invalidated",row.get("state"));assertEquals("SOURCE_REVOKED",row.get("invalidation_reason"));
   assertThrows(Store.Problem.class,()->f.preparation().context(f.owner(),UUID.fromString(plan(p).get("contextManifestRef").toString())));
  }finally{h.close(f,p);}
 }
 @Test void revocationBeforeApprovalCreatesDurableReconciliationAndReceiptCannotReplay()throws Exception {
  var h=helper();var f=h.fixture();var p=h.prepared(f,false);UUID c=conversation(f,p);String key=UUID.randomUUID().toString();
  f.preparation().understand(f.owner(),c,3,key);
  materials(f).revoke(f.owner(),UUID.fromString(plan(p).get("reportAttachmentId").toString()),UUID.randomUUID().toString());
  assertThrows(Store.Problem.class,()->f.preparation().understand(f.owner(),c,3,key));
  assertEquals(1,f.db().rows("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(p.get("bindingId").toString())).size());
  h.reconcile(f,p);assertNotNull(f.db().one("SELECT closed_at FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("bindingId").toString())).get("closed_at"));
 }
 @Test void contextReadRechecksOwnerAndCurrentGrantAndDetectsTampering()throws Exception {
  var h=helper();var f=h.fixture();var p=h.prepared(f,false);UUID ref=UUID.fromString(plan(p).get("contextManifestRef").toString());try{
   for(String alias:List.of("bob","reviewer","admin"))assertThrows(Store.Problem.class,()->f.preparation().context(f.scope().actor(alias),ref));
   var original=f.db().one("SELECT manifest_json FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(ref)).get("manifest_json");
   f.db().update("UPDATE context_manifests SET manifest_json=JSON_SET(manifest_json,'$.purpose','changed') WHERE tenant_id=? AND id=?",id(TENANT),id(ref));
   var error=assertThrows(Store.Problem.class,()->f.preparation().context(f.owner(),ref));assertEquals("INTEGRITY_MISMATCH",error.code);
   f.db().update("UPDATE context_manifests SET manifest_json=? WHERE tenant_id=? AND id=?",original,id(TENANT),id(ref));
   h.close(f,p);new ConfigurationService(f.db(),f.scope()).revoke(f.scope().actor("admin"),f.owner().alias(),f.environment(),UUID.randomUUID().toString());
   assertThrows(Store.Problem.class,()->f.preparation().context(f.owner(),ref));
   assertEquals("AUTHORIZATION_REVOKED",f.db().one("SELECT invalidation_reason FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(ref)).get("invalidation_reason"));
  }finally{if(f.db().one("SELECT closed_at FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("bindingId").toString())).get("closed_at")==null)h.close(f,p);}
 }
}
