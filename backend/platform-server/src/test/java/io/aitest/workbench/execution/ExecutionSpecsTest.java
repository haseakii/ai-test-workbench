package io.aitest.workbench.execution;

import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ProviderJson;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.material.Materials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static io.aitest.contract.ProviderTypes.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionSpecsTest {
 @TempDir Path blobs;
 @SuppressWarnings("unchecked") Map<String,Object> vector()throws Exception {
  var json=tools.jackson.databind.json.JsonMapper.builder().build();var root=json.readValue(Files.readString(Path.of("../../contracts/provider-vectors.json")),Map.class);
  return (Map<String,Object>)((Map<?,?>)((Map<?,?>)((Map<?,?>)root.get("methods")).get("start")).get("request")).get("request");
 }
 @Test void completeSpecAndContentUseExistingCrossLanguageHashDomains()throws Exception {
  var request=vector();var json=tools.jackson.databind.json.JsonMapper.builder().build();var spec=ProviderJson.read(json.writeValueAsString(request.get("spec")),ExecutionSpec.class);
  assertEquals("4c9b262c2fc88789bd61efb8e7e67eef5e8b3777b693d655a2fc7fc8f8a73a31",ExecutionSpecs.hash(spec));
  assertEquals(spec.admission().executionContentHash(),ExecutionSpecs.contentHash(spec));
  var altered=new LinkedHashMap<>((Map<String,Object>)request.get("spec"));altered.put("objective","a different operation");
  assertNotEquals(ExecutionSpecs.contentHash(spec),ExecutionSpecs.contentHash(ProviderJson.read(json.writeValueAsString(altered),ExecutionSpec.class)));
 }
 @Test void commandNamespaceAndOriginalSubmissionIdentityRemainStable()throws Exception {
  UUID binding=UUID.randomUUID(),node=UUID.randomUUID(),owner=UUID.randomUUID();
  var row=Map.<String,Object>of("id",id(binding),"node_run_id",id(node),"spec_hash",new byte[32]);var submission=ExecutionSpecs.submission(row);
  assertEquals(binding.toString(),submission.idempotencyKey());assertEquals("persistent-synthetic",submission.providerId());
  var body=Map.of("submission",submission);var first=ExecutionSpecs.command("closeSubmission",binding,body,0);var replay=ExecutionSpecs.command("closeSubmission",binding,body,0);
  assertEquals(first,replay);assertEquals(binding+":closeSubmission",first.idempotencyKey());assertNotEquals(first.idempotencyKey(),ExecutionSpecs.command("cancel",binding,body,0).idempotencyKey());
  assertNotEquals(first.requestHash(),ExecutionSpecs.command("closeSubmission",binding,Map.of("submission",submission,"other",true),0).requestHash());
  var context=ExecutionSpecs.context(row,owner,Instant.parse("2026-10-26T12:00:00.000001Z"));assertEquals("2026-10-26T12:00:30.000001Z",context.deadline());assertEquals(ExecutionSpecs.identity(binding,owner),context.subjectContext().authorizationRef());
 }
 static class RollbackProbe extends RuntimeException{}
 @Test @EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
 void dispatchSpecBindsRealApprovedContextAndFrozenOldPeriodWithoutChargingAnotherAccount()throws Exception {
  var helper=new DispatchAtomicityTest();helper.blobs=blobs;var clock=new MutableClock("2026-10-26T23:59:59.999999Z");var fixture=helper.fixture("normal",clock);var plan=helper.prepared(fixture,true);UUID binding=helper.binding(plan);
  var materials=new Materials(fixture.db(),fixture.scope(),blobs.toString());var factory=new ExecutionSpecs(fixture.db(),materials);
  try{
   assertThrows(RollbackProbe.class,()->fixture.db().transaction(()->{
    assertTrue(new QuotaLedger(fixture.db()).reserve(binding,fixture.owner().id(),"fixture-v1",100));clock.set("2026-10-27T00:00:00.000001Z");
    var connection=new ConnectionRef(ExecutionSpecs.PROVIDER,fixture.environment().toString(),1);
    var capabilities=new CapabilitySnapshot(connection,"factory-test-only",null,null,null,CanonicalJson.hash("{}"),ContextManifests.time(fixture.db().now()),ContextManifests.time(fixture.db().now().plusSeconds(120)),List.of(),List.of());
    var spec=factory.build(binding,UUID.randomUUID(),fixture.db().now(),fixture.db().now().plusSeconds(60),capabilities);var frozen=factory.quotaSnapshot(binding);
    assertEquals("2026-10-26",frozen.get("period"));assertEquals("model:fixture-v1",frozen.get("modelAccountKey"));assertEquals(CanonicalJson.hash(fixture.db().json(frozen)),spec.budget().modelQuotaSnapshotHash());assertTrue(spec.budget().userTotalAccountRef().endsWith(":2026-10-26:total"));
    assertEquals(2,spec.inputs().size());assertEquals(binding.toString(),spec.budget().reservationId());assertEquals(binding.toString(),spec.budget().platformTurnReservationId());assertEquals(0,spec.budget().maxNewCalls());assertNull(spec.budget().estimatedAmount());assertNull(spec.budget().currency());
    assertEquals(ExecutionSpecs.contentHash(spec),spec.admission().executionContentHash());assertNotEquals(ExecutionSpecs.contentHash(spec),ExecutionSpecs.hash(spec));
    var p=fixture.db().object(plan.get("plan"));assertEquals(p.get("contextManifestRef"),spec.preparation().contextManifestRef());assertEquals(p.get("contextManifestHash"),spec.preparation().contextManifestHash());assertEquals(1,spec.modelRequest().presetRevision());assertEquals(0,spec.skills().size());
    String serialized=ProviderJson.write(spec);assertFalse(serialized.contains("Synthetic transaction probe"));assertFalse(serialized.contains(blobs.toString()));assertNull(spec.primaryRepositoryId());assertNull(spec.codeCommit());assertNull(spec.deployedCommit());
    assertEquals(spec,ProviderJson.read(serialized,ExecutionSpec.class));assertTrue(fixture.db().rows("SELECT account_key FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND period_key='2026-10-27'",id(TENANT),id(fixture.owner().id())).isEmpty());
    throw new RollbackProbe();
   }));
  }finally{helper.close(fixture,plan);}
 }
 @Test @EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
 void buildRejectsWrongConnectionAndMissingFrozenPairAndReadNeverInventsLegacySpec()throws Exception {
  var helper=new DispatchAtomicityTest();helper.blobs=blobs;var fixture=helper.fixture();var plan=helper.prepared(fixture,true);UUID binding=helper.binding(plan);var factory=new ExecutionSpecs(fixture.db(),new Materials(fixture.db(),fixture.scope(),blobs.toString()));
  try{
   assertEquals("EXECUTION_SPEC_UNAVAILABLE",assertThrows(Store.Problem.class,()->factory.spec(binding)).code);
   var capabilities=new CapabilitySnapshot(new ConnectionRef(ExecutionSpecs.PROVIDER,UUID.randomUUID().toString(),1),"factory-test-only",null,null,null,CanonicalJson.hash("{}"),ContextManifests.time(fixture.db().now()),ContextManifests.time(fixture.db().now().plusSeconds(60)),List.of(),List.of());
   assertEquals("CAPABILITY_SNAPSHOT_STALE",assertThrows(Store.Problem.class,()->factory.build(binding,UUID.randomUUID(),fixture.db().now(),fixture.db().now().plusSeconds(30),capabilities)).code);
   assertThrows(Store.Problem.class,()->factory.quotaSnapshot(binding));
  }finally{helper.close(fixture,plan);}
 }
}
