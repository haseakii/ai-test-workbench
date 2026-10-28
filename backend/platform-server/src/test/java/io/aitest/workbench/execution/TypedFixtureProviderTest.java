package io.aitest.workbench.execution;

import io.aitest.contract.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionStage;
import static io.aitest.contract.ProviderTypes.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual persistent fixture behind the compiled port, not a separate fake implementation. */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class TypedFixtureProviderTest {
 @TempDir Path blobs;
 DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
 Map<String,Object> binding(DispatchAtomicityTest.Fixture f,Map<String,Object> p){return f.db().one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("bindingId").toString()));}
 CallContext context(DispatchAtomicityTest.Fixture f,Map<String,Object> p){return ExecutionSpecs.context(binding(f,p),f.owner().id(),f.db().now());}
 BindingRef ref(DispatchAtomicityTest.Fixture f,Map<String,Object> p){return ExecutionSpecs.binding(binding(f,p));}
 SubmissionRef submission(DispatchAtomicityTest.Fixture f,Map<String,Object> p){return ExecutionSpecs.submission(binding(f,p));}
 <T>T value(CompletionStage<Result<T>> stage,Class<T> type){var wire=ProviderJson.readResult(ProviderJson.write(stage.toCompletableFuture().join()),type);assertInstanceOf(ResultTrue.class,wire,()->ProviderJson.write(wire));return ((ResultTrue<T>)wire).value();}
 <T>String error(CompletionStage<Result<T>> stage){var result=stage.toCompletableFuture().join();assertInstanceOf(ResultFalse.class,result);return ((ResultFalse<T>)result).error() instanceof ProviderErrorNotSentOrAcceptedOrUnknown e?e.code():((ProviderErrorNotAccepted)((ResultFalse<T>)result).error()).code();}
 StartReceipt start(DispatchAtomicityTest.Fixture f,Map<String,Object> p){var row=binding(f,p);var sub=submission(f,p);var spec=ProviderJson.read(row.get("execution_spec_json").toString(),ExecutionSpec.class);var command=ExecutionSpecs.command("start",UUID.fromString(sub.bindingId()),Map.of("submission",sub,"spec",spec),0);return value(f.provider().start(context(f,p),new StartRequest(sub,command,spec)),StartReceipt.class);}
 void clean(DispatchAtomicityTest h,DispatchAtomicityTest.Fixture f,Map<String,Object> p)throws Exception{h.close(f,p);}

 @Test void supportedPortMethodsRoundTripStrictDtosAndPersistTwoArtifacts()throws Exception{
  var h=helper();var f=h.fixture();var p=h.prepared(f,true);try{
   assertTrue(h.dispatch(f,p));var c=context(f,p);var reference=ref(f,p);var spec=ProviderJson.read(binding(f,p).get("execution_spec_json").toString(),ExecutionSpec.class);
   var caps=value(f.provider().inspectCapabilities(c,spec.connection()),CapabilitySnapshot.class);assertTrue(caps.modelOptions().isEmpty());assertNull(caps.backendVersion());assertTrue(caps.capabilities().stream().filter(x->x.name().equals("resume_checkpoint")).allMatch(x->x.level().equals("unsupported")));
   assertTrue(value(f.provider().validateBinding(c,spec),ValidationReport.class).canStart());var accepted=start(f,p);var duplicate=start(f,p);assertEquals(accepted.receiptId(),duplicate.receiptId());assertTrue(duplicate.reused());
   var before=value(f.provider().getSnapshot(c,reference),ExecutionSnapshot.class);assertEquals("accepted",before.state());assertNull(before.modelConfirmed());assertEquals(KnownBoolean.FALSE,before.executionActive());assertEquals(0,number(f.provider().lookup(h.binding(p)).get("execution_attempts")));
   var first=value(f.provider().readEvents(c,new EventReadRequest(reference,null,1)),EventPage.class);assertEquals(1,first.events().size());assertInstanceOf(EventPayloadExecutionAccepted.class,first.events().getFirst().payload());assertEquals(before.eventWatermark(),first.events().getFirst().sequence());
   assertEquals("succeeded",f.provider().execute(h.binding(p)).get("status"));var terminal=value(f.provider().getSnapshot(c,reference),ExecutionSnapshot.class);assertEquals("terminal",terminal.state());assertEquals("succeeded",terminal.outcome());assertEquals(KnownBoolean.FALSE,terminal.executionActive());
   var later=value(f.provider().readEvents(c,new EventReadRequest(reference,first.cursor(),100)),EventPage.class);assertEquals(terminal.eventWatermark(),later.events().getLast().sequence());assertFalse(later.hasMore());assertEquals(terminal.snapshotRevision(),later.snapshotRevision());
   long next=first.events().getFirst().sequence();for(var event:later.events())assertEquals(++next,event.sequence());
   var ready=later.events().stream().filter(x->x.payload() instanceof EventPayloadArtifactReady).map(x->((EventPayloadArtifactReady)x.payload()).artifact()).toList();assertEquals(2,ready.size());assertEquals(Set.of("text/csv","application/json"),new HashSet<>(ready.stream().map(ArtifactDescriptor::mimeType).toList()));
   for(var descriptor:ready){var actual=value(f.provider().getArtifact(c,new ArtifactRequest(reference,descriptor.artifactId(),"import")),ArtifactDescriptor.class);assertEquals(descriptor,actual);byte[] bytes=new io.aitest.workbench.material.Materials(f.db(),f.scope(),blobs.toString()).read(UUID.fromString(actual.artifactId()));assertEquals(actual.bytes(),bytes.length);assertEquals(actual.sha256(),CanonicalJson.hashBytes(bytes));assertEquals("fixture-artifact:"+actual.artifactId(),actual.sourceRef());assertEquals("fixture-binding:"+reference.bindingId(),actual.authorizationRef());if(actual.mimeType().equals("application/json"))assertTrue(new String(bytes,StandardCharsets.UTF_8).contains("report-minus-detail"));}
   var unchanged=value(f.provider().getSnapshot(c,reference),ExecutionSnapshot.class);assertEquals(terminal.snapshotRevision(),unchanged.snapshotRevision());assertEquals(1,number(f.provider().lookup(h.binding(p)).get("execution_attempts")));
  }finally{clean(h,f,p);}
 }
 @Test void notFoundDoesNotProveNonAcceptanceAndCloseFencesOriginalSubmission()throws Exception{
  var h=helper();var f=h.fixture();var p=h.prepared(f,true);try{
   assertTrue(h.dispatch(f,p));var c=context(f,p);var sub=submission(f,p);var observation=value(f.provider().lookupSubmission(c,sub),SubmissionLookup.class);assertInstanceOf(SubmissionLookupNotFound.class,observation);assertFalse(((SubmissionLookupNotFound)observation).authoritativeNonAcceptance());
   var closure=value(f.provider().closeSubmission(c,new CloseSubmissionRequest(sub,ExecutionSpecs.command("closeSubmission",h.binding(p),Map.of("submission",sub),0))),SubmissionClosure.class);assertInstanceOf(SubmissionClosureNotAcceptedFinal.class,closure);assertFalse(((SubmissionClosureNotAcceptedFinal)closure).admissionFence().isBlank());assertEquals("rejected",value(f.provider().getSnapshot(c,ref(f,p)),ExecutionSnapshot.class).state());
   var spec=ProviderJson.read(binding(f,p).get("execution_spec_json").toString(),ExecutionSpec.class);assertEquals("SUBMISSION_CLOSED",error(f.provider().start(c,new StartRequest(sub,ExecutionSpecs.command("start",h.binding(p),Map.of("submission",sub,"spec",spec),1),spec))));
   assertEquals(0,number(f.provider().lookup(h.binding(p)).get("execution_attempts")));
  }finally{clean(h,f,p);}
 }
 @Test void closeAfterAcceptanceFindsReceiptWithoutCancellingOrCalculating()throws Exception{
  var h=helper();var f=h.fixture();var p=h.prepared(f,true);try{
   assertTrue(h.dispatch(f,p));var accepted=start(f,p);var c=context(f,p);var sub=submission(f,p);var before=value(f.provider().getSnapshot(c,ref(f,p)),ExecutionSnapshot.class);
   var closure=value(f.provider().closeSubmission(c,new CloseSubmissionRequest(sub,ExecutionSpecs.command("closeSubmission",h.binding(p),Map.of("submission",sub),before.snapshotRevision()))),SubmissionClosure.class);assertInstanceOf(SubmissionClosureFound.class,closure);assertEquals(accepted.receiptId(),((SubmissionClosureFound)closure).receipt().receiptId());
   for(int i=0;i<3;i++){var snapshot=value(f.provider().getSnapshot(c,ref(f,p)),ExecutionSnapshot.class);assertEquals("accepted",snapshot.state());assertEquals(before.eventWatermark(),snapshot.eventWatermark());}
   assertEquals("accepted",f.provider().lookup(h.binding(p)).get("status"));assertEquals(0,number(f.provider().lookup(h.binding(p)).get("execution_attempts")));
  }finally{clean(h,f,p);}
 }
 @Test void unsupportedInputAndPermissionMethodsReturnTypedErrorsWithoutInventedFacts()throws Exception{
  var h=helper();var f=h.fixture();var p=h.prepared(f,true);try{
   assertTrue(h.dispatch(f,p));start(f,p);var c=context(f,p);var r=ref(f,p);var snapshot=value(f.provider().getSnapshot(c,r),ExecutionSnapshot.class);var cmd=ExecutionSpecs.command("unsupported",h.binding(p),Map.of(),snapshot.snapshotRevision());
   assertEquals("UNSUPPORTED",error(f.provider().submitInput(c,new InputRequest(r,cmd,"input","message","supplement","No arbitrary continuation",List.of(),null))));
   assertEquals("UNSUPPORTED",error(f.provider().preparePermissionResume(c,new ResumePreparation(r,cmd,"checkpoint","request","a".repeat(64),"proof"))));
   assertEquals("UNSUPPORTED",error(f.provider().respondPermission(c,new PermissionResponseDeny(r,cmd,"request","a".repeat(64),0,"deny","deny","approval","Not supported"))));
   assertEquals(snapshot.snapshotRevision(),value(f.provider().getSnapshot(c,r),ExecutionSnapshot.class).snapshotRevision());assertTrue(f.db().rows("SELECT * FROM fixture_commands WHERE tenant_id=? AND binding_id=? AND method<>'start'",id(TENANT),id(h.binding(p))).isEmpty());
  }finally{clean(h,f,p);}
 }
 @Test void delegatedIdentityTenantBindingAndCursorCannotBeSubstituted()throws Exception{
  var h=helper();var f=h.fixture();var p=h.prepared(f,true);try{
   assertTrue(h.dispatch(f,p));start(f,p);var c=context(f,p);var r=ref(f,p);
   var wrongSubject=new CallContext(c.contractVersion(),c.correlationId(),c.tenantId(),new CallContextSubjectContext(f.reviewer().id().toString(),c.subjectContext().authorizationRef()),c.deadline());assertEquals("FORBIDDEN",error(f.provider().getSnapshot(wrongSubject,r)));
   var wrongTenant=new CallContext(c.contractVersion(),c.correlationId(),UUID.randomUUID().toString(),c.subjectContext(),c.deadline());assertEquals("FORBIDDEN",error(f.provider().lookupSubmission(wrongTenant,submission(f,p))));
   assertEquals("FORBIDDEN",error(f.provider().getSnapshot(c,new BindingRef(r.bindingId(),UUID.randomUUID().toString(),r.providerId()))));
   String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString((UUID.randomUUID()+":0").getBytes(StandardCharsets.UTF_8));assertEquals("CURSOR_EXPIRED",error(f.provider().readEvents(c,new EventReadRequest(r,cursor,100))));
   var sub=submission(f,p);var changed=new SubmissionRef(sub.bindingId(),sub.nodeRunId(),sub.providerId(),sub.tenantId(),sub.idempotencyKey(),"f".repeat(64));assertEquals("IDEMPOTENCY_CONFLICT",error(f.provider().lookupSubmission(c,changed)));
  }finally{clean(h,f,p);}
 }
 @Test void explicitCancellationIsIdempotentAndDifferentContentConflicts()throws Exception{
  var h=helper();var f=h.fixture();var p=h.prepared(f,true);try{
   assertTrue(h.dispatch(f,p));start(f,p);var c=context(f,p);var r=ref(f,p);long revision=value(f.provider().getSnapshot(c,r),ExecutionSnapshot.class).snapshotRevision();String reason="Stop the approved synthetic operation";var command=ExecutionSpecs.command("cancel",h.binding(p),Map.of("binding",r,"reason",reason),revision);var request=new CancelRequest(r,command,reason);
   var first=value(f.provider().cancel(c,request),CancelReceipt.class);var replay=value(f.provider().cancel(c,request),CancelReceipt.class);assertInstanceOf(CancelReceiptConfirmed.class,first);assertEquals(first,replay);assertEquals("cancelled",((CancelReceiptConfirmed)first).snapshot().outcome());
   String different="A different cancellation reason";var altered=new CancelRequest(r,ExecutionSpecs.command("cancel",h.binding(p),Map.of("binding",r,"reason",different),revision),different);assertEquals("IDEMPOTENCY_CONFLICT",error(f.provider().cancel(c,altered)));assertEquals(0,number(f.provider().lookup(h.binding(p)).get("execution_attempts")));
  }finally{clean(h,f,p);}
 }
 @Test void anotherBlobScopeCannotAcceptObserveCancelOrImportThisBinding()throws Exception{
  var h=helper();var f=h.fixture();var p=h.prepared(f,true);try{
   assertTrue(h.dispatch(f,p));var otherMaterials=new io.aitest.workbench.material.Materials(f.db(),f.scope(),blobs.resolve("other-scope").toString());var other=new FixtureProvider(f.db(),f.preparation(),otherMaterials,SyntheticCheckpoints.disabled());
   var c=context(f,p);var r=ref(f,p);var sub=submission(f,p);var spec=ProviderJson.read(binding(f,p).get("execution_spec_json").toString(),ExecutionSpec.class);
   assertEquals("FORBIDDEN",error(other.validateBinding(c,spec)));assertEquals("FORBIDDEN",error(other.start(c,new StartRequest(sub,ExecutionSpecs.command("start",h.binding(p),Map.of("submission",sub,"spec",spec),0),spec))));
   start(f,p);assertEquals("FORBIDDEN",error(other.lookupSubmission(c,sub)));assertEquals("FORBIDDEN",error(other.getSnapshot(c,r)));assertEquals("FORBIDDEN",error(other.readEvents(c,new EventReadRequest(r,null,100))));
   var cancel=new CancelRequest(r,ExecutionSpecs.command("cancel",h.binding(p),Map.of("binding",r,"reason","wrong scope"),1),"wrong scope");assertEquals("FORBIDDEN",error(other.cancel(c,cancel)));assertEquals("accepted",f.provider().lookup(h.binding(p)).get("status"));
   assertEquals("succeeded",f.provider().execute(h.binding(p)).get("status"));var row=f.provider().lookup(h.binding(p));var artifact=f.db().object(row.get("result_json")).get("artifactId").toString();assertEquals("FORBIDDEN",error(other.getArtifact(c,new ArtifactRequest(r,artifact,"import"))));
   assertEquals(1,number(row.get("execution_attempts")));try(var files=Files.list(blobs.resolve("other-scope"))){assertEquals(0,files.count());}
  }finally{clean(h,f,p);}
 }
}
