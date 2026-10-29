package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.CommandScope;
import io.aitest.workbench.infrastructure.CommandScope.Kind;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.Scope.Actor;
import io.aitest.workbench.material.Materials;
import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ExecutionProvider;
import io.aitest.contract.ProviderJson;
import static io.aitest.contract.ProviderTypes.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.aitest.workbench.infrastructure.Store.*;

@Service
@org.springframework.context.annotation.Profile("synthetic")
@org.springframework.context.annotation.DependsOn("syntheticBoundary")
public class ExecutionWorker {
    private final Store db;private final Scope scope;private final PreparationService preparation;
    private final ExecutionProvider provider;private final Materials materials;
    private final SyntheticCheckpoints checkpoints;private final QuotaLedger quota;
    static final String CLAIM_SQL="SELECT * FROM outbox FORCE INDEX(PRIMARY) WHERE tenant_id=? AND state IN ('queued','claimed','sending') AND (lease_until IS NULL OR lease_until<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED";
    private static final class ClaimLost extends RuntimeException {}
    private final UUID worker=UUID.randomUUID();private final AtomicBoolean processing=new AtomicBoolean();
    public ExecutionWorker(Store db,Scope scope,PreparationService preparation,ExecutionProvider provider,Materials materials,SyntheticCheckpoints checkpoints,QuotaLedger quota){this.db=db;this.scope=scope;this.preparation=preparation;this.provider=provider;this.materials=materials;this.checkpoints=checkpoints;this.quota=quota;}
    @Scheduled(fixedDelayString="${WB_WORKER_DELAY:200}")
    public void tick(){
        if(!processing.compareAndSet(false,true))return;
        try{
            for(int i=0;i<4;i++){
                var claim=db.queueTransaction(()->{
                    Instant claimedAt=db.now();
                    var rows=db.rows(CLAIM_SQL,id(TENANT),LocalDateTime.ofInstant(claimedAt,ZoneOffset.UTC));
                    if(rows.isEmpty())return Map.<String,Object>of();var row=rows.getFirst();
                    db.update("UPDATE outbox SET state=CASE WHEN state='queued' THEN 'claimed' ELSE state END,worker_id=?,lease_until=?,attempt=attempt+1 WHERE tenant_id=? AND id=?",id(worker),LocalDateTime.ofInstant(claimedAt.plusSeconds(30),ZoneOffset.UTC),id(TENANT),row.get("id"));return row;
                });
                if(claim.isEmpty())break;
                UUID binding=UUID.fromString(uuid(claim.get("binding_id")));
                try{process(binding,UUID.fromString(uuid(claim.get("id"))),claim.get("state").equals("sending"));}
                catch(PortFailure unavailable){unknown(binding,unavailable.code);}
                catch(Problem problem){
                    if(problem.code.startsWith("QUOTA_")){unknown(binding,problem.code);continue;}
                    try{closeAndObserve(binding,problem.code);}catch(PortFailure unavailable){unknown(binding,unavailable.code);}
                }
                catch(Exception unexpected){
                    org.slf4j.LoggerFactory.getLogger(ExecutionWorker.class).error("Synthetic worker failure for binding {}",binding,unexpected);
                    unknown(binding,"WORKER_ERROR");
                }
            }
        }finally{processing.set(false);}
    }
    private static final class PortFailure extends RuntimeException { final String code; PortFailure(String code){this.code=code;} }
    private <T>T port(CompletionStage<Result<T>> call){
        Result<T> result;
        try{result=call.toCompletableFuture().get(30,TimeUnit.SECONDS);}catch(Exception failure){throw new PortFailure("PROVIDER_UNAVAILABLE");}
        if(result instanceof ResultTrue<T> ok)return ok.value();
        var error=((ResultFalse<T>)result).error();throw new PortFailure(error instanceof ProviderErrorNotAccepted e?e.code():((ProviderErrorNotSentOrAcceptedOrUnknown)error).code());
    }
    private CallContext context(Map<String,Object> binding){var c=db.one("SELECT subject_id FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),binding.get("conversation_id"));return ExecutionSpecs.context(binding,UUID.fromString(uuid(c.get("subject_id"))),db.now());}
    private void process(UUID binding,UUID outbox,boolean sending){
        var b=db.one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));
        if(b.get("closed_at")!=null){db.update("UPDATE outbox SET state='done' WHERE tenant_id=? AND id=?",id(TENANT),id(outbox));return;}
        StartReceipt receipt;
        if(sending||!b.get("state").equals("prepared")){
            var lookup=port(provider.lookupSubmission(context(b),ExecutionSpecs.submission(b)));
            if(lookup instanceof SubmissionLookupFound found)receipt=found.receipt();
            else {if(Boolean.TRUE.equals(b.get("stopped")))closeAndObserve(binding,"STOPPED_BEFORE_ACCEPTANCE");else unknown(binding,"ACCEPTANCE_UNKNOWN");return;}
        }else{
            if(!prepareDispatch(binding,outbox))return;checkpoints.hit("dispatch_committed",binding);
            b=db.one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));
            var submission=ExecutionSpecs.submission(b);var spec=new ExecutionSpecs(db,materials).spec(binding);
            receipt=port(provider.start(context(b),new StartRequest(submission,ExecutionSpecs.command("start",binding,Map.of("submission",submission,"spec",spec),0),spec)));
        }
        accepted(binding,ProviderObservation.receipt(receipt));checkpoints.hit("platform_receipt_committed",binding);
        if(Boolean.TRUE.equals(db.one("SELECT stopped FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding)).get("stopped"))){closeAndObserve(binding,null);return;}
        observe(binding,receipt,null);
    }
    private void closeAndObserve(UUID binding,String reason){
        var b=db.one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));var submission=ExecutionSpecs.submission(b);
        var closure=port(provider.closeSubmission(context(b),new CloseSubmissionRequest(submission,ExecutionSpecs.command("closeSubmission",binding,Map.of("submission",submission),0))));
        if(closure instanceof SubmissionClosureNotAcceptedFinal closed){
            // Only the permanent submission fence proves nonacceptance; not_found never does.
            String event=closed.proofRef().substring(closed.proofRef().lastIndexOf(':')+1);
            finish(binding,new ProviderObservation("closed",event,submission.specHash(),null,null,null,null),reason);return;
        }
        if(!(closure instanceof SubmissionClosureFound found)){unknown(binding,"ACCEPTANCE_UNKNOWN");return;}
        var receipt=found.receipt();accepted(binding,ProviderObservation.receipt(receipt));
        var ref=ExecutionSpecs.binding(b);var snapshot=port(provider.getSnapshot(context(b),ref));
        if(!snapshot.state().equals("terminal")){
            var body=Map.of("binding",ref,"reason","platform requested stop");
            port(provider.cancel(context(b),new CancelRequest(ref,ExecutionSpecs.command("cancel",binding,body,snapshot.snapshotRevision()),"platform requested stop")));
        }
        observe(binding,receipt,reason);
    }
    private void observe(UUID binding,StartReceipt receipt,String reason){
        var b=db.one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));var ref=ExecutionSpecs.binding(b);var snapshot=port(provider.getSnapshot(context(b),ref));
        if(!ref.equals(snapshot.binding())||snapshot.run()==null||!snapshot.run().equals(receipt.run()))throw new PortFailure("INTEGRITY_MISMATCH");
        if(!snapshot.state().equals("terminal")||snapshot.executionActive()!=KnownBoolean.FALSE||snapshot.effectStatus().equals("unknown")){
            unknown(binding,snapshot.state().equals("accepted")?"WAITING_TEST_CHECKPOINT":"EXECUTION_OUTCOME_UNKNOWN");return;
        }
        String outcome=Objects.requireNonNull(snapshot.outcome());ArtifactDescriptor csv=null;ProviderObservation.CsvSummary summary=null;String code=null;
        String cursor=null;long sequence=0;var artifacts=new ArrayList<ArtifactDescriptor>();
        do{
            var page=port(provider.readEvents(context(b),new EventReadRequest(ref,cursor,100)));
            for(var event:page.events()){
                if(!event.bindingId().equals(binding.toString())||!event.providerId().equals(ref.providerId())||!event.providerRunId().equals(receipt.run().providerRunId())||event.sequence()!=++sequence)throw new PortFailure("EVENT_GAP");
                if(event.payload() instanceof EventPayloadArtifactReady ready)artifacts.add(ready.artifact());
                if(event.payload() instanceof EventPayloadDiagnostic diagnostic)code=diagnostic.code();
            }
            if(page.hasMore()&&Objects.equals(cursor,page.cursor()))throw new PortFailure("EVENT_GAP");cursor=page.hasMore()?page.cursor():null;
        }while(cursor!=null);
        if(sequence!=snapshot.eventWatermark())throw new PortFailure("EVENT_GAP");
        if(outcome.equals("succeeded")){
            try{
                if(artifacts.size()!=2)throw new Problem(409,"INTEGRITY_MISMATCH");
                for(var eventArtifact:artifacts){
                    var descriptor=port(provider.getArtifact(context(b),new ArtifactRequest(ref,eventArtifact.artifactId(),"import")));
                    if(!descriptor.equals(eventArtifact)||!descriptor.bindingId().equals(binding.toString())||!descriptor.sourceRef().equals("fixture-artifact:"+descriptor.artifactId())||!descriptor.authorizationRef().equals("fixture-binding:"+binding))throw new Problem(409,"INTEGRITY_MISMATCH");
                    if(descriptor.mimeType().equals("text/csv")){if(csv!=null)throw new Problem(409,"INTEGRITY_MISMATCH");csv=descriptor;}
                    else if(descriptor.mimeType().equals("application/json")){
                        if(summary!=null)throw new Problem(409,"INTEGRITY_MISMATCH");byte[] bytes=materials.read(UUID.fromString(descriptor.artifactId()));
                        if(bytes.length!=descriptor.bytes()||!CanonicalJson.hashBytes(bytes).equals(descriptor.sha256()))throw new Problem(409,"INTEGRITY_MISMATCH");
                        summary=ProviderJson.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),ProviderObservation.CsvSummary.class);
                    }else throw new Problem(409,"INTEGRITY_MISMATCH");
                }
                if(csv==null||summary==null)throw new Problem(409,"INTEGRITY_MISMATCH");
                var approval=db.one("SELECT operation_hash FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
                if(!CanonicalJson.hash(summary.plan().toString()).equals(hex(approval.get("operation_hash"))))throw new Problem(409,"INTEGRITY_MISMATCH");
            }catch(PortFailure unavailable){if(!Set.of("FORBIDDEN","ARTIFACT_UNAVAILABLE","INTEGRITY_MISMATCH").contains(unavailable.code))throw unavailable;code=unavailable.code;csv=null;summary=null;}
            catch(RuntimeException invalid){code=invalid instanceof Problem p?p.code:"INTEGRITY_MISMATCH";csv=null;summary=null;}
            checkpoints.hit("provider_result_committed",binding);
        }
        finish(binding,new ProviderObservation(outcome,receipt.receiptId(),receipt.specHash(),Instant.parse(receipt.acceptedAt()),csv,summary,code),reason);
    }
    private boolean prepareDispatch(UUID binding,UUID outbox){
        try{return db.transaction(()->{
            var queue=db.one("SELECT worker_id,lease_until FROM outbox WHERE tenant_id=? AND id=?",id(TENANT),id(outbox));
            if(!Arrays.equals((byte[])queue.get("worker_id"),id(worker))||queue.get("lease_until")==null||!db.now().isBefore(Store.instant(queue.get("lease_until"))))return false;
            var b=db.one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));
            var p=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
            if(Boolean.TRUE.equals(b.get("stopped"))||!p.get("state").equals("approved"))throw new Problem(409,"PLAN_STALE_OR_STOPPED");
            preparation.validate(p);var plan=db.object(p.get("plan_json"));
            var c=db.one("SELECT subject_id,environment_id FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),b.get("conversation_id"));
            UUID owner=UUID.fromString(uuid(c.get("subject_id")));
            var env=db.one("SELECT published_json FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),c.get("environment_id"));
            long environmentLimit=number(db.object(env.get("published_json")).getOrDefault("turnLimit",100));
            if(!quota.reserve(binding,owner,plan.get("model").toString(),environmentLimit)){
                if(db.update("UPDATE outbox SET state='queued',worker_id=NULL,lease_until=? WHERE tenant_id=? AND id=? AND worker_id=? AND lease_until=? AND lease_until>?",LocalDateTime.ofInstant(db.now().plusSeconds(1),ZoneOffset.UTC),id(TENANT),id(outbox),id(worker),queue.get("lease_until"),LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC))!=1)throw new ClaimLost();return false;
            }
            UUID ticket=UUID.randomUUID();
            var connection=new ConnectionRef(ExecutionSpecs.PROVIDER,uuid(c.get("environment_id")),number(plan.get("environmentRevision")));
            var capabilities=port(provider.inspectCapabilities(context(b),connection));
            Instant notBefore=db.now(),notAfter=Instant.parse(plan.get("validUntil").toString());
            var factory=new ExecutionSpecs(db,materials);var spec=factory.build(binding,ticket,notBefore,notAfter,capabilities);
            db.update("INSERT INTO admission_tickets(tenant_id,id,binding_id,content_hash,not_before,not_after) VALUES(?,?,?,?,?,?)",id(TENANT),id(ticket),id(binding),hex(ExecutionSpecs.contentHash(spec)),LocalDateTime.ofInstant(notBefore,ZoneOffset.UTC),LocalDateTime.ofInstant(notAfter,ZoneOffset.UTC));
            db.update("UPDATE node_runs SET state='dispatching' WHERE tenant_id=? AND id=?",id(TENANT),b.get("node_run_id"));
            db.update("UPDATE execution_bindings SET state='submitting',spec_hash=?,execution_spec_json=?,quota_snapshot_json=?,capability_snapshot_json=? WHERE tenant_id=? AND id=?",hex(ExecutionSpecs.hash(spec)),ProviderJson.write(spec),db.json(factory.quotaSnapshot(binding)),ProviderJson.write(capabilities),id(TENANT),id(binding));
            if(db.update("UPDATE outbox SET state='sending' WHERE tenant_id=? AND id=? AND worker_id=? AND lease_until=? AND lease_until>?",id(TENANT),id(outbox),id(worker),queue.get("lease_until"),LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC))!=1)throw new ClaimLost();
            db.event(UUID.fromString(uuid(b.get("conversation_id"))),"execution.dispatching",Map.of("bindingId",binding.toString()));
            db.audit(owner,"execution.dispatch",binding,Map.of("ticketId",ticket.toString(),"outboxId",outbox.toString(),"reservedAccounts",List.of("total",QuotaLedger.modelAccount(plan.get("model").toString()))));return true;
        });}catch(ClaimLost lost){return false;}
    }
    private void accepted(UUID binding,ProviderObservation receipt){
        if(receipt.acceptedAt()==null)return;
        db.transaction(()->{
            var b=db.one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));
            if(!hex(b.get("spec_hash")).equals(receipt.specHash()))throw new Problem(409,"INTEGRITY_MISMATCH");
            if(b.get("accepted_at")!=null)return null;
            db.update("UPDATE execution_bindings SET state='accepted',accepted_at=? WHERE tenant_id=? AND id=?",LocalDateTime.ofInstant(receipt.acceptedAt(),ZoneOffset.UTC),id(TENANT),id(binding));
            quota.consume(binding);
            db.event(UUID.fromString(uuid(b.get("conversation_id"))),"execution.accepted",Map.of("bindingId",binding.toString(),"backend","persistent_synthetic_fixture"));return null;
        });
    }
    private void finish(UUID binding,ProviderObservation fact,String failure){
        boolean success=fact.status().equals("succeeded")&&fact.artifact()!=null&&fact.summary()!=null;Map<String,Object> result=db.object(db.json(fact.result()));
        if(failure==null)failure=fact.failureCode();
        if(success){
            try {
                byte[] bytes=materials.read(UUID.fromString(result.get("artifactId").toString()));
                if(!CanonicalJson.hashBytes(bytes).equals(result.get("sha256"))){success=false;failure="INTEGRITY_MISMATCH";}
            } catch(Problem p){success=false;failure=p.code;}
        }
        final boolean calculated=success;final String reason=failure;
        db.transaction(()->{
            var b=db.one("SELECT * FROM execution_bindings WHERE tenant_id=? AND id=? FOR UPDATE",id(TENANT),id(binding));
            UUID conversation=UUID.fromString(uuid(b.get("conversation_id")));
            String event="terminal:"+fact.receiptId();String payloadHash=CanonicalJson.hash(db.json(Map.of("status",fact.status(),"result",result)));
            var old=db.rows("SELECT payload_hash FROM provider_events WHERE tenant_id=? AND binding_id=? AND event_id=?",id(TENANT),id(binding),key(event));
            if(!old.isEmpty()){
                if(!hex(old.getFirst().get("payload_hash")).equals(payloadHash))throw new Problem(409,"PROVIDER_EVENT_CONFLICT");
                return null;
            }
            if(b.get("closed_at")!=null)return null;
            if(fact.acceptedAt()!=null)accepted(binding,fact);
            db.update("INSERT INTO provider_events VALUES(?,?,?,?)",id(TENANT),id(binding),key(event),hex(payloadHash));
            boolean deliver=calculated;String invalid=reason;
            var approval=db.one("SELECT * FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
            var current=db.one("SELECT input_revision FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));
            boolean superseded=number(current.get("input_revision"))!=number(approval.get("input_revision"));
            if(deliver)try{preparation.validateResource(approval);}catch(Problem p){deliver=false;invalid=p.code;}
            boolean cancelled=fact.status().equals("cancelled")||(fact.status().equals("closed")&&Boolean.TRUE.equals(b.get("stopped")));
            String nodeState=calculated?"succeeded":cancelled?"cancelled":"failed";
            db.update("UPDATE execution_bindings SET state=?,closed_at=?,effect_status='verified' WHERE tenant_id=? AND id=?",fact.status().equals("closed")?"rejected":"terminal",LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC),id(TENANT),id(binding));
            db.update("UPDATE node_runs SET state=? WHERE tenant_id=? AND id=?",nodeState,id(TENANT),b.get("node_run_id"));
            quota.close(binding);
            if(deliver){
                UUID artifact=UUID.fromString(result.get("artifactId").toString());
                db.update("INSERT INTO artifacts VALUES(?,?,?,?,?,?,?,?,?,?)",id(TENANT),id(artifact),id(conversation),id(binding),hex(result.get("sha256").toString()),number(result.get("bytes")),superseded?"superseded":"ready",1,number(approval.get("input_revision")),db.json(result.get("summary")));
                if(!superseded)db.update("UPDATE conversations SET state='awaiting_verification',revision=revision+1 WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));
                db.event(conversation,superseded?"result.superseded":"result.ready",Map.of("artifactId",artifact.toString(),"resultRevision",1));
            }else{
                if(!superseded)db.update("UPDATE conversations SET state='handoff',revision=revision+1 WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));
                db.event(conversation,"execution.closed",Map.of("outcome",nodeState,"reason",Objects.toString(invalid,fact.status().toString())));
            }
            db.update("UPDATE outbox SET state='done',lease_until=NULL WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
            return null;
        });
    }
    private void unknown(UUID binding,String reason){
        db.transaction(()->{
            var b=db.one("SELECT state,conversation_id,closed_at,accepted_at FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));
            if(b.get("closed_at")!=null)return null;
            String state=reason.startsWith("QUOTA_")?"reconciling":reason.equals("WAITING_TEST_CHECKPOINT")?"accepted":b.get("accepted_at")==null?"acceptance_unknown":"reconciling";
            if(!b.get("state").equals(state))db.event(UUID.fromString(uuid(b.get("conversation_id"))),"execution.reconciling",Map.of("bindingId",binding.toString(),"reason",reason));
            db.update("UPDATE execution_bindings SET state=? WHERE tenant_id=? AND id=?",state,id(TENANT),id(binding));
            db.update("UPDATE outbox SET state='sending',lease_until=? WHERE tenant_id=? AND binding_id=?",LocalDateTime.ofInstant(db.now().plusSeconds(1),ZoneOffset.UTC),id(TENANT),id(binding));return null;
        });
    }
    public Map<String,Object> stop(Actor a,UUID conversation,String key) {
        return db.command(a.id(),"execution.stop",key,Map.of("conversationId",conversation.toString()),CommandScope.of(Kind.CONVERSATION,conversation),()->scope.authorize(a,CommandScope.of(Kind.CONVERSATION,conversation)),()->{
            scope.ownConversation(a,conversation);
            var bindings=db.rows("SELECT id,node_run_id FROM execution_bindings WHERE tenant_id=? AND conversation_id=? AND closed_at IS NULL",id(TENANT),id(conversation));
            if(bindings.isEmpty())return Map.of("state","already_terminal");
            for(var b:bindings){
                UUID binding=UUID.fromString(uuid(b.get("id")));
                db.update("UPDATE approvals SET state='superseded',revision=revision+1 WHERE tenant_id=? AND binding_id=? AND state IN ('pending','approved')",id(TENANT),id(binding));
                db.update("UPDATE execution_bindings SET stopped=TRUE,state='stopping' WHERE tenant_id=? AND id=?",id(TENANT),id(binding));
                db.update("UPDATE node_runs SET state='stopping' WHERE tenant_id=? AND id=?",id(TENANT),b.get("node_run_id"));
                var outbox=db.rows("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
                if(outbox.isEmpty())db.update("INSERT INTO outbox(tenant_id,id,binding_id,state) VALUES(?,?,?,?)",id(TENANT),id(UUID.randomUUID()),id(binding),"sending");
                else db.update("UPDATE outbox SET lease_until=NULL WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
            }
            db.event(conversation,"stop.requested",Map.of());return Map.of("state","stopping");
        });
    }
}
