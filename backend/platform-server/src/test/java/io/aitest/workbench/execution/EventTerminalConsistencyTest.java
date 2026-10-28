package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ProviderJson;
import io.aitest.contract.ProviderTypes.ArtifactDescriptor;
import org.springframework.dao.DataAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class EventTerminalConsistencyTest {
 @TempDir Path blobs;
 DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
 static Map<String,Object> rawResult(Map<String,Object> fact){
  Object raw=fact.get("result_json");
  if(raw==null)return Map.of();
  return ProviderJson.read(raw instanceof Map<?,?>?ProviderJson.write(raw):raw.toString(),new TypeReference<Map<String,Object>>(){});
 }
 static ProviderObservation observation(UUID binding,Map<String,Object> fact){
  var result=rawResult(fact);ArtifactDescriptor artifact=null;ProviderObservation.CsvSummary summary=null;
  if(result.containsKey("artifactId")){
   String artifactId=result.get("artifactId").toString();
   artifact=new ArtifactDescriptor(artifactId,binding.toString(),"text/csv",number(result.get("bytes")),result.get("sha256").toString(),"fixture-artifact:"+artifactId,"metadata_only","fixture-binding:"+binding,null);
   summary=ProviderJson.read(ProviderJson.write(result.get("summary")),ProviderObservation.CsvSummary.class);
  }
  return new ProviderObservation(fact.get("status").toString(),uuid(fact.get("receipt_id")),hex(fact.get("spec_hash")),fact.get("accepted_at")==null?null:instant(fact.get("accepted_at")),artifact,summary,Objects.toString(result.get("code"),null));
 }
 static void finish(ExecutionWorker worker,UUID binding,Map<String,Object> fact){
  try{var method=ExecutionWorker.class.getDeclaredMethod("finish",UUID.class,ProviderObservation.class,String.class);method.setAccessible(true);method.invoke(worker,binding,observation(binding,fact),null);}
  catch(java.lang.reflect.InvocationTargetException error){if(error.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException(error.getCause());}
  catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
 }
 @Test void eventInsertFailureRollsBackWatermarkAndRetryReusesSequence()throws Exception {
  var f=helper().fixture();UUID c=UUID.fromString(f.conversations().create(f.owner(),f.environment(),UUID.randomUUID().toString()).get("id").toString());
  long before=number(f.db().one("SELECT event_sequence FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(c)).get("event_sequence"));
  // Actual strict MySQL VARCHAR rejection occurs after the counter write; no mock DB.
  assertThrows(DataAccessException.class,()->f.db().event(c,"x".repeat(81),Map.of("probe","rejected insert")));
  assertEquals(before,number(f.db().one("SELECT event_sequence FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(c)).get("event_sequence")),"failed event must not commit a counter without its row");
  f.db().event(c,"probe.retry",Map.of("probe","valid retry"));
  var page=f.conversations().events(f.owner(),c,f.conversations().cursor(f.owner(),c,before),100);
  var rows=(List<?>)page.get("events");assertEquals(1,rows.size());assertEquals(before+1,number(((Map<?,?>)rows.getFirst()).get("sequence")));assertEquals(false,page.get("hasMore"));
 }
 @Test void closedTerminalSameEventChangedContentIsExplicitConflict()throws Exception {
  var h=helper();var f=h.fixture();var p=h.prepared(f,true);
  try{
   assertTrue(h.dispatch(f,p));f.provider().start(h.binding(p));var fact=f.provider().execute(h.binding(p));finish(f.worker(),h.binding(p),fact);
   var result=rawResult(fact);
   // Provider transport now includes a second summary artifact. It must not change the
   // persisted terminal hash domain used before the port integration.
   var legacyResult=Map.of("artifactId",result.get("artifactId"),"sha256",result.get("sha256"),"bytes",result.get("bytes"),"summary",result.get("summary"));
   var event=f.db().one("SELECT payload_hash FROM provider_events WHERE tenant_id=? AND binding_id=? AND event_id=?",id(TENANT),id(h.binding(p)),key("terminal:"+uuid(fact.get("receipt_id"))));
   assertEquals(CanonicalJson.hash(ProviderJson.write(Map.of("status",fact.get("status"),"result",legacyResult))),hex(event.get("payload_hash")));
   finish(f.worker(),h.binding(p),fact);
   var altered=new HashMap<>(fact);altered.put("status","failed");
   var error=assertThrows(Store.Problem.class,()->finish(f.worker(),h.binding(p),altered));assertEquals("PROVIDER_EVENT_CONFLICT",error.code);
   assertEquals(1,f.db().rows("SELECT * FROM artifacts WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))).size());
   assertEquals(0,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
  }finally{h.close(f,p);}
 }
}
