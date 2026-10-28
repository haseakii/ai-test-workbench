package io.aitest.workbench.api;

import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.ContentReview;
import io.aitest.workbench.identity.Scope.Actor;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.CommandScope;
import io.aitest.workbench.infrastructure.CommandScope.Kind;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.configuration.ConfigurationService;
import io.aitest.workbench.configuration.ConfigurationService.Publication;
import io.aitest.workbench.conversation.ConversationService;
import io.aitest.workbench.execution.PreparationService;
import io.aitest.workbench.execution.ExecutionWorker;
import io.aitest.workbench.material.Materials;
import io.aitest.workbench.handover.HandoverService;
import io.aitest.contract.ContractChecks;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;
import static io.aitest.workbench.infrastructure.Store.*;

@RestController
@RequestMapping("/api")
public class WorkbenchApi {
    private final Store db;private final Scope scope;private final ConfigurationService config;private final ConversationService conversations;
    private final PreparationService preparation;private final ExecutionWorker worker;private final Materials materials;private final HandoverService handovers;
    private final ExecutorService streams=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore streamSlots=new Semaphore(4);private final ContentReview contentReview;
    public WorkbenchApi(Store db,Scope scope,ConfigurationService config,ConversationService conversations,PreparationService preparation,ExecutionWorker worker,Materials materials,ContentReview contentReview,HandoverService handovers){this.db=db;this.scope=scope;this.config=config;this.conversations=conversations;this.preparation=preparation;this.worker=worker;this.materials=materials;this.contentReview=contentReview;this.handovers=handovers;}
    private Actor actor(HttpServletRequest r){return (Actor)r.getAttribute("actor");}
    @GetMapping("/session")public Map<String,Object> session(HttpServletRequest r){var a=actor(r);return Map.of("subjectId",a.id().toString(),"alias",a.alias(),"role",a.role(),"state",a.state(),"authorizationRevision",a.revision(),"identityMode","synthetic_only");}
    @GetMapping("/workbench/options")public Object options(HttpServletRequest r){return config.options(actor(r));}
    public record RequestMembership(String purpose,String idempotencyKey){}
    @PostMapping("/membership-requests")public Object requestMembership(HttpServletRequest r,@RequestBody RequestMembership body){
        var a=actor(r);if(body.purpose()==null||body.purpose().length()<5||body.purpose().length()>1000)throw new Problem(422,"PURPOSE_REQUIRED");
        return db.command(a.id(),"membership.request",body.idempotencyKey(),Map.of("purpose",body.purpose()),CommandScope.subject(),()->scope.authorize(a,CommandScope.subject()),()->{db.audit(a.id(),"membership.request",a.id(),Map.of("purpose",body.purpose()));return Map.of("state",a.state(),"requestId",a.id().toString());});
    }
    @GetMapping("/admin/users")public Object members(HttpServletRequest r){return config.members(actor(r));}
    public record Grant(String alias,String environmentId,String role,String idempotencyKey){}
    @PostMapping("/admin/grants")public Object grant(HttpServletRequest r,@RequestBody Grant body){return config.grant(actor(r),body.alias(),UUID.fromString(body.environmentId()),body.role(),body.idempotencyKey());}
    @PostMapping("/admin/grants/revoke")public Object revoke(HttpServletRequest r,@RequestBody Grant body){return config.revoke(actor(r),body.alias(),UUID.fromString(body.environmentId()),body.idempotencyKey());}
    @GetMapping("/admin/configurations")public Object configurations(HttpServletRequest r){return config.list(actor(r));}
    public record NewConfiguration(String kind,String name,Map<String,Object> content,String idempotencyKey){}
    @PostMapping("/admin/configurations")public Object newConfiguration(HttpServletRequest r,@RequestBody NewConfiguration body){return config.create(actor(r),body.kind(),body.name(),body.content(),body.idempotencyKey());}
    public record Draft(long expectedRevision,String expectedDraftHash,Map<String,Object> content,String idempotencyKey){}
    @PatchMapping("/admin/configurations/{id}/draft")public Object draft(HttpServletRequest r,@PathVariable UUID id,@RequestBody Draft body){return config.draft(actor(r),id,ContractChecks.safeInteger(body.expectedRevision()),body.expectedDraftHash(),body.content(),body.idempotencyKey());}
    public record Publish(List<Publication> objects,String idempotencyKey){}
    @PostMapping("/admin/config-publications")public Object publish(HttpServletRequest r,@RequestBody Publish body){return config.publish(actor(r),body.objects(),body.idempotencyKey());}
    public record Key(String idempotencyKey){}
    @PostMapping("/admin/configurations/{id}/disable")public Object disable(HttpServletRequest r,@PathVariable UUID id,@RequestBody Key body){return config.disable(actor(r),id,body.idempotencyKey());}
    @GetMapping("/conversations")public Object list(HttpServletRequest r){return conversations.list(actor(r));}
    public record NewConversation(String environmentId,String idempotencyKey){}
    @PostMapping("/conversations")public Object create(HttpServletRequest r,@RequestBody NewConversation body){return conversations.create(actor(r),UUID.fromString(body.environmentId()),body.idempotencyKey());}
    @GetMapping("/conversations/{id}")public Object snapshot(HttpServletRequest r,@PathVariable UUID id){return conversations.snapshot(actor(r),id);}
    @GetMapping("/conversations/{id}/handover")public Object handover(HttpServletRequest r,@PathVariable UUID id){return handovers.export(actor(r),id);}
    public record Message(String clientMessageId,String text,long expectedRevision,String idempotencyKey){}
    @PostMapping("/conversations/{id}/messages")public Object message(HttpServletRequest r,@PathVariable UUID id,@RequestBody Message body){return conversations.message(actor(r),id,body.clientMessageId(),body.text(),ContractChecks.safeInteger(body.expectedRevision()),body.idempotencyKey());}
    public record Rename(String title,long expectedTitleRevision,String idempotencyKey){}
    @PatchMapping("/conversations/{id}/title")public Object title(HttpServletRequest r,@PathVariable UUID id,@RequestBody Rename body){return conversations.rename(actor(r),id,body.title(),ContractChecks.safeInteger(body.expectedTitleRevision()),body.idempotencyKey());}
    @PostMapping(value="/conversations/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(HttpServletRequest r,@PathVariable UUID id,@RequestParam MultipartFile file,@RequestParam String idempotencyKey){return materials.upload(actor(r),id,file,idempotencyKey);}
    public record Prepare(PreparationService.Request scope,String idempotencyKey){}
    public record Understanding(long expectedInputRevision,String idempotencyKey){}
    @PostMapping("/conversations/{id}/understanding")public Object understand(HttpServletRequest r,@PathVariable UUID id,@RequestBody Understanding body){return preparation.understand(actor(r),id,ContractChecks.safeInteger(body.expectedInputRevision()),body.idempotencyKey());}
    @GetMapping("/contexts/{id}")public Object context(HttpServletRequest r,@PathVariable UUID id){return preparation.context(actor(r),id);}
    @PostMapping("/conversations/{id}/prepare")public Object prepare(HttpServletRequest r,@PathVariable UUID id,@RequestBody Prepare body){return preparation.prepare(actor(r),id,body.scope(),body.idempotencyKey());}
    @GetMapping("/admin/approvals")public Object approvals(HttpServletRequest r){return preparation.approvals(actor(r));}
    public record Decision(boolean approve,String operationHash,long expectedRevision,String reason,String idempotencyKey){}
    @PostMapping("/approvals/{id}/decision")public Object decide(HttpServletRequest r,@PathVariable UUID id,@RequestBody Decision body){return preparation.decide(actor(r),id,body.approve(),body.operationHash(),ContractChecks.safeInteger(body.expectedRevision()),body.reason(),body.idempotencyKey());}
    @PostMapping("/conversations/{id}/stop")public Object stop(HttpServletRequest r,@PathVariable UUID id,@RequestBody Key body){return worker.stop(actor(r),id,body.idempotencyKey());}
    @GetMapping("/conversations/{id}/events")public Object events(HttpServletRequest r,@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="50")int limit){return conversations.events(actor(r),id,cursor,limit);}
    @GetMapping(value="/conversations/{id}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest r,@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestHeader(value="Last-Event-ID",required=false)String header){
        var a=actor(r);scope.ownConversation(a,id);if(cursor!=null&&header!=null&&!cursor.equals(header))throw new Problem(409,"CURSOR_CONFLICT");
        String initial=cursor==null?header:cursor;conversations.events(a,id,initial,1);
        if(!streamSlots.tryAcquire())throw new Problem(503,"STREAM_CAPACITY_RECONNECT");
        var emitter=new SseEmitter(20000L);var open=new java.util.concurrent.atomic.AtomicBoolean(true);
        emitter.onCompletion(()->open.set(false));emitter.onTimeout(()->open.set(false));emitter.onError(e->open.set(false));
        try{streams.submit(()->{String next=initial;try{for(int i=0;i<35&&open.get();i++){
            var page=conversations.events(a,id,next,100);next=page.get("cursor").toString();
            emitter.send(SseEmitter.event().name("platform").id(next).data(page));
            Thread.sleep(500);
        }emitter.complete();}catch(InterruptedException e){Thread.currentThread().interrupt();emitter.complete();}
        catch(Problem p){try{emitter.send(SseEmitter.event().name("authorization-lost").data(Map.of("code",p.code)));}catch(IOException ignored){}emitter.complete();}
        catch(Exception e){emitter.completeWithError(e);}finally{open.set(false);streamSlots.release();}});}
        catch(RejectedExecutionException e){streamSlots.release();throw new Problem(503,"STREAM_CAPACITY_RECONNECT");}
        return emitter;
    }
    @GetMapping("/artifacts/{id}/download")public ResponseEntity<byte[]> download(HttpServletRequest r,@PathVariable UUID id){
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"difference.csv\"").header(HttpHeaders.CACHE_CONTROL,"no-store").body(materials.download(actor(r),id));
    }
    public record Verify(long resultRevision,boolean resolved,String opinion,String idempotencyKey){}
    @PostMapping("/results/{id}/verification")public Object verify(HttpServletRequest r,@PathVariable UUID id,@RequestBody Verify body){return materials.verify(actor(r),id,ContractChecks.safeInteger(body.resultRevision()),body.resolved(),body.opinion(),body.idempotencyKey());}
    @PostMapping("/attachments/{id}/revoke")public Object revokeSource(HttpServletRequest r,@PathVariable UUID id,@RequestBody Key body){return materials.revoke(actor(r),id,body.idempotencyKey());}
    @GetMapping("/commands/{id}")public Object command(HttpServletRequest r,@PathVariable UUID id){
        var a=actor(r);return db.transaction(()->{var row=db.one("SELECT response_json,access_kind,access_id FROM commands WHERE tenant_id=? AND id=? AND subject_id=?",Store.id(TENANT),Store.id(id),Store.id(a.id()));scope.receipt(a,row);return db.object(row.get("response_json"));});
    }
    public record Lookup(String kind,String idempotencyKey){}
    @PostMapping("/command-lookups")public Object lookup(HttpServletRequest r,@RequestBody Lookup body){
        var a=actor(r);return db.transaction(()->{scope.actor(a.alias());var rows=db.rows("SELECT response_json,access_kind,access_id FROM commands WHERE tenant_id=? AND subject_id=? AND kind=? AND idempotency_key=?",id(TENANT),id(a.id()),Store.key(body.kind()),Store.key(body.idempotencyKey()));
        if(rows.isEmpty())return Map.of("status","not_found","authoritativeNonAcceptance",false);scope.receipt(a,rows.getFirst());return db.object(rows.getFirst().get("response_json"));});
    }
    @GetMapping("/quota")public Object quota(HttpServletRequest r){
        var a=actor(r);scope.active(a);return Map.of("accounts",db.rows("SELECT CONVERT(account_key USING utf8mb4) account,period_key period,turn_limit turnLimit,consumed,reserved FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(a.id())),"slots",db.rows("SELECT occupied,slot_limit slotLimit FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(a.id())));
    }
    public record Quota(String alias,long turnLimit,int slotLimit,long expectedAuthorizationRevision,String idempotencyKey){}
    @PostMapping("/admin/quotas")public Object quota(HttpServletRequest r,@RequestBody Quota body){
        var a=actor(r);scope.admin(a);ContractChecks.safeInteger(body.turnLimit());if(body.slotLimit()<0||body.slotLimit()>10)throw new Problem(422,"INVALID_SLOT_LIMIT");
        return db.command(a.id(),"quota.limit",body.idempotencyKey(),Map.of("alias",body.alias(),"turnLimit",body.turnLimit(),"slotLimit",body.slotLimit(),"expectedAuthorizationRevision",body.expectedAuthorizationRevision()),CommandScope.admin(),()->scope.authorize(a,CommandScope.admin()),()->{
            scope.admin(a);var target=scope.actor(body.alias());if(target.revision()!=body.expectedAuthorizationRevision())throw new Problem(409,"REVISION_CONFLICT");
            db.update("UPDATE quota_policies SET turn_limit=?,revision=revision+1 WHERE tenant_id=? AND subject_id=?",body.turnLimit(),id(TENANT),id(target.id()));
            db.update("UPDATE quota_accounts SET turn_limit=? WHERE tenant_id=? AND subject_id=? AND period_key=?",body.turnLimit(),id(TENANT),id(target.id()),db.quotaPeriod());
            db.update("UPDATE active_slots SET slot_limit=? WHERE tenant_id=? AND subject_id=?",body.slotLimit(),id(TENANT),id(target.id()));
            db.update("UPDATE memberships SET revision=revision+1 WHERE tenant_id=? AND subject_id=?",id(TENANT),id(target.id()));
            db.audit(a.id(),"quota.limit",target.id(),Map.of("turnLimit",body.turnLimit(),"slotLimit",body.slotLimit()));
            return Map.of("state","saved");
        });
    }
    @GetMapping("/admin/records")public Object records(HttpServletRequest r,@RequestParam(required=false)String query){
        return contentReview.records(actor(r),query);
    }
    public record ContentEligibility(String alias,String environmentId,boolean enabled,long expectedAuthorizationRevision,String reason,String idempotencyKey){}
    @PostMapping("/admin/content-reviewers")public Object eligibility(HttpServletRequest r,@RequestBody ContentEligibility body){return contentReview.eligibility(actor(r),body.alias(),UUID.fromString(body.environmentId()),body.enabled(),ContractChecks.safeInteger(body.expectedAuthorizationRevision()),body.reason(),body.idempotencyKey());}
    public record ContentLease(String conversationId,String purpose,int validForSeconds,String idempotencyKey){}
    @PostMapping("/admin/content-leases")public Object lease(HttpServletRequest r,@RequestBody ContentLease body){return contentReview.lease(actor(r),UUID.fromString(body.conversationId()),body.purpose(),body.validForSeconds(),body.idempotencyKey());}
    @GetMapping("/admin/records/{id}/content")public Object content(HttpServletRequest r,@PathVariable UUID id){return contentReview.read(actor(r),id);}
    @GetMapping("/admin/records/{id}/content-lease")public Object contentLease(HttpServletRequest r,@PathVariable UUID id){return contentReview.status(actor(r),id);}
    @GetMapping("/admin/executions")public Object executions(HttpServletRequest r,@RequestParam(required=false)String query){
        var a=actor(r);scope.admin(a);query=Objects.requireNonNullElse(query,"").strip();if(query.length()>160)throw new Problem(422,"INVALID_RECORD_QUERY");return db.rows("SELECT b.id,b.conversation_id,b.state,b.accepted_at,b.closed_at,c.title FROM execution_bindings b JOIN conversations c ON c.tenant_id=b.tenant_id AND c.id=b.conversation_id WHERE b.tenant_id=? AND LOCATE(?,c.title)>0 ORDER BY (SELECT e.occurred_at FROM platform_events e WHERE e.tenant_id=c.tenant_id AND e.conversation_id=c.id AND e.sequence=1) DESC,b.id LIMIT 100",id(TENANT),query).stream().map(row->{var v=new LinkedHashMap<String,Object>();v.put("bindingId",uuid(row.get("id")));v.put("conversationId",uuid(row.get("conversation_id")));v.put("title",row.get("title"));v.put("state",row.get("state"));v.put("acceptedAt",row.get("accepted_at"));v.put("closedAt",row.get("closed_at"));return v;}).toList();
    }
    @PreDestroy public void close(){streams.shutdownNow();}
}
