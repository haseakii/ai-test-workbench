package io.aitest.workbench.handover;

import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ProviderJson;
import io.aitest.contract.ProviderTypes.*;
import io.aitest.workbench.execution.ContextManifests;
import io.aitest.workbench.execution.ExecutionSpecs;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.Scope.Actor;
import io.aitest.workbench.infrastructure.CommandScope;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.material.Materials;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static io.aitest.workbench.handover.HandoverPackage.*;

/** Owner-only fixed CSV export. Exporting does not transfer ownership or change any ledger. */
@Service
public class HandoverService {
    private final Store db;private final Scope scope;private final Materials materials;
    public HandoverService(Store db,Scope scope,Materials materials){this.db=db;this.scope=scope;this.materials=materials;}

    public Map<String,Object> export(Actor actor,UUID conversation){
        return db.transaction(()->{
            // Metadata access or a messages-only review lease never authorizes an export.
            var c=scope.ownConversation(actor,conversation);UUID environment=UUID.fromString(uuid(c.get("environment_id")));
            var grant=db.one("SELECT revision FROM grants WHERE tenant_id=? AND subject_id=? AND environment_id=? AND active=TRUE",id(TENANT),id(actor.id()),id(environment));
            String authorization="grant:"+actor.id()+":"+environment+":"+number(grant.get("revision"));
            var sourceRows=db.rows("SELECT * FROM attachments WHERE tenant_id=? AND conversation_id=? ORDER BY id",id(TENANT),id(conversation));
            for(var source:sourceRows){
                scope.authorize(actor,CommandScope.of(CommandScope.Kind.ATTACHMENT,UUID.fromString(uuid(source.get("id")))));
                if(!Boolean.TRUE.equals(source.get("source_active")))throw new Problem(409,"SOURCE_REVOKED_OR_CHANGED");
                if(!"ready".equals(source.get("state")))throw new Problem(409,"SOURCE_UNAVAILABLE");
                // Verify bytes while the same business guard protects the ACL check. Never export bytes.
                materials.source(conversation,UUID.fromString(uuid(source.get("id"))));
            }
            var approvals=db.rows("SELECT p.* FROM approvals p JOIN execution_bindings b ON b.tenant_id=p.tenant_id AND b.id=p.binding_id WHERE p.tenant_id=? AND p.conversation_id=? ORDER BY (b.closed_at IS NULL) DESC,p.input_revision DESC,b.closed_at DESC,p.id DESC",id(TENANT),id(conversation));
            var plans=new HashMap<String,Map<String,Object>>();
            for(var approval:approvals){
                var plan=db.object(approval.get("plan_json"));checkHash(plan,hex(approval.get("operation_hash")));
                if(!environment.toString().equals(plan.get("environmentId"))||!"reconcile_csv_readonly_v1".equals(plan.get("operation"))||!"investigation".equals(plan.get("purpose"))||number(plan.get("inputRevision"))!=number(approval.get("input_revision"))||number(plan.get("environmentRevision"))!=number(approval.get("environment_revision"))||number(plan.get("authorizationRevision"))!=number(approval.get("authorization_revision"))||!java.time.Instant.parse(plan.get("validUntil").toString()).equals(instant(approval.get("valid_until"))))throw new Problem(409,"INTEGRITY_MISMATCH");
                materials.assertSources(conversation,plan);plans.put(uuid(approval.get("binding_id")),plan);
                if(approval.get("context_manifest_id")!=null)context(approval,plan,c,actor);
            }
            Map<String,Object> primary=approvals.isEmpty()?null:approvals.getFirst();var primaryPlan=primary==null?Map.<String,Object>of():plans.get(uuid(primary.get("binding_id")));
            var unresolved=new ArrayList<Unresolved>();
            var context=primary==null?null:context(primary,primaryPlan,c,actor);
            if(context!=null&&context.state()!=ContextState.active)unresolved.add(new Unresolved("CONTEXT_STALE",UUID.fromString(uuid(primary.get("binding_id"))),"旧清单只供交接核对；请基于当前输入重新准备和批准计划。"));
            if(primary!=null&&context==null)unresolved.add(new Unresolved("CONTEXT_UNAVAILABLE",UUID.fromString(uuid(primary.get("binding_id"))),"历史记录没有固定清单，不能据此再次执行。"));
            if(primary==null)unresolved.add(new Unresolved("NO_FIXED_PLAN",null,"补齐并确认固定 CSV 检查范围。"));
            var executions=new ArrayList<Execution>();
            for(var binding:db.rows("SELECT b.* FROM execution_bindings b WHERE b.tenant_id=? AND b.conversation_id=? ORDER BY b.id",id(TENANT),id(conversation))){
                executions.add(execution(binding));
                if(binding.get("closed_at")==null)unresolved.add(new Unresolved("OPEN_BINDING_RETAINED",UUID.fromString(uuid(binding.get("id"))),"沿用原提交核查执行拥有者；本次导出不关闭绑定、不释放预占、不启动替代执行。"));
            }
            var inputs=new ArrayList<Input>();
            for(var row:sourceRows){String reference=uuid(row.get("id"));String role=reference.equals(primaryPlan.get("detailAttachmentId"))?"detail":reference.equals(primaryPlan.get("reportAttachmentId"))?"report":"other";
                inputs.add(new Input(UUID.fromString(reference),role,hex(row.get("sha256")),number(row.get("bytes")),InputState.active,authorization));}
            var artifacts=new ArrayList<Artifact>();var verifications=new ArrayList<Verification>();
            for(var row:db.rows("SELECT * FROM artifacts WHERE tenant_id=? AND conversation_id=? AND state='ready' ORDER BY id",id(TENANT),id(conversation))){
                UUID reference=UUID.fromString(uuid(row.get("id")));scope.artifact(actor,reference);
                var summary=db.object(row.get("summary_json"));var plan=plans.get(uuid(row.get("binding_id")));
                if(plan==null||!CanonicalJson.hash(db.json(plan)).equals(CanonicalJson.hash(db.json(summary.get("plan")))))throw new Problem(409,"INTEGRITY_MISMATCH");
                byte[] bytes=materials.read(reference);if(bytes.length!=number(row.get("bytes"))||!CanonicalJson.hashBytes(bytes).equals(hex(row.get("sha256"))))throw new Problem(409,"INTEGRITY_MISMATCH");
                var anchored=anchoredSummary(row,summary);
                if(anchored==null)unresolved.add(new Unresolved("LEGACY_SUMMARY_UNANCHORED",UUID.fromString(uuid(row.get("binding_id"))),"历史数值摘要缺少持久 Provider 摘要产物哈希锚；包中不附未经核验的数值。"));
                artifacts.add(new Artifact(reference,UUID.fromString(uuid(row.get("binding_id"))),hex(row.get("sha256")),number(row.get("bytes")),row.get("state").toString(),number(row.get("result_revision")),number(row.get("input_revision")),anchored));
                for(var verification:db.rows("SELECT * FROM verifications WHERE tenant_id=? AND artifact_id=? ORDER BY result_revision",id(TENANT),id(reference)))
                    verifications.add(new Verification(reference,number(verification.get("result_revision")),UUID.fromString(uuid(verification.get("subject_id"))),Boolean.TRUE.equals(verification.get("resolved"))));
            }
            for(var row:db.rows("SELECT binding_id FROM artifacts WHERE tenant_id=? AND conversation_id=? AND state<>'ready' ORDER BY binding_id",id(TENANT),id(conversation)))
                unresolved.add(new Unresolved("HISTORIC_RESULT_NOT_EXPORTED",UUID.fromString(uuid(row.get("binding_id"))),"旧结果已失效；包内不附旧结果正文或文件，请依据当前授权重新核查。"));
            var reservations=new ArrayList<Reservation>();var balances=new TreeMap<String,AccountBalance>();
            for(var row:db.rows("SELECT r.* FROM quota_reservations r JOIN execution_bindings b ON b.tenant_id=r.tenant_id AND b.id=r.binding_id WHERE b.tenant_id=? AND b.conversation_id=? ORDER BY r.binding_id",id(TENANT),id(conversation))){
                if(!uuid(row.get("subject_id")).equals(actor.id().toString()))throw new Problem(409,"INTEGRITY_MISMATCH");
                String total=keyText(row.get("total_account_key")),model=keyText(row.get("model_account_key")),period=row.get("period_key").toString();UUID binding=UUID.fromString(uuid(row.get("binding_id")));
                reservations.add(new Reservation(binding,actor.id(),period,row.get("state").toString(),total,model));
                if(total==null){unresolved.add(new Unresolved("LEGACY_QUOTA_PAIR_UNKNOWN",binding,"原预占账户对未知；保留原周期挂账，不按当前模型推定或释放。"));continue;}
                for(String account:List.of(total,model)){
                    var balance=db.one("SELECT turn_limit,reserved,consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND period_key=? AND account_key=?",id(TENANT),id(actor.id()),period,key(account));
                    balances.put(actor.id()+":"+period+":"+account,new AccountBalance(actor.id(),account,period,number(balance.get("turn_limit")),number(balance.get("reserved")),number(balance.get("consumed"))));
                }
            }
            var value=new HandoverPackage(HandoverPackage.VERSION,ContextManifests.time(db.now()),new Conversation(conversation,environment,number(c.get("input_revision")),number(c.get("revision")),number(c.get("event_sequence")),c.get("state").toString()),context,primary==null?null:operation(primary,primaryPlan),executions,inputs,artifacts,reservations,new ArrayList<>(balances.values()),verifications,unresolved,new Responsibility(null,"unassigned",List.of("由获授权负责人核对未决项并明确下一位责任人；导出不等于完成接管。")));
            String hash=value.sha256();db.audit(actor.id(),"handover.export",conversation,Map.of("packageSha256",hash,"schemaVersion",HandoverPackage.VERSION,"eventWatermark",number(c.get("event_sequence")),"purpose","fixed_csv_handover"));
            return Map.of("package",value,"packageSha256",hash);
        });
    }
    private Operation operation(Map<String,Object> row,Map<String,Object> plan){
        Authority authority=null;
        if(row.get("authority_json")!=null){var a=db.object(row.get("authority_json"));authority=new Authority(Objects.toString(a.get("kind"),"unknown"),a.get("subjectId")==null?null:UUID.fromString(a.get("subjectId").toString()),Objects.toString(a.get("qualificationRef"),null));}
        return new Operation(UUID.fromString(uuid(row.get("id"))),UUID.fromString(uuid(row.get("binding_id"))),number(row.get("revision")),row.get("state").toString(),hex(row.get("operation_hash")),new CsvScope(UUID.fromString(plan.get("detailAttachmentId").toString()),plan.get("detailAttachmentIdHash").toString(),UUID.fromString(plan.get("reportAttachmentId").toString()),plan.get("reportAttachmentIdHash").toString(),plan.get("from").toString(),plan.get("to").toString(),plan.get("timeZone").toString(),plan.get("currency").toString(),plan.get("status").toString()),authority,number(row.get("input_revision")),number(row.get("environment_revision")),number(row.get("authorization_revision")),plan.get("validUntil").toString());
    }
    private Context context(Map<String,Object> approval,Map<String,Object> plan,Map<String,Object> c,Actor actor){
        if(approval.get("context_manifest_id")==null){if(plan.containsKey("contextManifestRef"))throw new Problem(409,"INTEGRITY_MISMATCH");return null;}
        UUID reference=UUID.fromString(uuid(approval.get("context_manifest_id")));if(!reference.toString().equals(plan.get("contextManifestRef")))throw new Problem(409,"INTEGRITY_MISMATCH");
        var row=db.one("SELECT * FROM context_manifests WHERE tenant_id=? AND id=? AND conversation_id=? AND subject_id=?",id(TENANT),id(reference),c.get("id"),id(actor.id()));var manifest=db.object(row.get("manifest_json"));String hash=hex(row.get("manifest_hash"));checkHash(manifest,hash);
        if(!hash.equals(plan.get("contextManifestHash"))||!TENANT.toString().equals(manifest.get("tenantId"))||!uuid(c.get("id")).equals(manifest.get("conversationId"))||!actor.id().toString().equals(manifest.get("subjectId"))||!uuid(c.get("environment_id")).equals(manifest.get("environmentId"))||!"investigation".equals(row.get("purpose"))||!row.get("purpose").equals(manifest.get("purpose"))||number(row.get("input_revision"))!=number(approval.get("input_revision"))||number(manifest.get("inputRevision"))!=number(row.get("input_revision"))||number(row.get("authorization_revision"))!=number(approval.get("authorization_revision"))||number(manifest.get("authorizationRevision"))!=number(row.get("authorization_revision")))throw new Problem(409,"INTEGRITY_MISMATCH");
        var expectedScope=new LinkedHashMap<String,Object>();for(String field:List.of("detailAttachmentId","reportAttachmentId","from","to","timeZone","currency","status"))expectedScope.put(field,plan.get(field));
        if(!expectedScope.equals(db.object(manifest.get("providedScope"))))throw new Problem(409,"INTEGRITY_MISMATCH");
        var manifestSources=new HashSet<String>();
        for(Object object:(List<?>)manifest.get("attachments")){
            var source=db.object(object);String attachment=source.get("attachmentId").toString();
            if(!manifestSources.add(attachment))throw new Problem(409,"INTEGRITY_MISMATCH");
            var current=db.one("SELECT sha256,bytes,state,source_active FROM attachments WHERE tenant_id=? AND conversation_id=? AND id=?",id(TENANT),c.get("id"),id(attachment));
            if(!"ready".equals(current.get("state"))||!Boolean.TRUE.equals(current.get("source_active")))throw new Problem(409,"SOURCE_REVOKED_OR_CHANGED");
            if(!hex(current.get("sha256")).equals(source.get("sha256"))||number(current.get("bytes"))!=number(source.get("bytes")))throw new Problem(409,"INTEGRITY_MISMATCH");
        }
        if(!manifestSources.contains(plan.get("detailAttachmentId"))||!manifestSources.contains(plan.get("reportAttachmentId")))throw new Problem(409,"INTEGRITY_MISMATCH");
        for(Object object:(List<?>)manifest.get("messages")){
            var message=db.object(object);var current=db.one("SELECT text_content,input_revision FROM messages WHERE tenant_id=? AND conversation_id=? AND id=?",id(TENANT),c.get("id"),id(message.get("messageId").toString()));String body=current.get("text_content").toString();
            if(number(current.get("input_revision"))!=number(message.get("inputRevision"))||number(message.get("inputRevision"))>number(row.get("input_revision"))||number(message.get("characters"))!=body.length()||!CanonicalJson.hashBytes(body.getBytes(StandardCharsets.UTF_8)).equals(message.get("sha256")))throw new Problem(409,"INTEGRITY_MISMATCH");
        }
        String reason=Objects.toString(row.get("invalidation_reason"),null);
        if(!"active".equals(row.get("state"))&&reason==null)reason="CONTEXT_INVALIDATED";
        if(number(row.get("input_revision"))!=number(c.get("input_revision")))reason="INPUT_CHANGED";
        if(number(row.get("authorization_revision"))!=scope.actor(actor.alias()).revision())reason="AUTHORIZATION_CHANGED";
        var grant=db.one("SELECT revision FROM grants WHERE tenant_id=? AND subject_id=? AND environment_id=? AND active=TRUE",id(TENANT),id(actor.id()),c.get("environment_id"));
        String grantPrefix="grant:"+actor.id()+":"+uuid(c.get("environment_id"))+":";String originalGrant=Objects.toString(manifest.get("authorizationRef"),"");
        if(!originalGrant.startsWith(grantPrefix))throw new Problem(409,"INTEGRITY_MISMATCH");
        try{io.aitest.contract.ContractChecks.safeInteger(Long.parseLong(originalGrant.substring(grantPrefix.length())));}catch(IllegalArgumentException invalid){throw new Problem(409,"INTEGRITY_MISMATCH");}
        if(!originalGrant.equals(grantPrefix+number(grant.get("revision"))))reason="AUTHORIZATION_CHANGED";
        var expectedConfigurations=new TreeMap<String,Long>();expectedConfigurations.put(plan.get("environmentId").toString(),number(plan.get("environmentRevision")));db.object(plan.get("dependencyRevisions")).forEach((key,value)->expectedConfigurations.put(key,number(value)));var actualConfigurations=new TreeMap<String,Long>();
        for(Object object:(List<?>)manifest.get("configurations")){
            var config=db.object(object);if(actualConfigurations.put(config.get("objectId").toString(),number(config.get("revision")))!=null)throw new Problem(409,"INTEGRITY_MISMATCH");var version=db.one("SELECT content_json FROM config_versions WHERE tenant_id=? AND object_id=? AND revision=?",id(TENANT),id(config.get("objectId").toString()),number(config.get("revision")));checkHash(db.object(version.get("content_json")),config.get("sha256").toString());
            var current=db.one("SELECT revision,disabled FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),id(config.get("objectId").toString()));
            if(Boolean.TRUE.equals(current.get("disabled"))||number(current.get("revision"))!=number(config.get("revision")))reason="CONFIGURATION_CHANGED";
        }
        if(!expectedConfigurations.equals(actualConfigurations))throw new Problem(409,"INTEGRITY_MISMATCH");
        return new Context(reference,hash,row.get("purpose").toString(),number(row.get("input_revision")),reason==null?ContextState.active:ContextState.stale,reason);
    }
    private CsvResult anchoredSummary(Map<String,Object> artifact,Map<String,Object> platformSummary){
        var rows=db.rows("SELECT descriptor_json FROM fixture_artifacts WHERE tenant_id=? AND binding_id=? ORDER BY artifact_id",id(TENANT),artifact.get("binding_id"));
        var journal=db.rows("SELECT executor_scope FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),artifact.get("binding_id"));
        if(rows.isEmpty()){
            if(!journal.isEmpty()&&journal.getFirst().get("executor_scope")!=null)throw new Problem(409,"INTEGRITY_MISMATCH");
            return null; // Missing legacy evidence remains unknown; never trust its unanchored numbers.
        }
        String binding=uuid(artifact.get("binding_id"));ArtifactDescriptor csv=null,summary=null;
        var published=new HashMap<String,ArtifactDescriptor>();
        for(var fact:db.rows("SELECT event_json FROM fixture_events WHERE tenant_id=? AND binding_id=? ORDER BY sequence",id(TENANT),artifact.get("binding_id"))){
            var event=ProviderJson.read(fact.get("event_json").toString(),ExecutionEvent.class);
            if(event.payload() instanceof EventPayloadArtifactReady ready){
                var previous=published.put(ready.artifact().artifactId(),ready.artifact());
                if(previous!=null&&!previous.equals(ready.artifact()))throw new Problem(409,"INTEGRITY_MISMATCH");
            }
        }
        for(var row:rows){var descriptor=ProviderJson.read(row.get("descriptor_json").toString(),ArtifactDescriptor.class);
            if(!descriptor.equals(published.remove(descriptor.artifactId())))throw new Problem(409,"INTEGRITY_MISMATCH");
            if(!descriptor.bindingId().equals(binding)||!descriptor.authorizationRef().equals("fixture-binding:"+binding)||!descriptor.sourceRef().equals("fixture-artifact:"+descriptor.artifactId()))throw new Problem(409,"INTEGRITY_MISMATCH");
            if(descriptor.mimeType().equals("text/csv")){if(csv!=null)throw new Problem(409,"INTEGRITY_MISMATCH");csv=descriptor;}
            else if(descriptor.mimeType().equals("application/json")){if(summary!=null)throw new Problem(409,"INTEGRITY_MISMATCH");summary=descriptor;}
            else throw new Problem(409,"INTEGRITY_MISMATCH");
        }
        if(!published.isEmpty())throw new Problem(409,"INTEGRITY_MISMATCH");
        if(csv==null||summary==null||!csv.artifactId().equals(uuid(artifact.get("id")))||!csv.sha256().equals(hex(artifact.get("sha256")))||csv.bytes()!=number(artifact.get("bytes")))throw new Problem(409,"INTEGRITY_MISMATCH");
        byte[] bytes=materials.read(UUID.fromString(summary.artifactId()));if(bytes.length!=summary.bytes()||!CanonicalJson.hashBytes(bytes).equals(summary.sha256()))throw new Problem(409,"INTEGRITY_MISMATCH");
        var value=db.object(new String(bytes,StandardCharsets.UTF_8));if(!CanonicalJson.hash(db.json(value)).equals(CanonicalJson.hash(db.json(platformSummary))))throw new Problem(409,"INTEGRITY_MISMATCH");
        return new CsvResult(number(value.get("differenceCount")),value.get("detailTotal").toString(),value.get("reportTotal").toString(),value.get("totalDifference").toString(),value.get("currency").toString(),value.get("algorithm").toString());
    }
    private Execution execution(Map<String,Object> binding){
        UUID reference=UUID.fromString(uuid(binding.get("id")));String provider=null,run=null,receipt=null,outcome=null,snapshotHash=null,acceptedAt=time(binding.get("accepted_at")),providerCursor=null,closureProof=null;Long snapshotRevision=null,providerWatermark=null;
        if(binding.get("execution_spec_json")!=null){var spec=ProviderJson.read(binding.get("execution_spec_json").toString(),ExecutionSpec.class);if(!reference.toString().equals(spec.bindingId())||!ExecutionSpecs.hash(spec).equals(hex(binding.get("spec_hash"))))throw new Problem(409,"INTEGRITY_MISMATCH");provider=spec.connection().providerId();}
        long sequence=0;
        for(var row:db.rows("SELECT sequence,event_json FROM fixture_events WHERE tenant_id=? AND binding_id=? ORDER BY sequence",id(TENANT),id(reference))){
            var event=ProviderJson.read(row.get("event_json").toString(),ExecutionEvent.class);
            if(event.sequence()!=++sequence||event.sequence()!=number(row.get("sequence"))||!event.bindingId().equals(reference.toString()))throw new Problem(409,"INTEGRITY_MISMATCH");
            if(provider!=null&&!provider.equals(event.providerId()))throw new Problem(409,"INTEGRITY_MISMATCH");provider=event.providerId();
            if(event.payload() instanceof EventPayloadExecutionAccepted accepted){
                var fact=accepted.receipt();if(!fact.binding().bindingId().equals(reference.toString())||!fact.specHash().equals(hex(binding.get("spec_hash")))||!event.providerRunId().equals(fact.run().providerRunId()))throw new Problem(409,"INTEGRITY_MISMATCH");
                receipt=fact.receiptId();run=fact.run().providerRunId();acceptedAt=fact.acceptedAt();
            }
            if(event.payload() instanceof EventPayloadExecutionUpdated updated){var snapshot=updated.snapshot();if(!snapshot.binding().bindingId().equals(reference.toString())||!snapshot.binding().providerId().equals(provider)||snapshot.run()!=null&&run!=null&&!snapshot.run().providerRunId().equals(run))throw new Problem(409,"INTEGRITY_MISMATCH");if(run==null&&snapshot.run()!=null)run=snapshot.run().providerRunId();snapshotRevision=snapshot.snapshotRevision();snapshotHash=CanonicalJson.hash(ProviderJson.write(snapshot));outcome=snapshot.outcome();providerWatermark=snapshot.eventWatermark();providerCursor=snapshot.resumeCursor();}
        }
        var journal=db.rows("SELECT * FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(reference));
        if(!journal.isEmpty()&&(number(journal.getFirst().get("event_sequence"))!=sequence||!hex(journal.getFirst().get("spec_hash")).equals(hex(binding.get("spec_hash")))))throw new Problem(409,"INTEGRITY_MISMATCH");
        Boolean providerCancelRequested=journal.isEmpty()||journal.getFirst().get("executor_scope")==null?null:Boolean.TRUE.equals(journal.getFirst().get("cancel_requested"));
        for(var command:db.rows("SELECT CONVERT(method USING utf8mb4) method,response_json FROM fixture_commands WHERE tenant_id=? AND binding_id=? AND method IN (_binary'closeSubmission',_binary'cancel') ORDER BY method,command_id",id(TENANT),id(reference))){
            if(command.get("method").equals("closeSubmission")){
                var closed=ProviderJson.read(command.get("response_json").toString(),SubmissionClosure.class);
                if(closed instanceof SubmissionClosureNotAcceptedFinal finalClosure){
                    if(journal.isEmpty()||journal.getFirst().get("accepted_at")!=null||!"closed".equals(journal.getFirst().get("status")))throw new Problem(409,"INTEGRITY_MISMATCH");String localReceipt=uuid(journal.getFirst().get("receipt_id"));
                    if(!finalClosure.proofRef().equals("fixture-nonacceptance:"+localReceipt)||!finalClosure.admissionFence().equals("fixture-fence:"+localReceipt))throw new Problem(409,"INTEGRITY_MISMATCH");closureProof=finalClosure.proofRef();
                }
            }else{
                var cancelled=ProviderJson.read(command.get("response_json").toString(),CancelReceipt.class);
                if(cancelled instanceof CancelReceiptConfirmed confirmed){var snapshot=confirmed.snapshot();
                    if(journal.isEmpty()||run==null||snapshot.run()==null||!snapshot.run().providerRunId().equals(run)||!snapshot.binding().bindingId().equals(reference.toString())||!snapshot.binding().nodeRunId().equals(uuid(binding.get("node_run_id")))||!snapshot.binding().providerId().equals(provider)||!confirmed.proofRef().equals("fixture-terminal:"+uuid(journal.getFirst().get("receipt_id"))))throw new Problem(409,"INTEGRITY_MISMATCH");closureProof=confirmed.proofRef();
                }
            }
        }
        // A past observation cannot prove current liveness. Only a durably closed binding is inactive.
        Ownership ownership=binding.get("closed_at")==null?Ownership.unknown:Ownership.inactive;
        EffectStatus effect=binding.get("closed_at")==null?EffectStatus.unknown:EffectStatus.valueOf(binding.get("effect_status").toString());
        return new Execution(reference,UUID.fromString(uuid(binding.get("node_run_id"))),provider,run,binding.get("state").toString(),acceptedAt,time(binding.get("closed_at")),ownership,outcome,effect,snapshotRevision,snapshotHash,receipt,Boolean.TRUE.equals(binding.get("stopped")),providerCancelRequested,closureProof,providerWatermark,providerCursor);
    }
    private static String time(Object value){return value==null?null:ContextManifests.time(instant(value));}
    private static String keyText(Object value){return value==null?null:new String((byte[])value,StandardCharsets.UTF_8);}
    private void checkHash(Object value,String expected){if(!CanonicalJson.hash(db.json(value)).equals(expected))throw new Problem(409,"INTEGRITY_MISMATCH");}
}
