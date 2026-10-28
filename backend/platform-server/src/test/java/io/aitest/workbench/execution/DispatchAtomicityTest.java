package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.*;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.configuration.ConfigurationService;
import io.aitest.workbench.conversation.ConversationService;
import io.aitest.workbench.material.Materials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** Calls the actual business transactions on MySQL; no alternate storage or HTTP failpoint. */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class DispatchAtomicityTest {
    @TempDir Path blobs;
    static class InjectedFailure extends RuntimeException{}
    static class FaultStore extends Store {
        String failAction;
        FaultStore(DriverManagerDataSource source){this(source,Clock.systemUTC());}
        FaultStore(DriverManagerDataSource source,Clock clock){super(new JdbcTemplate(source),new DataSourceTransactionManager(source),clock);}
        FaultStore(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,Clock clock){super(jdbc,manager,clock);}
        @Override public void audit(UUID actor,String action,UUID object,Map<String,Object> detail){
            super.audit(actor,action,object,detail);
            if(action.equals(failAction)){failAction=null;throw new InjectedFailure();}
        }
    }
    record Fixture(FaultStore db,Scope scope,Scope.Actor owner,Scope.Actor reviewer,UUID environment,
                   PreparationService preparation,ConversationService conversations,ExecutionWorker worker,FixtureProvider provider){}
    Fixture fixture()throws Exception {return fixture("normal");}
    Fixture fixture(String mode)throws Exception {return fixture(mode,Clock.systemUTC());}
    Fixture fixture(String mode,Clock clock)throws Exception {
        return fixture(mode,clock,source->new FaultStore(source,clock));
    }
    Fixture fixture(String mode,Clock clock,java.util.function.Function<DriverManagerDataSource,FaultStore> factory)throws Exception {
        var source=new DriverManagerDataSource(System.getenv("WB_DB_URL"),System.getenv("WB_DB_USER"),System.getenv("WB_DB_PASSWORD"));
        var db=factory.apply(source);var scope=new Scope(db);var config=new ConfigurationService(db,scope);
        var admin=scope.actor("admin");String alias="tx-probe-"+UUID.randomUUID();UUID subject=UUID.randomUUID();
        db.transaction(()->{
            db.update("INSERT INTO subjects VALUES(?,?)",id(subject),key(alias));
            db.update("INSERT INTO memberships(tenant_id,subject_id,role_name,state) VALUES(?,?,?,?)",id(TENANT),id(subject),"member","active");
            db.update("INSERT INTO quota_policies(tenant_id,subject_id) VALUES(?,?)",id(TENANT),id(subject));return null;
        });
        var m=config.create(admin,"model",alias,Map.of("backend","persistent_fixture","modelCalls",false),UUID.randomUUID().toString());
        var w=config.create(admin,"workflow",alias,Map.of("operation","reconcile_csv_readonly_v1"),UUID.randomUUID().toString());
        var e=config.create(admin,"environment",alias,Map.of("modelRef",m.get("id"),"workflowRef",w.get("id"),"timeZone","UTC","turnLimit",100_000,"testMode",mode),UUID.randomUUID().toString());
        UUID env=UUID.fromString(e.get("id").toString());
        config.publish(admin,List.of(new ConfigurationService.Publication(m.get("id").toString(),0,Map.of()),new ConfigurationService.Publication(w.get("id").toString(),0,Map.of()),new ConfigurationService.Publication(env.toString(),0,Map.of(m.get("id").toString(),0L,w.get("id").toString(),0L))),UUID.randomUUID().toString());
        config.grant(admin,alias,env,"member",UUID.randomUUID().toString());config.grant(admin,"reviewer",env,"reviewer",UUID.randomUUID().toString());
        var materials=new Materials(db,scope,blobs.toString());var preparation=new PreparationService(db,scope,materials,SyntheticCheckpoints.disabled());
        var conversations=new ConversationService(db,scope);var provider=new FixtureProvider(db,preparation,materials,SyntheticCheckpoints.disabled());
        var worker=new ExecutionWorker(db,scope,preparation,provider,materials,SyntheticCheckpoints.disabled(),new QuotaLedger(db));
        return new Fixture(db,scope,scope.actor(alias),scope.actor("reviewer"),env,preparation,conversations,worker,provider);
    }
    Map<String,Object> prepared(Fixture f,boolean approve)throws Exception {
        var db=f.db();var owner=f.owner();var c=f.conversations().create(owner,f.environment(),UUID.randomUUID().toString());UUID conversation=UUID.fromString(c.get("id").toString());
        f.conversations().message(owner,conversation,UUID.randomUUID().toString(),"Synthetic transaction probe",1,UUID.randomUUID().toString());
        var material=new Materials(db,f.scope(),blobs.toString());
        byte[] bytes="order_id,amount,currency,status,occurred_at\r\nO1,1.00,EUR,completed,2026-10-25T12:00:00Z\r\n".getBytes(StandardCharsets.UTF_8);
        var d=material.upload(owner,conversation,new MockMultipartFile("file","detail.csv","text/csv",bytes),UUID.randomUUID().toString());
        var r=material.upload(owner,conversation,new MockMultipartFile("file","report.csv","text/csv",bytes),UUID.randomUUID().toString());
        var p=f.preparation().prepare(owner,conversation,new PreparationService.Request(d.get("id").toString(),r.get("id").toString(),"2026-10-25T00:00:00Z","2026-10-26T00:00:00Z","UTC","EUR","completed",3,600),UUID.randomUUID().toString());
        if(approve)db.transaction(()->{
            f.preparation().decide(f.reviewer(),UUID.fromString(p.get("approvalId").toString()),true,p.get("operationHash").toString(),1,"Fixed synthetic scope reviewed",UUID.randomUUID().toString());
            // Keep the live service worker away from this test's queue rows, in the same commit.
            db.update("UPDATE outbox SET lease_until=? WHERE tenant_id=? AND binding_id=?",LocalDateTime.ofInstant(db.now().plusSeconds(600),ZoneOffset.UTC),id(TENANT),id(p.get("bindingId").toString()));return null;
        });
        return p;
    }
    boolean dispatch(Fixture f,Map<String,Object> plan)throws Exception {
        UUID binding=UUID.fromString(plan.get("bindingId").toString());var field=ExecutionWorker.class.getDeclaredField("worker");field.setAccessible(true);UUID worker=(UUID)field.get(f.worker());
        var outbox=f.db().one("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding));
        f.db().update("UPDATE outbox SET worker_id=? WHERE tenant_id=? AND id=?",id(worker),id(TENANT),outbox.get("id"));
        var method=ExecutionWorker.class.getDeclaredMethod("prepareDispatch",UUID.class,UUID.class);method.setAccessible(true);
        try{return (boolean)method.invoke(f.worker(),binding,UUID.fromString(uuid(outbox.get("id"))));}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
    void close(Fixture f,Map<String,Object> p)throws Exception {
        var row=f.db().one("SELECT conversation_id FROM approvals WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("approvalId").toString()));
        f.worker().stop(f.owner(),UUID.fromString(uuid(row.get("conversation_id"))),UUID.randomUUID().toString());
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){
            // These are manually constructed beans, so neither @Scheduled callback runs.
            // Drive the Provider's own state machine; the Worker only observes its facts.
            f.provider().tick();f.worker().tick();
            if(f.db().one("SELECT closed_at FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(p.get("bindingId").toString())).get("closed_at")!=null)return;
            Thread.sleep(50);
        }
        fail("Test binding was not safely closed");
    }
    @Test void approvalAuditFailureRollsBackDecisionOutboxNodeAndCommand()throws Exception {
        var f=fixture();var p=prepared(f,false);String command=UUID.randomUUID().toString();f.db().failAction="approval.decide";
        assertThrows(InjectedFailure.class,()->f.preparation().decide(f.reviewer(),UUID.fromString(p.get("approvalId").toString()),true,p.get("operationHash").toString(),1,"Fixed scope reviewed",command));
        var state=f.db().one("SELECT p.state approval,n.state node FROM approvals p JOIN execution_bindings b ON b.tenant_id=p.tenant_id AND b.id=p.binding_id JOIN node_runs n ON n.tenant_id=b.tenant_id AND n.id=b.node_run_id WHERE p.tenant_id=? AND p.id=?",id(TENANT),id(p.get("approvalId").toString()));
        assertEquals("pending",state.get("approval"));assertEquals("blocked",state.get("node"));
        assertTrue(f.db().rows("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(p.get("bindingId").toString())).isEmpty());
        assertTrue(f.db().rows("SELECT id FROM commands WHERE tenant_id=? AND idempotency_key=?",id(TENANT),key(command)).isEmpty());close(f,p);
    }
    @Test void dispatchAuditFailureRollsBackAllAccountsSlotBindingTicketOutboxAndEvent()throws Exception {
        var f=fixture();var p=prepared(f,true);f.db().failAction="execution.dispatch";
        long watermark=number(f.db().one("SELECT event_sequence FROM conversations WHERE tenant_id=? AND id=(SELECT conversation_id FROM approvals WHERE tenant_id=? AND id=?)",id(TENANT),id(TENANT),id(p.get("approvalId").toString())).get("event_sequence"));
        assertThrows(InjectedFailure.class,()->dispatch(f,p));
        assertEquals(0,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
        for(String table:List.of("quota_accounts","quota_reservations","admission_tickets"))assertTrue(f.db().rows("SELECT * FROM "+table+" WHERE tenant_id=? AND "+(table.equals("quota_accounts")?"subject_id":"binding_id")+"=?",id(TENANT),table.equals("quota_accounts")?id(f.owner().id()):id(p.get("bindingId").toString())).isEmpty());
        var state=f.db().one("SELECT b.state binding,n.state node,o.state queue,c.event_sequence FROM execution_bindings b JOIN node_runs n ON n.tenant_id=b.tenant_id AND n.id=b.node_run_id JOIN outbox o ON o.tenant_id=b.tenant_id AND o.binding_id=b.id JOIN conversations c ON c.tenant_id=b.tenant_id AND c.id=b.conversation_id WHERE b.tenant_id=? AND b.id=?",id(TENANT),id(p.get("bindingId").toString()));
        assertEquals("prepared",state.get("binding"));assertEquals("ready",state.get("node"));assertEquals("queued",state.get("queue"));assertEquals(watermark,number(state.get("event_sequence")));close(f,p);
    }
    void race(boolean quota)throws Exception {
        var f=fixture();var a=prepared(f,true);var b=prepared(f,true);
        f.db().update("UPDATE quota_policies SET turn_limit=? WHERE tenant_id=? AND subject_id=?",quota?1:100,id(TENANT),id(f.owner().id()));
        f.db().update("UPDATE active_slots SET slot_limit=? WHERE tenant_id=? AND subject_id=?",quota?2:1,id(TENANT),id(f.owner().id()));
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->{gate.await();return dispatch(f,a);});var second=pool.submit(()->{gate.await();return dispatch(f,b);});gate.countDown();
            assertEquals(1,(first.get(10,TimeUnit.SECONDS)?1:0)+(second.get(10,TimeUnit.SECONDS)?1:0));
            assertEquals(1,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
            for(var account:f.db().rows("SELECT reserved,consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()))){assertEquals(1,number(account.get("reserved")));assertEquals(0,number(account.get("consumed")));}
        }finally{pool.shutdownNow();close(f,a);close(f,b);}
        assertEquals(0,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
    }
    @Test void twoDispatchTransactionsCompeteForLastTotalAndModelQuota()throws Exception {race(true);}
    @Test void twoDispatchTransactionsCompeteForLastActiveSlot()throws Exception {race(false);}
    UUID binding(Map<String,Object> p){return UUID.fromString(p.get("bindingId").toString());}
    UUID conversation(Fixture f,Map<String,Object> p){return UUID.fromString(uuid(f.db().one("SELECT conversation_id FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding(p))).get("conversation_id")));}
    void reconcile(Fixture f,Map<String,Object> p)throws Exception {
        // Match the independent Provider scheduler before observing. Its real validation and
        // cancellation rules decide whether anything may execute; do not synthesize a terminal
        // or swallow failures here. In particular not_found/executing remain unexecuted.
        f.provider().tick();
        var queue=f.db().one("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding(p)));
        var method=ExecutionWorker.class.getDeclaredMethod("process",UUID.class,UUID.class,boolean.class);method.setAccessible(true);
        try{method.invoke(f.worker(),binding(p),UUID.fromString(uuid(queue.get("id"))),true);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
    @Test void firstProviderSnapshotAlreadyTerminalSettlesOnce()throws Exception {
        var f=fixture();var p=prepared(f,true);assertTrue(dispatch(f,p));
        f.provider().start(binding(p));assertEquals("succeeded",f.provider().execute(binding(p)).get("status"));
        assertEquals("submitting",f.db().one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding(p))).get("state"));
        reconcile(f,p);reconcile(f,p);
        var snapshot=f.conversations().snapshot(f.owner(),conversation(f,p));assertEquals("awaiting_verification",snapshot.get("state"));
        assertEquals(1,((List<?>)snapshot.get("artifacts")).size());
        for(var account:f.db().rows("SELECT consumed,reserved FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()))){assertEquals(1,number(account.get("consumed")));assertEquals(0,number(account.get("reserved")));}
        assertEquals(0,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
    }
    @Test void nonAuthoritativeNotFoundRetainsAllReservationsAndOwner()throws Exception {
        var f=fixture();var p=prepared(f,true);assertTrue(dispatch(f,p));reconcile(f,p);reconcile(f,p);
        assertEquals("acceptance_unknown",f.db().one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding(p))).get("state"));
        assertEquals("not_found",f.provider().lookup(binding(p)).get("status"));
        for(var account:f.db().rows("SELECT reserved,consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()))){assertEquals(1,number(account.get("reserved")));assertEquals(0,number(account.get("consumed")));}
        assertEquals(1,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));close(f,p);
    }
    @Test void concurrentStartAndPermanentCloseCannotResurrectKey()throws Exception {
        var f=fixture();var p=prepared(f,true);assertTrue(dispatch(f,p));var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var start=pool.submit(()->{gate.await();try{return f.provider().start(binding(p)).get("status");}catch(Store.Problem e){assertEquals("SUBMISSION_CLOSED",e.code);return "closed";}});
            var stop=pool.submit(()->{gate.await();return f.provider().close(binding(p));});gate.countDown();start.get(10,TimeUnit.SECONDS);stop.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        var fact=f.provider().lookup(binding(p));assertTrue(Set.of("closed","cancelled").contains(fact.get("status")));
        if(fact.get("status").equals("closed"))assertThrows(Store.Problem.class,()->f.provider().start(binding(p)));
        else assertEquals("cancelled",f.provider().start(binding(p)).get("status"));
        assertEquals(1,f.db().rows("SELECT binding_id FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding(p))).size());close(f,p);
    }
    @Test void lostReceiptReplayRacesCloseWithoutSecondAcceptanceOrCharge()throws Exception {
        var f=fixture("drop_receipt");var p=prepared(f,true);assertTrue(dispatch(f,p));
        assertThrows(FixtureProvider.LostReceipt.class,()->f.provider().start(binding(p)));
        var first=f.provider().lookup(binding(p));var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var retry=pool.submit(()->{gate.await();return f.provider().start(binding(p));});
            var close=pool.submit(()->{gate.await();return f.provider().close(binding(p));});gate.countDown();retry.get(10,TimeUnit.SECONDS);close.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        var last=f.provider().lookup(binding(p));assertArrayEquals((byte[])first.get("receipt_id"),(byte[])last.get("receipt_id"));assertEquals("cancelled",last.get("status"));
        reconcile(f,p);reconcile(f,p);
        for(var account:f.db().rows("SELECT consumed,reserved FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()))){assertEquals(1,number(account.get("consumed")));assertEquals(0,number(account.get("reserved")));}
    }
    @Test void newInputAfterAcceptanceBeforeToolStartCancelsOldAction()throws Exception {
        var f=fixture();var p=prepared(f,true);assertTrue(dispatch(f,p));f.provider().start(binding(p));var conversation=conversation(f,p);
        var before=f.conversations().snapshot(f.owner(),conversation);f.conversations().message(f.owner(),conversation,UUID.randomUUID().toString(),"New input supersedes the fixed plan",number(before.get("revision")),UUID.randomUUID().toString());
        reconcile(f,p);assertEquals("cancelled",f.provider().lookup(binding(p)).get("status"));
        var after=f.conversations().snapshot(f.owner(),conversation);assertEquals("awaiting_user",after.get("state"));assertTrue(((List<?>)after.get("artifacts")).isEmpty());
    }
    @Test void alreadyCalculatedOldResultCannotOverwriteNewInput()throws Exception {
        var f=fixture();var p=prepared(f,true);assertTrue(dispatch(f,p));f.provider().start(binding(p));assertEquals("succeeded",f.provider().execute(binding(p)).get("status"));var conversation=conversation(f,p);
        var before=f.conversations().snapshot(f.owner(),conversation);f.conversations().message(f.owner(),conversation,UUID.randomUUID().toString(),"New input arrived before result publication",number(before.get("revision")),UUID.randomUUID().toString());
        reconcile(f,p);var after=f.conversations().snapshot(f.owner(),conversation);assertEquals("awaiting_user",after.get("state"));assertTrue(((List<?>)after.get("artifacts")).isEmpty());
        var old=f.db().one("SELECT id,state FROM artifacts WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding(p)));assertEquals("superseded",old.get("state"));
        assertThrows(Store.Problem.class,()->f.scope().artifact(f.owner(),UUID.fromString(uuid(old.get("id")))));
    }
}
