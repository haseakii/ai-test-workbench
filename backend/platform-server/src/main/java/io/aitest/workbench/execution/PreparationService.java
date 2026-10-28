package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.CommandScope;
import io.aitest.workbench.infrastructure.CommandScope.Kind;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.Scope.Actor;
import io.aitest.workbench.material.Materials;
import io.aitest.contract.CanonicalJson;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;

@Service
public class PreparationService {
    private final Store db;private final Scope scope;private final Materials materials;
    private final SyntheticCheckpoints checkpoints;
    private final ContextManifests contexts;
    public PreparationService(Store db,Scope scope,Materials materials,SyntheticCheckpoints checkpoints){this.db=db;this.scope=scope;this.materials=materials;this.checkpoints=checkpoints;this.contexts=new ContextManifests(db,scope,materials);}
    public Map<String,Object> understand(Actor actor,UUID conversation,long expectedInputRevision,String key){
        var result=db.command(actor.id(),"understanding.prepare",key,Map.of("conversationId",conversation.toString(),"expectedInputRevision",expectedInputRevision),CommandScope.of(Kind.CONVERSATION,conversation),()->{
            scope.authorize(actor,CommandScope.of(Kind.CONVERSATION,conversation));if(number(scope.ownConversation(actor,conversation).get("input_revision"))!=expectedInputRevision)throw new Problem(409,"INPUT_REVISION_CONFLICT");
        },()->contexts.understand(actor,conversation,Map.of()));
        // Equal-key receipts still depend on the current source/configuration authorization.
        contexts.read(actor,UUID.fromString(result.get("contextManifestRef").toString()));return result;
    }
    public Map<String,Object> context(Actor actor,UUID reference){return contexts.read(actor,reference);}
    public record Request(String detailAttachmentId,String reportAttachmentId,String from,String to,String timeZone,String currency,String status,long expectedInputRevision,int validForSeconds){}
    public Map<String,Object> prepare(Actor a,UUID conversation,Request r,String key) {
        Instant from,to;
        try{from=io.aitest.contract.ContractChecks.microsecond(Instant.parse(r.from()));to=io.aitest.contract.ContractChecks.microsecond(Instant.parse(r.to()));ZoneId.of(r.timeZone());}catch(Exception e){throw new Problem(422,"INVALID_TIME_SCOPE");}
        if(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(366))>0||r.validForSeconds()<1||r.validForSeconds()>3600||!r.currency().matches("[A-Z]{3}")||!Set.of("completed","pending","cancelled").contains(r.status())||r.detailAttachmentId().equals(r.reportAttachmentId()))throw new Problem(422,"INVALID_PLAN_SCOPE");
        return db.command(a.id(),"execution.prepare",key,Map.of("conversationId",conversation.toString(),"request",r),CommandScope.of(Kind.CONVERSATION,conversation),()->scope.authorize(a,CommandScope.of(Kind.CONVERSATION,conversation)),()->{
            var c=scope.ownConversation(a,conversation);
            if(number(c.get("input_revision"))!=r.expectedInputRevision()||r.expectedInputRevision()==0)throw new Problem(409,"INPUT_REVISION_CONFLICT");
            if(!db.rows("SELECT id FROM execution_bindings WHERE tenant_id=? AND conversation_id=? AND closed_at IS NULL",id(TENANT),id(conversation)).isEmpty())throw new Problem(409,"EXECUTION_OWNER_OCCUPIED");
            UUID env=UUID.fromString(uuid(c.get("environment_id")));var config=scope.environment(a,env);var envContent=db.object(config.get("published_json"));
            for(var dependency:db.object(envContent.get("dependencyRevisions")).entrySet()){
                var d=db.one("SELECT revision,disabled FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),id(dependency.getKey()));
                if(number(d.get("revision"))!=number(dependency.getValue())||Boolean.TRUE.equals(d.get("disabled")))throw new Problem(409,"DEPENDENCY_REVISION_CONFLICT");
            }
            long authorization=scope.actor(a.alias()).revision();
            UUID node=UUID.randomUUID(),binding=UUID.randomUUID(),approval=UUID.randomUUID();Instant until=db.now().plusSeconds(r.validForSeconds());
            var plan=new LinkedHashMap<String,Object>();
            plan.put("operation","reconcile_csv_readonly_v1");plan.put("environmentId",env.toString());plan.put("environmentRevision",number(config.get("revision")));plan.put("inputRevision",r.expectedInputRevision());plan.put("authorizationRevision",authorization);
            plan.put("detailAttachmentId",r.detailAttachmentId());plan.put("reportAttachmentId",r.reportAttachmentId());
            for(String field:List.of("detailAttachmentId","reportAttachmentId")){
                var source=db.one("SELECT sha256 FROM attachments WHERE tenant_id=? AND conversation_id=? AND id=? AND state='ready' AND source_active=TRUE",id(TENANT),id(conversation),id(plan.get(field).toString()));
                plan.put(field+"Hash",hex(source.get("sha256")));
            }
            var time=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
            plan.put("from",time.format(from));plan.put("to",time.format(to));plan.put("timeZone",r.timeZone());plan.put("currency",r.currency());plan.put("status",r.status());plan.put("output","difference.csv");plan.put("validUntil",time.format(until));plan.put("dependencyRevisions",envContent.get("dependencyRevisions"));plan.put("model","fixture-v1");plan.put("purpose","investigation");
            var answers=Map.<String,Object>of("detailAttachmentId",r.detailAttachmentId(),"reportAttachmentId",r.reportAttachmentId(),"from",time.format(from),"to",time.format(to),"timeZone",r.timeZone(),"currency",r.currency(),"status",r.status());
            var understanding=contexts.understand(a,conversation,answers);var manifest=contexts.freeze(a,conversation,"investigation",answers);
            plan.put("understandingRef",understanding.get("id"));plan.put("understandingHash",understanding.get("outputHash"));plan.put("contextManifestRef",uuid(manifest.get("id")));plan.put("contextManifestHash",hex(manifest.get("manifest_hash")));
            String operationHash=CanonicalJson.hash(db.json(plan));
            String contentHash=CanonicalJson.hash(db.json(Map.of("bindingId",binding.toString(),"nodeRunId",node.toString(),"purpose","investigation","plan",plan)));
            db.update("INSERT INTO node_runs VALUES(?,?,?,?)",id(TENANT),id(node),id(conversation),"blocked");
            db.update("INSERT INTO execution_bindings(tenant_id,id,node_run_id,conversation_id,state,spec_hash) VALUES(?,?,?,?,?,?)",id(TENANT),id(binding),id(node),id(conversation),"prepared",hex(contentHash));
            db.update("INSERT INTO approvals(tenant_id,id,conversation_id,binding_id,input_revision,environment_revision,authorization_revision,operation_hash,plan_json,valid_until) VALUES(?,?,?,?,?,?,?,?,?,?)",id(TENANT),id(approval),id(conversation),id(binding),r.expectedInputRevision(),number(config.get("revision")),authorization,hex(operationHash),db.json(plan),LocalDateTime.ofInstant(until,ZoneOffset.UTC));
            db.update("UPDATE approvals SET context_manifest_id=? WHERE tenant_id=? AND id=?",manifest.get("id"),id(TENANT),id(approval));
            db.update("UPDATE conversations SET state='open',revision=revision+1 WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));
            db.event(conversation,"investigation.awaiting_approval",Map.of("approvalId",approval.toString(),"operationHash",operationHash));db.audit(a.id(),"execution.prepare",binding,Map.of("operationHash",operationHash));
            return Map.of("approvalId",approval.toString(),"bindingId",binding.toString(),"operationHash",operationHash,"plan",plan,"revision",1);
        });
    }
    public Map<String,Object> decide(Actor a,UUID approval,boolean approve,String operationHash,long revision,String reason,String key) {
        if(reason==null||reason.length()<5||reason.length()>1000)throw new Problem(422,"REASON_REQUIRED");
        return db.command(a.id(),"approval.decide",key,Map.of("approvalId",approval.toString(),"approve",approve,"operationHash",operationHash,"expectedRevision",revision,"reason",reason),CommandScope.of(Kind.APPROVAL,approval),()->scope.authorize(a,CommandScope.of(Kind.APPROVAL,approval)),()->{
            scope.reviewer(a);var p=db.one("SELECT * FROM approvals WHERE tenant_id=? AND id=? FOR UPDATE",id(TENANT),id(approval));
            var plan=db.object(p.get("plan_json"));scope.environment(a,UUID.fromString(plan.get("environmentId").toString()));
            if(number(p.get("revision"))!=revision||!"pending".equals(p.get("state"))||!hex(p.get("operation_hash")).equals(operationHash)||!db.now().isBefore(Instant.parse(plan.get("validUntil").toString())))throw new Problem(409,"PLAN_STALE");
            if(!CanonicalJson.hash(db.json(plan)).equals(operationHash))throw new Problem(409,"INTEGRITY_MISMATCH");
            UUID binding=UUID.fromString(uuid(p.get("binding_id"))),conversation=UUID.fromString(uuid(p.get("conversation_id")));
            if(approve)validate(p);
            var authority=Map.of("kind","human","subjectId",a.id().toString(),"qualificationRef","membership:"+a.id()+":"+scope.actor(a.alias()).revision());
            db.update("UPDATE approvals SET state=?,revision=revision+1,authority_json=? WHERE tenant_id=? AND id=? AND revision=?",approve?"approved":"denied",db.json(authority),id(TENANT),id(approval),revision);
            if(approve){
                db.update("UPDATE node_runs n JOIN execution_bindings b ON b.node_run_id=n.id AND b.tenant_id=n.tenant_id SET n.state='ready' WHERE b.tenant_id=? AND b.id=?",id(TENANT),id(binding));
                db.update("INSERT INTO outbox(tenant_id,id,binding_id,state) VALUES(?,?,?,?)",id(TENANT),id(UUID.randomUUID()),id(binding),"queued");
            } else {
                // No external dispatch existed; permanently fence this synthetic submission key.
                db.update("INSERT INTO fixture_submissions(tenant_id,binding_id,spec_hash,status,receipt_id) SELECT tenant_id,id,spec_hash,'closed',? FROM execution_bindings WHERE tenant_id=? AND id=?",id(UUID.randomUUID()),id(TENANT),id(binding));
                db.update("UPDATE execution_bindings SET state='rejected',closed_at=? WHERE tenant_id=? AND id=?",LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC),id(TENANT),id(binding));
                db.update("UPDATE node_runs n JOIN execution_bindings b ON b.node_run_id=n.id AND b.tenant_id=n.tenant_id SET n.state='failed' WHERE b.tenant_id=? AND b.id=?",id(TENANT),id(binding));
            }
            db.event(conversation,"approval.decided",Map.of("approvalId",approval.toString(),"approved",approve));db.audit(a.id(),"approval.decide",approval,Map.of("operationHash",operationHash,"approve",approve,"authority",authority,"reason",reason));
            if(approve)checkpoints.hit("approval_before_commit",binding);
            return Map.of("state",approve?"approved":"denied","revision",revision+1);
        });
    }
    public void validate(Map<String,Object> approval) {
        validateGuards(approval,true);
        var plan=db.object(approval.get("plan_json"));
        if(!db.now().isBefore(Instant.parse(plan.get("validUntil").toString())))throw new Problem(409,"APPROVAL_EXPIRED");
    }
    public void validateResource(Map<String,Object> approval) {
        validateGuards(approval,false);
    }
    public void validateContextForExecution(Map<String,Object> approval){
        var plan=db.object(approval.get("plan_json"));if(!plan.containsKey("contextManifestRef"))throw new Problem(409,"CONTEXT_UNAVAILABLE");
        contexts.verifyForExecution(UUID.fromString(plan.get("contextManifestRef").toString()),plan.get("contextManifestHash").toString(),UUID.fromString(uuid(approval.get("conversation_id"))));
    }
    private void validateGuards(Map<String,Object> approval,boolean firstAcceptance) {
        var plan=db.object(approval.get("plan_json"));UUID conversation=UUID.fromString(uuid(approval.get("conversation_id")));
        var c=db.one("SELECT * FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));
        var owner=db.one("SELECT m.revision,m.state FROM memberships m WHERE m.tenant_id=? AND m.subject_id=?",id(TENANT),c.get("subject_id"));
        if(!"active".equals(owner.get("state"))||(firstAcceptance&&number(owner.get("revision"))!=number(approval.get("authorization_revision"))))throw new Problem(409,"AUTHORIZATION_CHANGED");
        if(db.rows("SELECT revision FROM grants WHERE tenant_id=? AND subject_id=? AND environment_id=? AND active=TRUE",id(TENANT),c.get("subject_id"),c.get("environment_id")).isEmpty())throw new Problem(403,"GRANT_REVOKED");
        var env=db.one("SELECT revision,disabled FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),c.get("environment_id"));
        if(Boolean.TRUE.equals(env.get("disabled"))||(firstAcceptance&&number(env.get("revision"))!=number(approval.get("environment_revision"))))throw new Problem(409,"CONFIGURATION_CHANGED");
        for(var dependency:db.object(plan.get("dependencyRevisions")).entrySet()){
            var dep=db.one("SELECT revision,disabled FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),id(dependency.getKey()));
            if(Boolean.TRUE.equals(dep.get("disabled"))||(firstAcceptance&&number(dep.get("revision"))!=number(dependency.getValue())))throw new Problem(409,"DEPENDENCY_REVISION_CONFLICT");
        }
        if(firstAcceptance&&number(c.get("input_revision"))!=number(approval.get("input_revision")))throw new Problem(409,"INPUT_CHANGED");
        materials.assertSources(conversation,plan);
        if(!plan.containsKey("contextManifestRef"))throw new Problem(409,"CONTEXT_UNAVAILABLE");
        contexts.verify(UUID.fromString(plan.get("contextManifestRef").toString()),plan.get("contextManifestHash").toString(),conversation,firstAcceptance);
    }
    public List<Map<String,Object>> approvals(Actor a) {
        scope.reviewer(a);
        return db.rows("SELECT p.*,c.title,c.environment_id FROM approvals p JOIN conversations c ON c.tenant_id=p.tenant_id AND c.id=p.conversation_id JOIN grants g ON g.tenant_id=c.tenant_id AND g.environment_id=c.environment_id WHERE p.tenant_id=? AND g.subject_id=? AND g.active=TRUE ORDER BY p.id",id(TENANT),id(a.id())).stream().map(r->Map.<String,Object>of("id",uuid(r.get("id")),"conversationId",uuid(r.get("conversation_id")),"title",r.get("title"),"state",r.get("state"),"revision",number(r.get("revision")),"operationHash",hex(r.get("operation_hash")),"plan",db.object(r.get("plan_json")))).toList();
    }
}
