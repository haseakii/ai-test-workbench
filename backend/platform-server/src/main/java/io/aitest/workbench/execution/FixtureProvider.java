package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.material.Materials;
import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ExecutionProvider;
import io.aitest.contract.ProviderJson;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static io.aitest.contract.ProviderTypes.*;
import static io.aitest.workbench.infrastructure.Store.*;

/** Persistent local CSV test backend. This is not a real Agent or ACP implementation. */
@Component
public class FixtureProvider implements ExecutionProvider {
    public static final String PROVIDER_ID="persistent-synthetic";
    private final Store db;private final PreparationService preparation;private final Materials materials;
    private final SyntheticCheckpoints checkpoints;private final AtomicBoolean advancing=new AtomicBoolean();
    private final long ownerPid=ProcessHandle.current().pid();
    private final String ownerStarted=ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("");
    private final String bootHash;
    public FixtureProvider(Store db,PreparationService preparation,Materials materials,SyntheticCheckpoints checkpoints){
        this.db=db;this.preparation=preparation;this.materials=materials;this.checkpoints=checkpoints;
        String boot=null;
        try{Path p=Path.of("/proc/sys/kernel/random/boot_id");if(Files.isReadable(p))boot=CanonicalJson.hashBytes(Files.readAllBytes(p));}catch(Exception ignored){}
        if(boot==null){String host=Objects.toString(System.getenv("COMPUTERNAME"),Objects.toString(System.getenv("HOSTNAME"),"localhost"));boot=CanonicalJson.hashBytes((host+":"+ownerStarted).getBytes(StandardCharsets.UTF_8));}
        bootHash=boot;
    }
    public static class LostReceipt extends RuntimeException {}
    private String time(){return ContextManifests.time(db.now());}
    private Map<String,Object> binding(UUID binding){return db.one("SELECT b.*,c.subject_id,c.environment_id FROM execution_bindings b JOIN conversations c ON c.tenant_id=b.tenant_id AND c.id=b.conversation_id WHERE b.tenant_id=? AND b.id=?",id(TENANT),id(binding));}
    private BindingRef ref(UUID binding){var b=binding(binding);return new BindingRef(binding.toString(),uuid(b.get("node_run_id")),PROVIDER_ID);}
    private ProviderRunRef run(Map<String,Object> row){return new ProviderRunRef(uuid(row.get("receipt_id")),null,null,null);}
    private StartReceipt receipt(UUID binding,Map<String,Object> row,boolean reused){return new StartReceipt("accepted",ref(binding),run(row),ContextManifests.time(instant(row.get("accepted_at"))),uuid(row.get("receipt_id")),hex(row.get("spec_hash")),reused);}
    private String cursor(UUID binding,long sequence){return Base64.getUrlEncoder().withoutPadding().encodeToString((binding+":"+sequence).getBytes(StandardCharsets.UTF_8));}
    private void assertScope(UUID binding,Map<String,Object> row){
        if(row.get("execution_spec_json")!=null&&!ProviderJson.read(row.get("execution_spec_json").toString(),ExecutionSpec.class).workspaceAuthorizationRef().equals("fixture-workspace:"+materials.scope()))throw new Problem(403,"FORBIDDEN");
        var journal=lookup(binding);if(journal.get("executor_scope")!=null&&!hex(journal.get("executor_scope")).equals(materials.scope()))throw new Problem(403,"FORBIDDEN");
    }
    private Map<String,Object> authorize(CallContext context,UUID binding){
        if(!TENANT.toString().equals(context.tenantId()))throw new Problem(403,"FORBIDDEN");
        var row=binding(binding);String subject=uuid(row.get("subject_id"));
        if(!subject.equals(context.subjectContext().subjectId())||!context.subjectContext().authorizationRef().equals("platform-delegation:"+binding+":"+subject))throw new Problem(403,"FORBIDDEN");assertScope(binding,row);return row;
    }
    private UUID authorize(CallContext context,BindingRef binding){UUID value=UUID.fromString(binding.bindingId());var row=authorize(context,value);if(!PROVIDER_ID.equals(binding.providerId())||!uuid(row.get("node_run_id")).equals(binding.nodeRunId()))throw new Problem(403,"FORBIDDEN");return value;}
    private UUID authorize(CallContext context,SubmissionRef submission){
        UUID value=authorize(context,new BindingRef(submission.bindingId(),submission.nodeRunId(),submission.providerId()));var row=binding(value);
        if(!context.tenantId().equals(submission.tenantId())||!submission.idempotencyKey().equals(value.toString()))throw new Problem(403,"FORBIDDEN");
        if(!submission.specHash().equals(hex(row.get("spec_hash"))))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return value;
    }
    private <T> CompletionStage<Result<T>> complete(CallContext context,String phase,Supplier<T> body){
        try{if(!db.now().isBefore(Instant.parse(context.deadline())))throw new Problem(408,"DEADLINE_EXCEEDED");return CompletableFuture.completedFuture(new ResultTrue<>(true,body.get()));}
        catch(LostReceipt lost){return CompletableFuture.completedFuture(new ResultFalse<>(false,new ProviderErrorNotSentOrAcceptedOrUnknown("PROVIDER_UNAVAILABLE","Synthetic acceptance receipt deliberately lost",context.correlationId(),"submission","reconcile",null,"unknown",null)));}
        catch(Problem problem){String code=switch(problem.code){case "FORBIDDEN","NOT_FOUND","GRANT_REVOKED"->"FORBIDDEN";case "IDEMPOTENCY_CONFLICT","SUBMISSION_CLOSED","INTEGRITY_MISMATCH","APPROVAL_EXPIRED","DEADLINE_EXCEEDED","REVISION_CONFLICT","CURSOR_EXPIRED","ARTIFACT_UNAVAILABLE","UNSUPPORTED"->problem.code;default->"PRECONDITION_CHANGED";};return CompletableFuture.completedFuture(new ResultFalse<>(false,new ProviderErrorNotSentOrAcceptedOrUnknown(code,problem.code,context.correlationId(),phase,"reconcile",null,"unknown",null)));}
        catch(IllegalArgumentException invalid){return CompletableFuture.completedFuture(new ResultFalse<>(false,new ProviderErrorNotSentOrAcceptedOrUnknown("VALIDATION_FAILED","Invalid synthetic Provider request",context.correlationId(),phase,"correct_input",null,"unknown",null)));}
        catch(RuntimeException unavailable){return CompletableFuture.completedFuture(new ResultFalse<>(false,new ProviderErrorNotSentOrAcceptedOrUnknown("PROVIDER_UNAVAILABLE","Synthetic Provider journal unavailable",context.correlationId(),phase,"reconcile",null,"unknown",null)));}
    }
    private <T> CompletionStage<Result<T>> unsupported(CallContext context,String phase,BindingRef request){return complete(context,phase,()->db.transaction(()->{authorize(context,request);throw new Problem(422,"UNSUPPORTED");}));}
    private <T> T command(UUID binding,String method,CommandRef command,Object body,Class<T> type,Supplier<T> action){
        String hash=CanonicalJson.hash(db.json(Map.of("method",method,"body",body)));if(!hash.equals(command.requestHash()))throw new Problem(409,"INTEGRITY_MISMATCH");
        var old=db.rows("SELECT request_hash,response_json FROM fixture_commands WHERE tenant_id=? AND binding_id=? AND method=? AND idempotency_key=?",id(TENANT),id(binding),key(method),key(command.idempotencyKey()));
        if(!old.isEmpty()){if(!hash.equals(hex(old.getFirst().get("request_hash"))))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return ProviderJson.read(old.getFirst().get("response_json").toString(),type);}
        // Closing an admission key is monotonic: after acceptance this returns only the
        // original receipt and never changes execution. It must also work after a lost reply.
        long revision=number(lookup(binding).getOrDefault("snapshot_revision",0));if(!method.equals("closeSubmission")&&command.expectedRevision()!=revision)throw new Problem(409,"REVISION_CONFLICT");T response=action.get();
        db.update("INSERT INTO fixture_commands VALUES(?,?,?,?,?,?,?)",id(TENANT),id(binding),key(method),key(command.idempotencyKey()),id(command.commandId()),hex(hash),ProviderJson.write(response));return response;
    }
    private void append(UUID binding,EventPayload payload){
        var row=lookup(binding);long sequence=number(row.get("event_sequence"))+1;db.update("UPDATE fixture_submissions SET event_sequence=?,snapshot_revision=snapshot_revision+1 WHERE tenant_id=? AND binding_id=?",sequence,id(TENANT),id(binding));
        UUID event=UUID.randomUUID();String now=time();var value=new ExecutionEvent(PROVIDER_ID,uuid(row.get("receipt_id")),event.toString(),binding.toString(),sequence,sequence,now,now,1,"business",payload);db.update("INSERT INTO fixture_events VALUES(?,?,?,?,?)",id(TENANT),id(binding),sequence,id(event),ProviderJson.write(value));
    }
    private void updated(UUID binding){
        var row=lookup(binding);long sequence=number(row.get("event_sequence"))+1;db.update("UPDATE fixture_submissions SET event_sequence=?,snapshot_revision=snapshot_revision+1 WHERE tenant_id=? AND binding_id=?",sequence,id(TENANT),id(binding));
        UUID event=UUID.randomUUID();String now=time();var value=new ExecutionEvent(PROVIDER_ID,uuid(row.get("receipt_id")),event.toString(),binding.toString(),sequence,sequence,now,now,1,"business",new EventPayloadExecutionUpdated("execution.updated",snapshot(binding)));db.update("INSERT INTO fixture_events VALUES(?,?,?,?,?)",id(TENANT),id(binding),sequence,id(event),ProviderJson.write(value));
    }
    private ExecutionSnapshot snapshot(UUID binding){
        var row=lookup(binding);String status=row.get("status").toString();boolean terminal=Set.of("succeeded","failed","cancelled").contains(status),closed=status.equals("closed");boolean uncertain=status.equals("outcome_unknown")||status.equals("executing")&&!Boolean.TRUE.equals(ownerAlive(row));
        String state=closed?"rejected":terminal?"terminal":status.equals("accepted")?"accepted":uncertain?"reconciling":"active";KnownBoolean active=terminal||closed?KnownBoolean.FALSE:uncertain?KnownBoolean.UNKNOWN:status.equals("accepted")?KnownBoolean.FALSE:KnownBoolean.TRUE;
        String effect=terminal?"verified":closed||status.equals("accepted")?"none":"unknown";List<String> effects=terminal?List.of("fixture-terminal:"+uuid(row.get("receipt_id"))):List.of();
        return new ExecutionSnapshot(ref(binding),row.get("accepted_at")==null?null:run(row),state,number(row.get("snapshot_revision")),number(row.get("event_sequence")),cursor(binding,number(row.get("event_sequence"))),time(),active,terminal?status:null,effect,effects,null,List.of(),List.of(),List.of(),null);
    }
    @Override public CompletionStage<Result<CapabilitySnapshot>> inspectCapabilities(CallContext context,ConnectionRef connection){return complete(context,"validation",()->db.transaction(()->{
        String[] pieces=context.subjectContext().authorizationRef().split(":");if(pieces.length!=3)throw new Problem(403,"FORBIDDEN");var row=authorize(context,UUID.fromString(pieces[1]));var config=db.one("SELECT revision,published_json FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),row.get("environment_id"));
        if(!connection.providerId().equals(PROVIDER_ID)||!connection.connectionId().equals(uuid(row.get("environment_id")))||connection.connectionRevision()!=number(config.get("revision")))throw new Problem(409,"PRECONDITION_CHANGED");
        var supported=Set.of("binary_artifact","durable_events","idempotent_start","submission_fence","hard_call_limit","usage_reporting","admission_guard");var capabilities=new ArrayList<CapabilitySnapshotCapabilitiesItem>();
        for(String name:List.of("image_input","resume_checkpoint","permission_bridge","binary_artifact","durable_events","idempotent_start","submission_fence","hard_call_limit","usage_reporting","hard_token_limit","admission_guard"))capabilities.add(new CapabilitySnapshotCapabilitiesItem(name,supported.contains(name)?"adapter_supported":"unsupported",supported.contains(name)?"fixture-code:deterministic-csv-v1":null));
        return new CapabilitySnapshot(connection,"persistent-fixture-typed-v1",null,"deterministic-csv-v1","local-java-port",CanonicalJson.hash(db.json(db.object(config.get("published_json")))),time(),ContextManifests.time(db.now().plusSeconds(60)),capabilities,List.of());
    }));}
    private ExecutionSpec spec(UUID binding){var row=binding(binding);if(row.get("execution_spec_json")==null)throw new Problem(409,"PRECONDITION_CHANGED");return ProviderJson.read(row.get("execution_spec_json").toString(),ExecutionSpec.class);}
    private void validate(UUID binding,ExecutionSpec specification){
        var b=binding(binding);String encoded=ProviderJson.write(specification);String hash=CanonicalJson.hash(encoded);
        if(!hash.equals(hex(b.get("spec_hash")))||!hash.equals(CanonicalJson.hash(ProviderJson.write(spec(binding))))||!specification.bindingId().equals(binding.toString())||!specification.nodeRunId().equals(uuid(b.get("node_run_id")))||!specification.tenantId().equals(TENANT.toString())||!specification.conversationId().equals(uuid(b.get("conversation_id")))||!specification.connection().providerId().equals(PROVIDER_ID)||!specification.connection().connectionId().equals(uuid(b.get("environment_id")))||!specification.executionIdentityRef().equals("platform-delegation:"+binding+":"+uuid(b.get("subject_id")))||!specification.workspaceAuthorizationRef().equals("fixture-workspace:"+materials.scope()))throw new Problem(409,"INTEGRITY_MISMATCH");
        var content=new LinkedHashMap<>(db.object(encoded));content.remove("admission");String contentHash=CanonicalJson.hash(db.json(content));var ticket=db.one("SELECT * FROM admission_tickets WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
        if(!contentHash.equals(hex(ticket.get("content_hash")))||!contentHash.equals(specification.admission().executionContentHash())||!uuid(ticket.get("id")).equals(specification.admission().ticketId())||!binding.toString().equals(specification.admission().bindingId())||!ContextManifests.time(instant(ticket.get("not_before"))).equals(specification.admission().notBefore())||!ContextManifests.time(instant(ticket.get("not_after"))).equals(specification.admission().notAfter()))throw new Problem(409,"INTEGRITY_MISMATCH");
    }
    @Override public CompletionStage<Result<ValidationReport>> validateBinding(CallContext context,ExecutionSpec specification){return complete(context,"validation",()->db.transaction(()->{UUID binding=UUID.fromString(specification.bindingId());authorize(context,binding);validate(binding,specification);var p=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));preparation.validate(p);return new ValidationReport(CanonicalJson.hash(ProviderJson.write(specification)),time(),specification.admission().notAfter(),true,List.of(),List.of("Synthetic fixed CSV execution only; no real model or backend"));}));}
    @Override public CompletionStage<Result<StartReceipt>> start(CallContext context,StartRequest request){return complete(context,"submission",()->{
        boolean[] first={false};UUID binding=UUID.fromString(request.submission().bindingId());var result=db.transaction(()->{authorize(context,request.submission());validate(binding,request.spec());return command(binding,"start",request.command(),Map.of("submission",request.submission(),"spec",request.spec()),StartReceipt.class,()->{var accepted=accept(binding);first[0]=Boolean.TRUE.equals(accepted.get("firstAcceptance"));return receipt(binding,accepted,!first[0]);});});
        if(first[0])checkpoints.hit("provider_acceptance_committed",binding);if(first[0]&&mode(binding).equals("drop_receipt"))throw new LostReceipt();return first[0]?result:new StartReceipt(result.status(),result.binding(),result.run(),result.acceptedAt(),result.receiptId(),result.specHash(),true);
    });}
    @Override public CompletionStage<Result<SubmissionLookup>> lookupSubmission(CallContext context,SubmissionRef request){return complete(context,"observation",()->db.transaction(()->{UUID binding=authorize(context,request);var row=lookup(binding);return row.get("accepted_at")!=null?new SubmissionLookupFound("found",receipt(binding,row,true)):new SubmissionLookupNotFound("not_found",time(),false);}));}
    @Override public CompletionStage<Result<SubmissionClosure>> closeSubmission(CallContext context,CloseSubmissionRequest request){return complete(context,"submission",()->db.transaction(()->{UUID binding=authorize(context,request.submission());return command(binding,"closeSubmission",request.command(),Map.of("submission",request.submission()),SubmissionClosure.class,()->{
        var row=lookup(binding);if(row.get("accepted_at")!=null)return new SubmissionClosureFound("found",receipt(binding,row,true));if(row.get("status").equals("not_found")){tombstone(binding);row=lookup(binding);}Object closed=row.get("closed_at");if(closed==null)closed=binding(binding).get("closed_at");if(closed==null)return new SubmissionClosureUnknown("unknown","Legacy tombstone has no durable closure timestamp");
        return new SubmissionClosureNotAcceptedFinal("not_accepted_final","fixture-fence:"+uuid(row.get("receipt_id")),ContextManifests.time(instant(closed)),"fixture-nonacceptance:"+uuid(row.get("receipt_id")));
    });}));}
    @Override public CompletionStage<Result<ExecutionSnapshot>> getSnapshot(CallContext context,BindingRef request){return complete(context,"observation",()->db.transaction(()->{UUID binding=authorize(context,request);if(lookup(binding).get("status").equals("not_found"))throw new Problem(404,"NOT_FOUND");return snapshot(binding);}));}
    @Override public CompletionStage<Result<EventPage>> readEvents(CallContext context,EventReadRequest request){return complete(context,"observation",()->db.transaction(()->{
        UUID binding=authorize(context,request.binding());if(request.limit()<1||request.limit()>100)throw new Problem(422,"PRECONDITION_CHANGED");long after=0;
        if(request.cursor()!=null){try{String[] parts=new String(Base64.getUrlDecoder().decode(request.cursor()),StandardCharsets.UTF_8).split(":");if(parts.length!=2||!parts[0].equals(binding.toString()))throw new IllegalArgumentException();after=io.aitest.contract.ContractChecks.safeInteger(Long.parseLong(parts[1]));}catch(Exception invalid){throw new Problem(410,"CURSOR_EXPIRED");}}
        var row=lookup(binding);if(row.get("status").equals("not_found"))throw new Problem(404,"NOT_FOUND");long watermark=number(row.get("event_sequence"));if(after>watermark)throw new Problem(410,"CURSOR_EXPIRED");var values=db.rows("SELECT event_json FROM fixture_events WHERE tenant_id=? AND binding_id=? AND sequence>? ORDER BY sequence LIMIT ?",id(TENANT),id(binding),after,request.limit()).stream().map(r->ProviderJson.read(r.get("event_json").toString(),ExecutionEvent.class)).toList();long last=values.isEmpty()?after:values.getLast().sequence();return new EventPage(values,cursor(binding,last),last<watermark,1,number(row.get("snapshot_revision")));
    }));}
    @Override public CompletionStage<Result<InputReceipt>> submitInput(CallContext context,InputRequest request){return unsupported(context,"execution",request.binding());}
    @Override public CompletionStage<Result<ResumeReceipt>> preparePermissionResume(CallContext context,ResumePreparation request){return unsupported(context,"permission",request.binding());}
    @Override public CompletionStage<Result<PermissionReceipt>> respondPermission(CallContext context,PermissionResponse request){return unsupported(context,"permission",request instanceof PermissionResponseAllow allow?allow.binding():((PermissionResponseDeny)request).binding());}
    @Override public CompletionStage<Result<CancelReceipt>> cancel(CallContext context,CancelRequest request){return complete(context,"execution",()->db.transaction(()->{UUID binding=authorize(context,request.binding());return command(binding,"cancel",request.command(),Map.of("binding",request.binding(),"reason",request.reason()),CancelReceipt.class,()->{
        cancelLocal(binding);var row=lookup(binding);if(Set.of("cancelled","succeeded","failed").contains(row.get("status").toString()))return new CancelReceiptConfirmed("confirmed",snapshot(binding),"fixture-terminal:"+uuid(row.get("receipt_id")));return new CancelReceiptUnknown("unknown",request.command().commandId(),"Original execution has no authoritative terminal fact");
    });}));}
    @Override public CompletionStage<Result<ArtifactDescriptor>> getArtifact(CallContext context,ArtifactRequest request){return complete(context,"artifact",()->db.transaction(()->{UUID binding=authorize(context,request.binding());var p=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));preparation.validateResource(p);var row=db.one("SELECT descriptor_json FROM fixture_artifacts WHERE tenant_id=? AND binding_id=? AND artifact_id=?",id(TENANT),id(binding),id(request.artifactId()));return ProviderJson.read(row.get("descriptor_json").toString(),ArtifactDescriptor.class);}));}
    // Package-local choreography hooks retained for transaction tests, never used by the Worker.
    Map<String,Object> lookup(UUID binding){var rows=db.rows("SELECT * FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));return rows.isEmpty()?Map.of("status","not_found"):rows.getFirst();}
    private Map<String,Object> accept(UUID binding){
        var b=binding(binding);assertScope(binding,b);var prior=lookup(binding);if(prior.get("status").equals("closed"))throw new Problem(409,"SUBMISSION_CLOSED");if(!prior.get("status").equals("not_found")){if(!hex(prior.get("spec_hash")).equals(hex(b.get("spec_hash"))))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return prior;}
        var p=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));if(!p.get("state").equals("approved")||Boolean.TRUE.equals(b.get("stopped")))throw new Problem(409,"ADMISSION_BLOCKED");preparation.validate(p);
        var ticket=db.one("SELECT * FROM admission_tickets WHERE tenant_id=? AND binding_id=? FOR UPDATE",id(TENANT),id(binding));if(!"ready".equals(ticket.get("state"))||ticket.get("not_before")==null||db.now().isBefore(instant(ticket.get("not_before")))||!db.now().isBefore(instant(ticket.get("not_after"))))throw new Problem(409,"ADMISSION_EXPIRED");
        if(b.get("execution_spec_json")!=null)validate(binding,spec(binding));else{var plan=db.object(p.get("plan_json"));String expected=CanonicalJson.hash(db.json(Map.of("bindingId",binding.toString(),"nodeRunId",uuid(b.get("node_run_id")),"purpose","investigation","plan",plan)));if(!hex(ticket.get("content_hash")).equals(expected))throw new Problem(409,"INTEGRITY_MISMATCH");}
        var accepted=LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC);db.update("UPDATE admission_tickets SET state='consumed',consumed_at=? WHERE tenant_id=? AND id=?",accepted,id(TENANT),ticket.get("id"));db.update("INSERT INTO fixture_submissions(tenant_id,binding_id,spec_hash,status,accepted_at,receipt_id,executor_scope) VALUES(?,?,?,?,?,?,?)",id(TENANT),id(binding),b.get("spec_hash"),"accepted",accepted,id(UUID.randomUUID()),hex(materials.scope()));append(binding,new EventPayloadExecutionAccepted("execution.accepted",receipt(binding,lookup(binding),false)));var result=new LinkedHashMap<>(lookup(binding));result.put("firstAcceptance",true);return result;
    }
    Map<String,Object> start(UUID binding){var result=db.transaction(()->accept(binding));if(Boolean.TRUE.equals(result.get("firstAcceptance")))checkpoints.hit("provider_acceptance_committed",binding);if(Boolean.TRUE.equals(result.get("firstAcceptance"))&&mode(binding).equals("drop_receipt"))throw new LostReceipt();return result;}
    private void tombstone(UUID binding){var b=binding(binding);db.update("INSERT INTO fixture_submissions(tenant_id,binding_id,spec_hash,status,receipt_id,executor_scope,execution_phase,closed_at) VALUES(?,?,?,?,?,?,?,?)",id(TENANT),id(binding),b.get("spec_hash"),"closed",id(UUID.randomUUID()),hex(materials.scope()),"terminal",LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC));append(binding,new EventPayloadDiagnostic("diagnostic","SUBMISSION_CLOSED","Submission key permanently fenced before acceptance"));}
    private void terminal(UUID binding,String status,Object result){db.update("UPDATE fixture_submissions SET status=?,execution_phase='terminal',result_json=?,closed_at=? WHERE tenant_id=? AND binding_id=?",status,result==null?null:db.json(result),LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC),id(TENANT),id(binding));if(status.equals("failed")&&result!=null)append(binding,new EventPayloadDiagnostic("diagnostic",Objects.toString(db.object(result).get("code"),"FIXTURE_FAILED"),"Fixed CSV computation failed"));updated(binding);}
    /** null means identity cannot prove either liveness or death, never permission to cancel. */
    private Boolean ownerAlive(Map<String,Object> row){
        if(row.get("owner_pid")==null||row.get("owner_started_at")==null||row.get("owner_boot_hash")==null||!hex(row.get("owner_boot_hash")).equals(bootHash))return null;
        long pid=((Number)row.get("owner_pid")).longValue();var handle=ProcessHandle.of(pid);if(handle.isEmpty()||!handle.get().isAlive())return false;
        try{var proc=Files.readString(Path.of("/proc",Long.toString(pid),"stat"));int end=proc.lastIndexOf(')');if(end>=0&&proc.substring(end+2).startsWith("Z"))return false;}catch(java.io.IOException ignored){return null;}
        return handle.get().info().startInstant().map(t->t.toString().equals(row.get("owner_started_at").toString())).orElse(null);
    }
    private void cancelLocal(UUID binding){
        assertScope(binding,binding(binding));var row=lookup(binding);String status=row.get("status").toString();if(status.equals("not_found")){tombstone(binding);return;}
        if(status.equals("accepted")||Set.of("executing","outcome_unknown").contains(status)&&row.get("execution_phase").equals("computing")&&Boolean.FALSE.equals(ownerAlive(row))){db.update("UPDATE fixture_submissions SET cancel_requested=TRUE,execution_fence=execution_fence+1 WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));terminal(binding,"cancelled",null);}
        else if(Set.of("executing","outcome_unknown").contains(status))db.update("UPDATE fixture_submissions SET cancel_requested=TRUE WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
    }
    Map<String,Object> close(UUID binding){return db.transaction(()->{cancelLocal(binding);return lookup(binding);});}
    Map<String,Object> execute(UUID binding){
        long[] fence={-1};boolean claimed=db.transaction(()->{var r=lookup(binding);if(!r.get("status").equals("accepted"))return false;var b=binding(binding);assertScope(binding,b);var p=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
            if(Boolean.TRUE.equals(b.get("stopped"))||!"approved".equals(p.get("state"))){terminal(binding,"cancelled",null);return false;}preparation.validateResource(p);preparation.validateContextForExecution(p);if(mode(binding).equals("hold_after_accept"))return false;fence[0]=number(r.get("execution_fence"));db.update("UPDATE fixture_submissions SET status='executing',execution_phase='computing',execution_attempts=execution_attempts+1,owner_pid=?,owner_started_at=?,owner_boot_hash=? WHERE tenant_id=? AND binding_id=? AND status='accepted'",ownerPid,ownerStarted,hex(bootHash),id(TENANT),id(binding));updated(binding);return true;});
        if(!claimed)return lookup(binding);var p=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));UUID conversation=UUID.fromString(uuid(p.get("conversation_id")));
        try{
            var calculated=materials.calculate(conversation,db.object(p.get("plan_json")));checkpoints.hit("provider_execution_started",binding);
            boolean writing=db.transaction(()->{var row=lookup(binding);if(!row.get("status").equals("executing")||number(row.get("execution_fence"))!=fence[0])return false;var current=binding(binding);var approval=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));if(Boolean.TRUE.equals(row.get("cancel_requested"))||Boolean.TRUE.equals(current.get("stopped"))||!approval.get("state").equals("approved")){terminal(binding,"cancelled",null);return false;}preparation.validateResource(approval);preparation.validateContextForExecution(approval);db.update("UPDATE fixture_submissions SET execution_phase='writing' WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));return true;});
            if(!writing)return lookup(binding);UUID csv=UUID.randomUUID(),summary=UUID.randomUUID();byte[] summaryBytes=CanonicalJson.canonicalize(db.json(calculated.summary())).getBytes(StandardCharsets.UTF_8);materials.write(csv,calculated.csv());materials.write(summary,summaryBytes);String csvHash=CanonicalJson.hashBytes(calculated.csv()),summaryHash=CanonicalJson.hashBytes(summaryBytes);if(mode(binding).equals("hash_failure"))materials.write(csv,"corrupted-fixture-output".getBytes(StandardCharsets.UTF_8));
            var result=new LinkedHashMap<String,Object>();result.put("artifactId",csv.toString());result.put("sha256",csvHash);result.put("bytes",calculated.csv().length);result.put("summary",calculated.summary());result.put("summaryArtifactId",summary.toString());result.put("summarySha256",summaryHash);result.put("summaryBytes",summaryBytes.length);
            db.transaction(()->{var row=lookup(binding);if(!row.get("status").equals("executing")||number(row.get("execution_fence"))!=fence[0])throw new Problem(409,"RESULT_UNKNOWN");for(var artifact:List.of(new ArtifactDescriptor(csv.toString(),binding.toString(),"text/csv",calculated.csv().length,csvHash,"fixture-artifact:"+csv,"metadata_only","fixture-binding:"+binding,null),new ArtifactDescriptor(summary.toString(),binding.toString(),"application/json",summaryBytes.length,summaryHash,"fixture-artifact:"+summary,"metadata_only","fixture-binding:"+binding,null))){db.update("INSERT INTO fixture_artifacts VALUES(?,?,?,?)",id(TENANT),id(binding),id(artifact.artifactId()),ProviderJson.write(artifact));append(binding,new EventPayloadArtifactReady("artifact.ready",artifact));}append(binding,new EventPayloadUsageReported("usage.reported",new UsageFactActualOrEstimate(binding.toString(),"fixture-csv-model-calls","model_calls","cumulative","run_total",time(),"actual","0","calls",null)));terminal(binding,"succeeded",result);return null;});
        }catch(Problem problem){db.transaction(()->{var row=lookup(binding);if(row.get("status").equals("executing")){if(row.get("execution_phase").equals("computing"))terminal(binding,"failed",Map.of("code",problem.code));else{db.update("UPDATE fixture_submissions SET status='outcome_unknown' WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));append(binding,new EventPayloadDiagnostic("diagnostic",problem.code,"Output publication has no authoritative terminal fact"));updated(binding);}}return null;});}return lookup(binding);
    }
    /** Only the provider drives execution. Observations never calculate or replay a run. */
    @Scheduled(fixedDelayString="${WB_PROVIDER_DELAY:100}") public void tick(){
        if(!advancing.compareAndSet(false,true))return;try{for(var row:db.rows("SELECT binding_id,status FROM fixture_submissions WHERE tenant_id=? AND executor_scope=? AND (status IN ('accepted','executing') OR (status='outcome_unknown' AND cancel_requested=TRUE AND execution_phase='computing')) ORDER BY binding_id",id(TENANT),hex(materials.scope()))){UUID binding=UUID.fromString(uuid(row.get("binding_id")));if(!row.get("status").equals("accepted")){db.transaction(()->{var current=lookup(binding);if(Boolean.TRUE.equals(current.get("cancel_requested")))cancelLocal(binding);current=lookup(binding);if(current.get("status").equals("executing")&&Boolean.FALSE.equals(ownerAlive(current))){db.update("UPDATE fixture_submissions SET status='outcome_unknown' WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));append(binding,new EventPayloadDiagnostic("diagnostic","EXECUTION_OUTCOME_UNKNOWN","Original local process ended; execution will not be replayed"));updated(binding);}return null;});continue;}try{execute(binding);}catch(Problem denied){db.transaction(()->{var current=lookup(binding);if(current.get("status").equals("accepted"))terminal(binding,"failed",Map.of("code",denied.code));return null;});}}}finally{advancing.set(false);}
    }
    private String mode(UUID binding){var r=db.one("SELECT c.published_json FROM config_objects c JOIN conversations v ON v.tenant_id=c.tenant_id AND v.environment_id=c.id JOIN execution_bindings b ON b.tenant_id=v.tenant_id AND b.conversation_id=v.id WHERE b.tenant_id=? AND b.id=?",id(TENANT),id(binding));return Objects.toString(db.object(r.get("published_json")).get("testMode"),"normal");}
    public static Instant instant(Object value){return Store.instant(value);}
}
