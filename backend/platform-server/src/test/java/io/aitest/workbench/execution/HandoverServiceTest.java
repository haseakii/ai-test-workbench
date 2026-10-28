package io.aitest.workbench.execution;

import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ProviderJson;
import io.aitest.contract.ProviderTypes.*;
import io.aitest.workbench.configuration.ConfigurationService;
import io.aitest.workbench.handover.HandoverPackage;
import io.aitest.workbench.handover.HandoverService;
import io.aitest.workbench.identity.ContentReview;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.material.Materials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real fixed-flow service boundary. Rollback scenarios are not process-durability evidence. */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class HandoverServiceTest {
    @TempDir Path blobs;
    interface Scenario {void run()throws Exception;}
    DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
    Materials materials(DispatchAtomicityTest.Fixture f)throws Exception{return new Materials(f.db(),f.scope(),blobs.toString());}
    HandoverService service(DispatchAtomicityTest.Fixture f)throws Exception{return new HandoverService(f.db(),f.scope(),materials(f));}
    ConfigurationService config(DispatchAtomicityTest.Fixture f){return new ConfigurationService(f.db(),f.scope());}
    void rollback(DispatchAtomicityTest.Fixture f,Scenario scenario){
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(f.db().jdbc.getDataSource()))).execute(status->{
            status.setRollbackOnly();f.db().one("SELECT id FROM tenants WHERE id=? FOR UPDATE",id(TENANT));
            try{scenario.run();}catch(RuntimeException|Error e){throw e;}catch(Exception e){throw new AssertionError("Handover fixture failed",e);}return null;
        });
    }
    Scope.Actor actor(DispatchAtomicityTest.Fixture f,String role){
        UUID subject=UUID.randomUUID();String alias="handover-"+UUID.randomUUID();
        f.db().update("INSERT INTO subjects VALUES(?,?)",id(subject),key(alias));
        f.db().update("INSERT INTO memberships(tenant_id,subject_id,role_name,state) VALUES(?,?,?,'active')",id(TENANT),id(subject),role);
        f.db().update("INSERT INTO quota_policies(tenant_id,subject_id) VALUES(?,?)",id(TENANT),id(subject));
        config(f).grant(f.scope().actor("admin"),alias,f.environment(),role,UUID.randomUUID().toString());return f.scope().actor(alias);
    }
    HandoverPackage value(Map<String,Object> envelope){var value=(HandoverPackage)envelope.get("package");assertEquals(value.sha256(),envelope.get("packageSha256"));return value;}
    void problem(String code,int status,Runnable action){var error=assertThrows(Store.Problem.class,action::run);assertEquals(code,error.code);assertEquals(status,error.status);}
    long exportAudits(DispatchAtomicityTest.Fixture f,UUID conversation){return number(f.db().one("SELECT COUNT(*) n FROM audit_entries WHERE tenant_id=? AND object_id=? AND action_name='handover.export'",id(TENANT),id(conversation)).get("n"));}
    String state(DispatchAtomicityTest.Fixture f,UUID conversation){
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("conversation",f.db().one("SELECT state,revision,input_revision,event_sequence FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(conversation)));
        snapshot.put("bindings",f.db().rows("SELECT HEX(id) id,state,stopped,CAST(accepted_at AS CHAR) accepted,CAST(closed_at AS CHAR) closed,effect_status FROM execution_bindings WHERE tenant_id=? AND conversation_id=? ORDER BY id",id(TENANT),id(conversation)));
        snapshot.put("reservations",f.db().rows("SELECT HEX(r.binding_id) binding,r.period_key,r.state,HEX(r.total_account_key) total_key,HEX(r.model_account_key) model_key FROM quota_reservations r JOIN execution_bindings b ON b.tenant_id=r.tenant_id AND b.id=r.binding_id WHERE b.tenant_id=? AND b.conversation_id=? ORDER BY r.binding_id",id(TENANT),id(conversation)));
        snapshot.put("accounts",f.db().rows("SELECT CONVERT(account_key USING utf8mb4) account,period_key,turn_limit,reserved,consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=? ORDER BY period_key,account_key",id(TENANT),id(f.owner().id())));
        snapshot.put("slots",f.db().rows("SELECT occupied,slot_limit FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())));
        return CanonicalJson.hash(f.db().json(snapshot));
    }
    UUID complete(DispatchAtomicityTest h,DispatchAtomicityTest.Fixture f,Map<String,Object> plan)throws Exception {
        assertTrue(h.dispatch(f,plan));startPort(h,f,plan);h.reconcile(f,plan);
        UUID conversation=h.conversation(f,plan);var snapshot=f.conversations().snapshot(f.owner(),conversation);assertEquals("awaiting_verification",snapshot.get("state"));
        var artifact=f.db().one("SELECT id FROM artifacts WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(plan)));return UUID.fromString(uuid(artifact.get("id")));
    }
    StartReceipt startPort(DispatchAtomicityTest h,DispatchAtomicityTest.Fixture f,Map<String,Object> p)throws Exception {
        UUID binding=h.binding(p);var row=f.db().one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));var submission=ExecutionSpecs.submission(row);var spec=new ExecutionSpecs(f.db(),materials(f)).spec(binding);
        var result=f.provider().start(ExecutionSpecs.context(row,f.owner().id(),f.db().now()),new StartRequest(submission,ExecutionSpecs.command("start",binding,Map.of("submission",submission,"spec",spec),0),spec)).toCompletableFuture().get();
        if(result instanceof ResultTrue<StartReceipt> accepted)return accepted.value();throw new AssertionError("Synthetic Provider did not accept the original submission");
    }
    void rebindContext(DispatchAtomicityTest.Fixture f,Map<String,Object> p,java.util.function.Consumer<Map<String,Object>> change){
        var plan=new LinkedHashMap<>(f.db().object(p.get("plan")));UUID reference=UUID.fromString(plan.get("contextManifestRef").toString());
        var row=f.db().one("SELECT manifest_json FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(reference));var manifest=new LinkedHashMap<>(f.db().object(row.get("manifest_json")));change.accept(manifest);
        String hash=CanonicalJson.hash(f.db().json(manifest));plan.put("contextManifestHash",hash);
        f.db().update("UPDATE context_manifests SET manifest_json=?,manifest_hash=?,input_revision=? WHERE tenant_id=? AND id=?",f.db().json(manifest),hex(hash),number(manifest.get("inputRevision")),id(TENANT),id(reference));
        f.db().update("UPDATE approvals SET plan_json=?,operation_hash=? WHERE tenant_id=? AND id=?",f.db().json(plan),hex(CanonicalJson.hash(f.db().json(plan))),id(TENANT),id(p.get("approvalId").toString()));
    }
    @Test void completedFixedCsvFlowExportsAuthorizedFactsHashesVerificationAndAudit()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,true);UUID artifact=complete(h,f,plan),conversation=h.conversation(f,plan);
            materials(f).verify(f.owner(),artifact,1,true,"Synthetic CSV result inspected",UUID.randomUUID().toString());
            String before=state(f,conversation);var result=value(service(f).export(f.owner(),conversation));
            assertEquals(before,state(f,conversation));assertEquals("resolved",result.conversation().state());assertEquals(HandoverPackage.ContextState.active,result.context().state());
            assertEquals(plan.get("operationHash"),result.operation().operationHash());assertEquals(f.reviewer().id(),result.operation().authority().subjectId());
            assertEquals(2,result.inputs().size());assertEquals(1,result.artifacts().size());assertEquals("0.00",result.artifacts().getFirst().result().totalDifference());
            assertEquals(1,result.verifications().size());assertTrue(result.verifications().getFirst().resolved());
            assertNotNull(result.executions().getFirst().providerRunId());assertNotNull(result.executions().getFirst().receiptRef());assertNotNull(result.executions().getFirst().authoritativeSnapshotHash());
            assertEquals(HandoverPackage.Ownership.inactive,result.executions().getFirst().ownership());assertEquals("succeeded",result.executions().getFirst().outcome());
            assertEquals("settled",result.reservations().getFirst().state());assertEquals(2,result.accountBalances().size());assertEquals("unassigned",result.responsibility().status());
            assertFalse(result.canonicalJson().contains("Synthetic transaction probe"));assertFalse(result.canonicalJson().contains(blobs.toString()));assertFalse(result.canonicalJson().contains("owner_pid"));
            assertEquals(1,exportAudits(f,conversation));var audit=f.db().one("SELECT detail_json FROM audit_entries WHERE tenant_id=? AND object_id=? AND action_name='handover.export'",id(TENANT),id(conversation));assertEquals(result.sha256(),f.db().object(audit.get("detail_json")).get("packageSha256"));
        });
    }
    @Test void unknownSubmissionAcrossUtcRolloverExportsFrozenOldPairWithoutReleaseOrNewExecution()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T23:59:00.000000Z");var f=h.fixture("normal",clock);rollback(f,()->{
            var plan=h.prepared(f,true);assertTrue(h.dispatch(f,plan));h.reconcile(f,plan);clock.set("2026-10-27T00:01:00.000000Z");UUID c=h.conversation(f,plan);String before=state(f,c);
            var result=value(service(f).export(f.owner(),c));assertEquals(before,state(f,c));
            assertEquals("acceptance_unknown",result.executions().getFirst().state());assertEquals(HandoverPackage.Ownership.unknown,result.executions().getFirst().ownership());assertEquals(HandoverPackage.EffectStatus.unknown,result.executions().getFirst().effectStatus());
            assertNull(result.executions().getFirst().providerRunId());assertEquals("2026-10-26",result.reservations().getFirst().period());assertEquals("reserved",result.reservations().getFirst().state());
            assertEquals(Set.of("total","model:fixture-v1"),new HashSet<>(result.accountBalances().stream().map(HandoverPackage.AccountBalance::accountKey).toList()));
            assertTrue(result.accountBalances().stream().allMatch(b->b.period().equals("2026-10-26")&&b.reserved()==1&&b.consumed()==0));assertTrue(result.unresolved().stream().anyMatch(u->u.code().equals("OPEN_BINDING_RETAINED")));
            assertTrue(f.db().rows("SELECT * FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(plan))).isEmpty());
        });
    }
    @Test void legacyUnknownFrozenPairStaysUnknownAndDoesNotGuessCurrentAccounts()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,true);assertTrue(h.dispatch(f,plan));h.reconcile(f,plan);UUID c=h.conversation(f,plan);
            // Rollback-only synthetic legacy canary; balances and the original period stay intact.
            f.db().update("UPDATE quota_reservations SET total_account_key=NULL,model_account_key=NULL WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(plan)));
            String before=state(f,c);var result=value(service(f).export(f.owner(),c));assertEquals(before,state(f,c));
            assertNull(result.reservations().getFirst().modelAccountKey());assertNull(result.reservations().getFirst().totalAccountKey());assertTrue(result.accountBalances().isEmpty());
            assertTrue(result.unresolved().stream().anyMatch(u->u.code().equals("LEGACY_QUOTA_PAIR_UNKNOWN")));
        });
    }
    @Test void newInputExportsStalePlanAndOriginalContextHashWithoutOldResultBody()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,true);complete(h,f,plan);UUID c=h.conversation(f,plan);var fixed=f.db().object(plan.get("plan"));
            var snapshot=f.conversations().snapshot(f.owner(),c);f.conversations().message(f.owner(),c,UUID.randomUUID().toString(),"New input invalidates old result",number(snapshot.get("revision")),UUID.randomUUID().toString());
            problem("CONTEXT_STALE",409,()->f.preparation().context(f.owner(),UUID.fromString(fixed.get("contextManifestRef").toString())));
            String before=state(f,c);var result=value(service(f).export(f.owner(),c));assertEquals(before,state(f,c));
            assertEquals(HandoverPackage.ContextState.stale,result.context().state());assertEquals("INPUT_CHANGED",result.context().invalidationCode());assertEquals(fixed.get("contextManifestHash"),result.context().sha256());assertEquals(plan.get("operationHash"),result.operation().operationHash());
            assertTrue(result.artifacts().isEmpty());assertTrue(result.verifications().isEmpty());assertTrue(result.unresolved().stream().anyMatch(u->u.code().equals("HISTORIC_RESULT_NOT_EXPORTED")));assertFalse(result.canonicalJson().contains("New input invalidates old result"));
        });
    }
    @Test void peerReviewerBodyLeaseAndAdminMetadataDoNotAuthorizeOwnerExport()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,false);UUID c=h.conversation(f,plan);var peer=actor(f,"member");var reviewer=actor(f,"reviewer");var review=new ContentReview(f.db(),f.scope());
            review.eligibility(f.scope().actor("admin"),reviewer.alias(),f.environment(),true,reviewer.revision(),"Synthetic handover reviewer",UUID.randomUUID().toString());var current=f.scope().actor(reviewer.alias());review.lease(current,c,"Inspect messages only",60,UUID.randomUUID().toString());assertFalse(((List<?>)review.read(current,c).get("messages")).isEmpty());
            var service=service(f);for(var unauthorized:List.of(peer,current,f.scope().actor("admin")))problem("NOT_FOUND",404,()->service.export(unauthorized,c));
            assertEquals(0,exportAudits(f,c));assertNotNull(service.export(f.owner(),c).get("packageSha256"));assertEquals(1,exportAudits(f,c));
        });
    }
    @Test void revokedOwnerCannotReuseOldActorForExportAndDeniedAttemptIsNotAuditedAsExport()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,false);UUID c=h.conversation(f,plan);var service=service(f);service.export(f.owner(),c);assertEquals(1,exportAudits(f,c));
            config(f).revoke(f.scope().actor("admin"),f.owner().alias(),f.environment(),UUID.randomUUID().toString());String before=state(f,c);
            problem("NOT_FOUND",404,()->service.export(f.owner(),c));assertEquals(before,state(f,c));assertEquals(1,exportAudits(f,c));
        });
    }
    @Test void revokedSourceBlocksEntireDerivedPackageAndRetainsUnknownOccupation()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,true);assertTrue(h.dispatch(f,plan));h.reconcile(f,plan);UUID c=h.conversation(f,plan);var fixed=f.db().object(plan.get("plan"));
            materials(f).revoke(f.owner(),UUID.fromString(fixed.get("detailAttachmentId").toString()),UUID.randomUUID().toString());String before=state(f,c);var service=service(f);
            problem("SOURCE_REVOKED_OR_CHANGED",409,()->service.export(f.owner(),c));assertEquals(before,state(f,c));assertEquals(0,exportAudits(f,c));assertEquals(1,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
        });
    }
    @Test void planContextAndArtifactTamperingPreventExportWithoutPublishingAudit()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,true);UUID artifact=complete(h,f,plan),c=h.conversation(f,plan);var service=service(f);var fixed=f.db().object(plan.get("plan"));
            var original=f.db().one("SELECT plan_json FROM approvals WHERE tenant_id=? AND id=?",id(TENANT),id(plan.get("approvalId").toString())).get("plan_json");
            f.db().update("UPDATE approvals SET plan_json=JSON_SET(plan_json,'$.currency','USD') WHERE tenant_id=? AND id=?",id(TENANT),id(plan.get("approvalId").toString()));problem("INTEGRITY_MISMATCH",409,()->service.export(f.owner(),c));
            f.db().update("UPDATE approvals SET plan_json=? WHERE tenant_id=? AND id=?",original,id(TENANT),id(plan.get("approvalId").toString()));
            var context=f.db().one("SELECT manifest_json FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(fixed.get("contextManifestRef").toString())).get("manifest_json");
            f.db().update("UPDATE context_manifests SET manifest_json=JSON_SET(manifest_json,'$.purpose','changed') WHERE tenant_id=? AND id=?",id(TENANT),id(fixed.get("contextManifestRef").toString()));problem("INTEGRITY_MISMATCH",409,()->service.export(f.owner(),c));
            f.db().update("UPDATE context_manifests SET manifest_json=? WHERE tenant_id=? AND id=?",context,id(TENANT),id(fixed.get("contextManifestRef").toString()));materials(f).write(artifact,"corrupted synthetic export canary".getBytes(java.nio.charset.StandardCharsets.UTF_8));problem("INTEGRITY_MISMATCH",409,()->service.export(f.owner(),c));assertEquals(0,exportAudits(f,c));
        });
    }
    @Test void auditFailureReturnsNoPackageAndRollsBackItsOnlyWrite()throws Exception {
        var h=helper();var f=h.fixture();var plan=h.prepared(f,false);UUID c=h.conversation(f,plan);
        try{String before=state(f,c);var service=service(f);f.db().failAction="handover.export";assertThrows(DispatchAtomicityTest.InjectedFailure.class,()->service.export(f.owner(),c));assertEquals(before,state(f,c));assertEquals(0,exportAudits(f,c));}
        finally{h.close(f,plan);}
    }
    @Test void alteredPlatformSummaryCannotOverrideTheHashedProviderSummaryArtifact()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,true);UUID artifact=complete(h,f,plan),c=h.conversation(f,plan);
            f.db().update("UPDATE artifacts SET summary_json=JSON_SET(summary_json,'$.differenceCount',7) WHERE tenant_id=? AND id=?",id(TENANT),id(artifact));
            var service=service(f);problem("INTEGRITY_MISMATCH",409,()->service.export(f.owner(),c));assertEquals(0,exportAudits(f,c));
        });
    }
    @Test void selfConsistentContextHashCannotBindDifferentProvidedScopeToTheApprovedPlan()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,false);rebindContext(f,plan,manifest->{var changed=new LinkedHashMap<>(f.db().object(manifest.get("providedScope")));changed.put("currency","USD");manifest.put("providedScope",changed);});
            var service=service(f);UUID c=h.conversation(f,plan);problem("INTEGRITY_MISMATCH",409,()->service.export(f.owner(),c));assertEquals(0,exportAudits(f,c));
        });
    }
    @Test void contextInputRevisionMustMatchItsHistoricalPlanEvenWhenTheConversationHasNewInput()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,false);rebindContext(f,plan,manifest->manifest.put("inputRevision",2));var service=service(f);UUID c=h.conversation(f,plan);
            problem("INTEGRITY_MISMATCH",409,()->service.export(f.owner(),c));assertEquals(0,exportAudits(f,c));
        });
    }
    @Test void contextAttachmentDigestMustBelongToTheReferencedConversationSources()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,false);rebindContext(f,plan,manifest->{var files=new ArrayList<Object>((List<?>)manifest.get("attachments"));var first=new LinkedHashMap<>(f.db().object(files.getFirst()));first.put("sha256","c".repeat(64));files.set(0,first);manifest.put("attachments",files);});
            var service=service(f);UUID c=h.conversation(f,plan);problem("INTEGRITY_MISMATCH",409,()->service.export(f.owner(),c));assertEquals(0,exportAudits(f,c));
        });
    }
    @Test void stopBeforeAcceptanceExportsActualPermanentProofWithoutTouchingAnotherReservation()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var other=h.prepared(f,true);assertTrue(h.dispatch(f,other));h.reconcile(f,other);UUID otherConversation=h.conversation(f,other);String otherBefore=state(f,otherConversation);
            var target=h.prepared(f,true);UUID c=h.conversation(f,target);h.close(f,target);var result=value(service(f).export(f.owner(),c));var execution=result.executions().getFirst();
            assertEquals(otherBefore,state(f,otherConversation));assertTrue(execution.stopRequested());assertEquals(Boolean.FALSE,execution.providerCancelRequested());assertEquals("rejected",execution.state());assertNull(execution.providerRunId());assertNull(execution.receiptRef());assertTrue(result.reservations().isEmpty());assertTrue(result.accountBalances().isEmpty());
            var command=f.db().one("SELECT response_json FROM fixture_commands WHERE tenant_id=? AND binding_id=? AND method=_binary'closeSubmission'",id(TENANT),id(h.binding(target)));var actual=ProviderJson.read(command.get("response_json").toString(),SubmissionClosure.class);assertInstanceOf(SubmissionClosureNotAcceptedFinal.class,actual);assertEquals(((SubmissionClosureNotAcceptedFinal)actual).proofRef(),execution.closureProofRef());
            assertEquals(1,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
        });
    }
    @Test void acceptedStopExportsPersistedCancellationProofAndOriginalProviderResumePosition()throws Exception {
        var h=helper();var f=h.fixture("hold_after_accept");rollback(f,()->{
            var plan=h.prepared(f,true);assertTrue(h.dispatch(f,plan));var accepted=startPort(h,f,plan);h.reconcile(f,plan);UUID c=h.conversation(f,plan);
            f.worker().stop(f.owner(),c,UUID.randomUUID().toString());
            // Observe the persisted stop through the Worker before the independent Provider
            // scheduler can finalize its own stop. This obtains an actual typed cancel receipt.
            var queue=f.db().one("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(plan)));
            var process=ExecutionWorker.class.getDeclaredMethod("process",UUID.class,UUID.class,boolean.class);process.setAccessible(true);process.invoke(f.worker(),h.binding(plan),UUID.fromString(uuid(queue.get("id"))),true);
            String before=state(f,c);var result=value(service(f).export(f.owner(),c));assertEquals(before,state(f,c));var execution=result.executions().getFirst();
            assertTrue(execution.stopRequested());assertEquals(Boolean.TRUE,execution.providerCancelRequested());assertEquals("cancelled",execution.outcome());assertEquals(accepted.run().providerRunId(),execution.providerRunId());
            var command=f.db().one("SELECT response_json FROM fixture_commands WHERE tenant_id=? AND binding_id=? AND method=_binary'cancel'",id(TENANT),id(h.binding(plan)));var actual=ProviderJson.read(command.get("response_json").toString(),CancelReceipt.class);assertInstanceOf(CancelReceiptConfirmed.class,actual);var confirmed=(CancelReceiptConfirmed)actual;assertEquals(confirmed.proofRef(),execution.closureProofRef());assertEquals(accepted.run(),confirmed.snapshot().run());
            var rows=f.db().rows("SELECT event_json FROM fixture_events WHERE tenant_id=? AND binding_id=? ORDER BY sequence DESC",id(TENANT),id(h.binding(plan)));ExecutionSnapshot snapshot=null;
            for(var row:rows){var event=ProviderJson.read(row.get("event_json").toString(),ExecutionEvent.class);if(event.payload() instanceof EventPayloadExecutionUpdated updated){snapshot=updated.snapshot();break;}}
            assertNotNull(snapshot);assertEquals(snapshot.eventWatermark(),execution.providerEventWatermark());assertEquals(snapshot.resumeCursor(),execution.providerResumeCursor());assertEquals(CanonicalJson.hash(ProviderJson.write(snapshot)),execution.authoritativeSnapshotHash());
        });
    }
    @Test void legacyWithoutProviderSummaryAnchorExportsNullNumbersAndNoInventedRunOrClosureProof()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,false);UUID c=h.conversation(f,plan);var material=materials(f);var calculated=material.calculate(c,f.db().object(plan.get("plan")));UUID artifact=UUID.randomUUID();material.write(artifact,calculated.csv());
            // Explicit rollback-only legacy storage canary. There is no Provider run,
            // typed acceptance or closure proof, so the exporter must not invent any.
            f.db().update("INSERT INTO artifacts VALUES(?,?,?,?,?,?,'ready',1,3,?)",id(TENANT),id(artifact),id(c),id(h.binding(plan)),hex(CanonicalJson.hashBytes(calculated.csv())),calculated.csv().length,f.db().json(calculated.summary()));
            f.db().update("UPDATE execution_bindings SET state='terminal',closed_at=? WHERE tenant_id=? AND id=?",java.time.LocalDateTime.ofInstant(f.db().now(),java.time.ZoneOffset.UTC),id(TENANT),id(h.binding(plan)));var binding=f.db().one("SELECT node_run_id FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(plan)));f.db().update("UPDATE node_runs SET state='succeeded' WHERE tenant_id=? AND id=?",id(TENANT),binding.get("node_run_id"));
            String before=state(f,c);var result=value(service(f).export(f.owner(),c));assertEquals(before,state(f,c));assertEquals(1,result.artifacts().size());assertNull(result.artifacts().getFirst().result());assertTrue(result.unresolved().stream().anyMatch(u->u.code().equals("LEGACY_SUMMARY_UNANCHORED")));
            var execution=result.executions().getFirst();assertNull(execution.providerRunId());assertNull(execution.providerCancelRequested());assertNull(execution.closureProofRef());assertNull(execution.providerEventWatermark());assertNull(execution.providerResumeCursor());
        });
    }
    @Test void alignedMutableCsvMetadataCannotReplaceThePersistentArtifactReadyFact()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var plan=h.prepared(f,true);UUID artifact=complete(h,f,plan),c=h.conversation(f,plan);byte[] replacement="changed synthetic CSV with aligned mutable hashes".getBytes(java.nio.charset.StandardCharsets.UTF_8);materials(f).write(artifact,replacement);String hash=CanonicalJson.hashBytes(replacement);
            f.db().update("UPDATE artifacts SET sha256=?,bytes=? WHERE tenant_id=? AND id=?",hex(hash),replacement.length,id(TENANT),id(artifact));var row=f.db().one("SELECT descriptor_json FROM fixture_artifacts WHERE tenant_id=? AND binding_id=? AND artifact_id=?",id(TENANT),id(h.binding(plan)),id(artifact));var descriptor=new LinkedHashMap<>(f.db().object(row.get("descriptor_json")));descriptor.put("sha256",hash);descriptor.put("bytes",replacement.length);f.db().update("UPDATE fixture_artifacts SET descriptor_json=? WHERE tenant_id=? AND binding_id=? AND artifact_id=?",f.db().json(descriptor),id(TENANT),id(h.binding(plan)),id(artifact));
            var service=service(f);problem("INTEGRITY_MISMATCH",409,()->service.export(f.owner(),c));assertEquals(0,exportAudits(f,c));
        });
    }
}
