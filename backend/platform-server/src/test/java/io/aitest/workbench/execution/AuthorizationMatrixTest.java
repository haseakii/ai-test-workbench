package io.aitest.workbench.execution;

import io.aitest.contract.CanonicalJson;
import io.aitest.workbench.configuration.ConfigurationService;
import io.aitest.workbench.identity.ContentReview;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.material.Materials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ACL service-boundary matrix using the existing approved MySQL database.
 * Scenario rows always roll back. The artifact is an explicit ACL-only fixture,
 * not evidence that a Provider executed; real HTTP/SSE/export checks are separate.
 */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class AuthorizationMatrixTest {
    @TempDir Path blobs;
    interface Scenario {void run()throws Exception;}
    DispatchAtomicityTest helper(){var result=new DispatchAtomicityTest();result.blobs=blobs;return result;}
    void rollback(DispatchAtomicityTest.Fixture f,Scenario scenario){
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(f.db().jdbc.getDataSource()))).execute(status->{
            status.setRollbackOnly();f.db().one("SELECT id FROM tenants WHERE id=? FOR UPDATE",id(TENANT));
            try{scenario.run();}catch(RuntimeException|Error e){throw e;}catch(Exception e){throw new AssertionError("ACL fixture failed",e);}
            return null;
        });
    }
    ConfigurationService config(DispatchAtomicityTest.Fixture f){return new ConfigurationService(f.db(),f.scope());}
    Materials materials(DispatchAtomicityTest.Fixture f)throws Exception{return new Materials(f.db(),f.scope(),blobs.toString());}
    Scope.Actor subject(DispatchAtomicityTest.Fixture f,String role,UUID environment){
        String alias="acl-"+UUID.randomUUID();UUID subject=UUID.randomUUID();
        f.db().update("INSERT INTO subjects VALUES(?,?)",id(subject),key(alias));
        f.db().update("INSERT INTO memberships(tenant_id,subject_id,role_name,state) VALUES(?,?,?,'active')",id(TENANT),id(subject),role);
        f.db().update("INSERT INTO quota_policies(tenant_id,subject_id) VALUES(?,?)",id(TENANT),id(subject));
        if(environment!=null)config(f).grant(f.scope().actor("admin"),alias,environment,role,UUID.randomUUID().toString());
        return f.scope().actor(alias);
    }
    void problem(String code,int status,Runnable action){var error=assertThrows(Store.Problem.class,action::run);assertEquals(code,error.code);assertEquals(status,error.status);}
    byte[] csv(){return "order_id,amount,currency,status,occurred_at\r\nO1,1.00,EUR,completed,2026-10-25T12:00:00Z\r\n".getBytes(StandardCharsets.UTF_8);}
    MockMultipartFile upload(){return new MockMultipartFile("file","detail.csv","text/csv",csv());}
    UUID conversation(DispatchAtomicityTest h,DispatchAtomicityTest.Fixture f,Map<String,Object> p){return h.conversation(f,p);}
    UUID context(Map<String,Object> p){return UUID.fromString(((Map<?,?>)p.get("plan")).get("contextManifestRef").toString());}
    long count(DispatchAtomicityTest.Fixture f,String table,UUID conversation){return number(f.db().one("SELECT COUNT(*) n FROM "+table+" WHERE tenant_id=? AND conversation_id=?",id(TENANT),id(conversation)).get("n"));}
    UUID aclArtifact(DispatchAtomicityTest h,DispatchAtomicityTest.Fixture f,Map<String,Object> p)throws Exception {
        UUID c=conversation(h,f,p),artifact=UUID.randomUUID();var material=materials(f);var calculated=material.calculate(c,f.db().object(p.get("plan")));material.write(artifact,calculated.csv());
        f.db().update("INSERT INTO artifacts(tenant_id,id,conversation_id,binding_id,sha256,bytes,state,result_revision,input_revision,summary_json) VALUES(?,?,?,?,?,?,'ready',1,3,?)",id(TENANT),id(artifact),id(c),id(h.binding(p)),hex(CanonicalJson.hashBytes(calculated.csv())),calculated.csv().length,f.db().json(calculated.summary()));
        return artifact;
    }
    @Test void sameEnvironmentPeerCannotUploadReadContextEventsOrDownloadOwnerArtifact()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var peer=subject(f,"member",f.environment());var p=h.prepared(f,false);UUID c=conversation(h,f,p),manifest=context(p),artifact=aclArtifact(h,f,p);var material=materials(f);
            assertNotNull(f.scope().environment(peer,f.environment()));assertEquals(manifest.toString(),f.preparation().context(f.owner(),manifest).get("id"));assertTrue(material.download(f.owner(),artifact).length>0);
            long before=count(f,"attachments",c);
            problem("NOT_FOUND",404,()->material.upload(peer,c,upload(),UUID.randomUUID().toString()));
            problem("NOT_FOUND",404,()->f.conversations().snapshot(peer,c));
            problem("NOT_FOUND",404,()->f.conversations().events(peer,c,null,100));
            problem("NOT_FOUND",404,()->f.preparation().context(peer,manifest));
            problem("NOT_FOUND",404,()->material.download(peer,artifact));assertEquals(before,count(f,"attachments",c));
            assertTrue(f.db().rows("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))).isEmpty());
        });
    }
    @Test void reviewerBodyLeaseGrantsMessagesOnlyAndDoesNotGrantContextEventsUploadOrArtifact()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var reviewer=subject(f,"reviewer",f.environment());var p=h.prepared(f,false);UUID c=conversation(h,f,p),manifest=context(p),artifact=aclArtifact(h,f,p);var material=materials(f);var review=new ContentReview(f.db(),f.scope());
            problem("CONTENT_REVIEW_NOT_GRANTED",403,()->review.lease(reviewer,c,"ACL matrix review",60,UUID.randomUUID().toString()));
            review.eligibility(f.scope().actor("admin"),reviewer.alias(),f.environment(),true,reviewer.revision(),"Synthetic ACL review eligibility",UUID.randomUUID().toString());
            var current=f.scope().actor(reviewer.alias());assertEquals("messages_only",review.lease(current,c,"Inspect visible messages only",60,UUID.randomUUID().toString()).get("scope"));
            assertEquals(1,((List<?>)review.read(current,c).get("messages")).size());
            problem("NOT_FOUND",404,()->f.conversations().snapshot(current,c));problem("NOT_FOUND",404,()->f.conversations().events(current,c,null,50));
            problem("NOT_FOUND",404,()->f.preparation().context(current,manifest));problem("NOT_FOUND",404,()->material.download(current,artifact));
            problem("NOT_FOUND",404,()->material.upload(current,c,upload(),UUID.randomUUID().toString()));
            // The metadata endpoint intentionally bounds its unfiltered page to 100 rows.
            // Retained clock/concurrency fixtures may sort ahead of this rollback-only row.
            String marker="acl-metadata-"+UUID.randomUUID();
            f.conversations().rename(f.owner(),c,marker,number(f.scope().ownConversation(f.owner(),c).get("title_revision")),UUID.randomUUID().toString());
            var admin=f.scope().actor("admin");var metadata=review.records(admin,marker);
            assertEquals(1,metadata.size());assertEquals(c.toString(),metadata.getFirst().get("conversationId"));
            for(String forbidden:List.of("messages","attachments","artifacts"))assertFalse(metadata.getFirst().containsKey(forbidden),"metadata must not grant body or material access");
            problem("APPROVER_REQUIRED",403,()->review.read(admin,c));problem("NOT_FOUND",404,()->f.preparation().context(admin,manifest));problem("NOT_FOUND",404,()->material.download(admin,artifact));
        });
    }
    @Test void sameEnvironmentForeignAttachmentCannotBeAssociatedWithAnotherOwnedConversation()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var peer=subject(f,"member",f.environment());var p=h.prepared(f,false);var ownerPlan=f.db().object(p.get("plan"));
            UUID c=UUID.fromString(f.conversations().create(peer,f.environment(),UUID.randomUUID().toString()).get("id").toString());
            f.conversations().message(peer,c,UUID.randomUUID().toString(),"Another owned conversation",1,UUID.randomUUID().toString());var material=materials(f);
            var own=material.upload(peer,c,upload(),UUID.randomUUID().toString());
            var request=new PreparationService.Request(ownerPlan.get("detailAttachmentId").toString(),own.get("id").toString(),"2026-10-25T00:00:00Z","2026-10-26T00:00:00Z","UTC","EUR","completed",2,600);
            long inputs=number(f.scope().ownConversation(peer,c).get("input_revision"));
            problem("NOT_FOUND",404,()->f.preparation().prepare(peer,c,request,UUID.randomUUID().toString()));
            assertEquals(inputs,number(f.scope().ownConversation(peer,c).get("input_revision")));assertEquals(0,count(f,"execution_bindings",c));assertEquals(0,count(f,"approvals",c));
            assertTrue(f.preparation().context(f.owner(),context(p)).containsKey("manifest"));
        });
    }
    @Test void anotherValidEnvironmentGrantCannotExposeOwnerEnvironmentOrItsContent()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var configuration=config(f);var admin=f.scope().actor("admin");
            var original=f.db().object(f.db().one("SELECT published_json FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),id(f.environment())).get("published_json"));
            var other=configuration.create(admin,"environment","ACL isolated environment "+UUID.randomUUID(),Map.of("modelRef",original.get("modelRef"),"workflowRef",original.get("workflowRef"),"timeZone","UTC","turnLimit",100_000),UUID.randomUUID().toString());UUID environment=UUID.fromString(other.get("id").toString());
            var dependencies=new LinkedHashMap<String,Long>();f.db().object(original.get("dependencyRevisions")).forEach((key,value)->dependencies.put(key,number(value)));
            configuration.publish(admin,List.of(new ConfigurationService.Publication(environment.toString(),0,dependencies)),UUID.randomUUID().toString());
            var peer=subject(f,"member",environment);var reviewer=subject(f,"reviewer",environment);var p=h.prepared(f,false);UUID c=conversation(h,f,p);var material=materials(f);
            assertNotNull(f.scope().environment(peer,environment));assertEquals(List.of(environment.toString()),configuration.options(peer).stream().map(r->r.get("id").toString()).toList());
            problem("NOT_FOUND",404,()->f.scope().environment(peer,f.environment()));problem("NOT_FOUND",404,()->f.conversations().snapshot(peer,c));
            problem("NOT_FOUND",404,()->f.preparation().context(peer,context(p)));problem("NOT_FOUND",404,()->material.upload(peer,c,upload(),UUID.randomUUID().toString()));
            var review=new ContentReview(f.db(),f.scope());assertTrue(review.records(reviewer).isEmpty());
            problem("NOT_FOUND",404,()->review.lease(reviewer,c,"Cross-environment access denied",60,UUID.randomUUID().toString()));
            assertEquals(context(p).toString(),f.preparation().context(f.owner(),context(p)).get("id"));
        });
    }
    @Test void resumeCursorCannotCrossSubjectOrConversationAndPagesRecheckCurrentGrant()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var peer=subject(f,"member",f.environment());var p=h.prepared(f,false);UUID owner=conversation(h,f,p);var page=f.conversations().events(f.owner(),owner,null,1);String cursor=page.get("cursor").toString();
            problem("NOT_FOUND",404,()->f.conversations().events(peer,owner,cursor,50));
            UUID other=UUID.fromString(f.conversations().create(peer,f.environment(),UUID.randomUUID().toString()).get("id").toString());
            problem("CURSOR_INVALID",410,()->f.conversations().events(peer,other,cursor,50));
            String wrongSubject=f.conversations().cursor(peer,owner,1);problem("CURSOR_INVALID",410,()->f.conversations().events(f.owner(),owner,wrongSubject,50));
            config(f).revoke(f.scope().actor("admin"),f.owner().alias(),f.environment(),UUID.randomUUID().toString());
            problem("NOT_FOUND",404,()->f.conversations().events(f.owner(),owner,cursor,50));problem("NOT_FOUND",404,()->f.conversations().snapshot(f.owner(),owner));
        });
    }
    @Test void ownerRevocationBlocksOldActorArtifactContextUploadReceiptAndReviewerLease()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var reviewer=subject(f,"reviewer",f.environment());var p=h.prepared(f,false);UUID c=conversation(h,f,p),manifest=context(p),artifact=aclArtifact(h,f,p);var material=materials(f);var review=new ContentReview(f.db(),f.scope());
            review.eligibility(f.scope().actor("admin"),reviewer.alias(),f.environment(),true,reviewer.revision(),"Synthetic ACL review eligibility",UUID.randomUUID().toString());var current=f.scope().actor(reviewer.alias());
            String leaseKey=UUID.randomUUID().toString();review.lease(current,c,"Inspect authorized body only",60,leaseKey);assertFalse(((List<?>)review.read(current,c).get("messages")).isEmpty());
            var receipt=f.db().one("SELECT access_kind,access_id FROM commands WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("commandId").toString()));
            config(f).revoke(f.scope().actor("admin"),f.owner().alias(),f.environment(),UUID.randomUUID().toString());
            problem("NOT_FOUND",404,()->f.preparation().context(f.owner(),manifest));problem("NOT_FOUND",404,()->material.download(f.owner(),artifact));problem("NOT_FOUND",404,()->material.upload(f.owner(),c,upload(),UUID.randomUUID().toString()));
            problem("NOT_FOUND",404,()->f.scope().receipt(f.owner(),receipt));problem("SOURCE_AUTHORIZATION_REVOKED",403,()->review.read(current,c));
            problem("SOURCE_AUTHORIZATION_REVOKED",403,()->review.lease(current,c,"Inspect authorized body only",60,leaseKey));
            assertEquals("invalidated",f.db().one("SELECT state FROM context_manifests WHERE tenant_id=? AND id=?",id(TENANT),id(manifest)).get("state"));
        });
    }
    @Test void sourceRevocationAndNewInputBlockOldDerivedMaterialWithoutRewritingHistoricFacts()throws Exception {
        var h=helper();var f=h.fixture();rollback(f,()->{
            var p=h.prepared(f,false);UUID c=conversation(h,f,p),manifest=context(p),artifact=aclArtifact(h,f,p);var material=materials(f);var original=f.db().one("SELECT operation_hash,plan_json FROM approvals WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("approvalId").toString()));
            material.revoke(f.owner(),UUID.fromString(f.db().object(p.get("plan")).get("detailAttachmentId").toString()),UUID.randomUUID().toString());
            problem("CONTEXT_STALE",409,()->f.preparation().context(f.owner(),manifest));problem("SOURCE_REVOKED_OR_CHANGED",409,()->material.download(f.owner(),artifact));
            assertTrue(((List<?>)f.conversations().snapshot(f.owner(),c).get("artifacts")).isEmpty());
            var preserved=f.db().one("SELECT operation_hash,plan_json FROM approvals WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("approvalId").toString()));assertArrayEquals((byte[])original.get("operation_hash"),(byte[])preserved.get("operation_hash"));assertEquals(f.db().object(original.get("plan_json")),f.db().object(preserved.get("plan_json")));
            var newer=h.prepared(f,false);UUID newConversation=conversation(h,f,newer),newManifest=context(newer),oldArtifact=aclArtifact(h,f,newer);long revision=number(f.scope().ownConversation(f.owner(),newConversation).get("revision"));
            f.conversations().message(f.owner(),newConversation,UUID.randomUUID().toString(),"New input invalidates the prior context and result",revision,UUID.randomUUID().toString());
            problem("CONTEXT_STALE",409,()->f.preparation().context(f.owner(),newManifest));problem("RESULT_STALE",409,()->material.download(f.owner(),oldArtifact));
            assertTrue(((List<?>)f.conversations().snapshot(f.owner(),newConversation).get("artifacts")).isEmpty());
        });
    }
}
