package io.aitest.workbench.execution;

import io.aitest.contract.CanonicalJson;
import io.aitest.workbench.api.WorkbenchApi;
import io.aitest.workbench.configuration.ConfigurationService;
import io.aitest.workbench.identity.ContentReview;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.material.Materials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** A06: actual, distinct MySQL transactions overlap before COMMIT. All hooks are test-only.
 * Each case creates its own synthetic subject/environment; the service's queue is leased away
 * by DispatchAtomicityTest until this test explicitly closes its own binding. */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class AdmissionRaceTest {
    @TempDir Path blobs;
    enum Change { ENVIRONMENT_PUBLICATION, DEPENDENCY_PUBLICATION, GRANT_REVOCATION, QUOTA_REDUCTION, STOP }
    enum Boundary { DISPATCH, FIRST_ACCEPTANCE }
    record Outcome(Object value,Throwable error){}
    record Pair(Outcome leader,Outcome follower){}

    static void await(CountDownLatch latch){
        try{assertTrue(latch.await(10,TimeUnit.SECONDS),"A06 transaction checkpoint not reached");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    static class ObservedJdbc extends JdbcTemplate {
        GuardStore guard;
        ObservedJdbc(DriverManagerDataSource source){super(source);}
        @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
            if(guard!=null&&guard.armed&&sql.startsWith("SELECT id FROM tenants WHERE id=")){
                String thread=Thread.currentThread().getName();
                if(Set.of("a06-leader","a06-follower").contains(thread)){
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    long connection=execute((ConnectionCallback<Long>)c->{
                        assertFalse(c.getAutoCommit());
                        try(var statement=c.createStatement();var result=statement.executeQuery("SELECT CONNECTION_ID()")){
                            assertTrue(result.next());return result.getLong(1);
                        }
                    });
                    Long prior=guard.connections.putIfAbsent(thread,connection);
                    if(prior!=null)assertEquals(prior.longValue(),connection);
                    if(thread.equals("a06-follower"))guard.followerActive.countDown();
                }
            }
            return super.queryForList(sql,args);
        }
    }
    static class GuardStore extends DispatchAtomicityTest.FaultStore {
        volatile boolean armed;
        Supplier<String> snapshotAtLeaderCommit;volatile String leaderState;
        final CountDownLatch leaderAtCommit=new CountDownLatch(1),followerActive=new CountDownLatch(1),release=new CountDownLatch(1);
        final Map<String,Long> connections=new ConcurrentHashMap<>();
        final ThreadLocal<Integer> depth=ThreadLocal.withInitial(()->0);
        GuardStore(DriverManagerDataSource source,Clock clock){this(source,clock,new ObservedJdbc(source));}
        private GuardStore(DriverManagerDataSource source,Clock clock,ObservedJdbc jdbc){
            super(jdbc,new DataSourceTransactionManager(source),clock);jdbc.guard=this;
        }
        @Override public <T>T transaction(Supplier<T> work){
            int previous=depth.get();depth.set(previous+1);
            try{return super.transaction(()->{
                T value=work.get();
                if(armed&&previous==0&&Thread.currentThread().getName().equals("a06-leader")){
                    if(snapshotAtLeaderCommit!=null)leaderState=snapshotAtLeaderCommit.get();
                    leaderAtCommit.countDown();await(release);
                }
                return value;
            });}finally{depth.set(previous);}
        }
    }
    DispatchAtomicityTest helper(){var helper=new DispatchAtomicityTest();helper.blobs=blobs;return helper;}
    DispatchAtomicityTest.Fixture fixture(Clock clock)throws Exception {
        return helper().fixture("normal",clock,source->new GuardStore(source,clock));
    }
    GuardStore guard(DispatchAtomicityTest.Fixture fixture){return (GuardStore)fixture.db();}
    static Outcome call(String name,Callable<Object> action){
        Thread.currentThread().setName(name);
        try{return new Outcome(action.call(),null);}catch(Throwable error){return new Outcome(null,error);}
    }
    Pair overlap(String name,GuardStore db,Callable<Object> leader,Callable<Object> follower,Runnable whileHeld)throws Exception {
        var pool=Executors.newFixedThreadPool(2);db.armed=true;
        try{
            var first=pool.submit(()->call("a06-leader",leader));
            try{await(db.leaderAtCommit);}catch(AssertionError checkpoint){
                if(first.isDone()){var stopped=first.get();if(stopped.error()!=null)throw new AssertionError("A06 leader failed before COMMIT checkpoint: "+name,stopped.error());}
                throw checkpoint;
            }
            var second=pool.submit(()->call("a06-follower",follower));await(db.followerActive);
            assertFalse(second.isDone(),"follower cannot cross the held admission guard");
            assertNotNull(db.connections.get("a06-leader"));assertNotNull(db.connections.get("a06-follower"));
            assertNotEquals(db.connections.get("a06-leader"),db.connections.get("a06-follower"));
            whileHeld.run();db.release.countDown();
            var pair=new Pair(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            System.out.println("A06_PROOF "+db.json(Map.of("case",name,"leaderConnection",db.connections.get("a06-leader"),"followerConnection",db.connections.get("a06-follower"),"activeTransactionsOverlapped",true,"leaderHeldBeforeCommit",true,"followerWaited",true)));
            return pair;
        }finally{db.release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));db.armed=false;}
    }
    static void success(Outcome outcome){assertNull(outcome.error(),()->Objects.toString(outcome.error()));}
    static void rejected(Outcome outcome,String code){
        var error=assertInstanceOf(Store.Problem.class,outcome.error());assertEquals(409,error.status);assertEquals(code,error.code);
    }
    void rejectedAtBoundary(Outcome outcome,String code,Boundary boundary,DispatchAtomicityTest.Fixture fixture,Map<String,Object> plan)throws Exception {
        if(boundary==Boundary.FIRST_ACCEPTANCE||outcome.error()!=null){rejected(outcome,code);return;}
        assertEquals(Boolean.FALSE,outcome.value(),"a rejected dispatch must return exactly false");
        var queue=fixture.db().one("SELECT worker_id,lease_until FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(helper().binding(plan)));
        var field=ExecutionWorker.class.getDeclaredField("worker");field.setAccessible(true);
        boolean ownerMatches=Arrays.equals((byte[])queue.get("worker_id"),id((UUID)field.get(fixture.worker())));
        boolean leaseValid=queue.get("lease_until")!=null&&fixture.db().now().isBefore(instant(queue.get("lease_until")));
        assertFalse(ownerMatches&&leaseValid,"false must be justified by lost or expired sending authority, not an unexplained refusal");
    }
    String admissionState(DispatchAtomicityTest.Fixture fixture,Map<String,Object> plan){
        var db=fixture.db();UUID binding=helper().binding(plan),conversation=helper().conversation(fixture,plan);
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("binding",db.rows("SELECT state,stopped,HEX(spec_hash) specHash,execution_spec_json,quota_snapshot_json,capability_snapshot_json,CAST(accepted_at AS CHAR) acceptedAt,CAST(closed_at AS CHAR) closedAt FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding)));
        snapshot.put("node",db.rows("SELECT n.state FROM node_runs n JOIN execution_bindings b ON b.tenant_id=n.tenant_id AND b.node_run_id=n.id WHERE b.tenant_id=? AND b.id=?",id(TENANT),id(binding)));
        snapshot.put("approval",db.rows("SELECT state,revision,HEX(operation_hash) operationHash FROM approvals WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding)));
        snapshot.put("queue",db.rows("SELECT state,HEX(worker_id) owner,CAST(lease_until AS CHAR) lease,attempt FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding)));
        snapshot.put("conversation",db.rows("SELECT state,revision,input_revision,event_sequence FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(conversation)));
        snapshot.put("events",db.rows("SELECT sequence,event_type,SHA2(CAST(payload AS CHAR),256) payloadHash FROM platform_events WHERE tenant_id=? AND conversation_id=? ORDER BY sequence",id(TENANT),id(conversation)));
        snapshot.put("audit",db.rows("SELECT HEX(id) id,action_name,detail_json FROM audit_entries WHERE tenant_id=? AND object_id=? ORDER BY id",id(TENANT),id(binding)));
        return CanonicalJson.hash(db.json(snapshot));
    }
    long count(Store db,String sql,Object...args){return number(db.one(sql,args).get("n"));}
    void publish(DispatchAtomicityTest.Fixture fixture,UUID object){
        var db=fixture.db();var row=db.one("SELECT revision,published_json FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),id(object));
        var dependencies=new TreeMap<String,Long>();
        db.object(db.object(row.get("published_json")).getOrDefault("dependencyRevisions",Map.of())).forEach((key,value)->dependencies.put(key,number(value)));
        new ConfigurationService(db,fixture.scope()).publish(fixture.scope().actor("admin"),List.of(new ConfigurationService.Publication(object.toString(),number(row.get("revision")),dependencies)),UUID.randomUUID().toString());
    }
    Object change(Change change,DispatchAtomicityTest.Fixture fixture,Map<String,Object> plan)throws Exception {
        var db=fixture.db();var config=new ConfigurationService(db,fixture.scope());var admin=fixture.scope().actor("admin");
        switch(change){
            case ENVIRONMENT_PUBLICATION -> publish(fixture,fixture.environment());
            case DEPENDENCY_PUBLICATION -> {
                var environment=db.one("SELECT published_json FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),id(fixture.environment()));
                publish(fixture,UUID.fromString(db.object(environment.get("published_json")).get("modelRef").toString()));
            }
            case GRANT_REVOCATION -> config.revoke(admin,fixture.owner().alias(),fixture.environment(),UUID.randomUUID().toString());
            case QUOTA_REDUCTION -> {
                // Exercise the real endpoint business command, including its revision increment.
                var request=new MockHttpServletRequest();request.setAttribute("actor",admin);
                var materials=new Materials(db,fixture.scope(),blobs.toString());
                var api=new WorkbenchApi(db,fixture.scope(),config,fixture.conversations(),fixture.preparation(),fixture.worker(),materials,new ContentReview(db,fixture.scope()),new io.aitest.workbench.handover.HandoverService(db,fixture.scope(),materials));
                try{api.quota(request,new WorkbenchApi.Quota(fixture.owner().alias(),0,0,fixture.scope().actor(fixture.owner().alias()).revision(),UUID.randomUUID().toString()));}
                finally{api.close();}
            }
            case STOP -> fixture.worker().stop(fixture.owner(),helper().conversation(fixture,plan),UUID.randomUUID().toString());
        }
        return Map.of("change",change.name());
    }
    String expected(Change change,Boundary boundary){
        return switch(change){
            case ENVIRONMENT_PUBLICATION -> "CONFIGURATION_CHANGED";
            case DEPENDENCY_PUBLICATION -> "DEPENDENCY_REVISION_CONFLICT";
            case QUOTA_REDUCTION -> "AUTHORIZATION_CHANGED";
            case GRANT_REVOCATION,STOP -> boundary==Boundary.DISPATCH?"PLAN_STALE_OR_STOPPED":"ADMISSION_BLOCKED";
        };
    }
    void noAcceptance(DispatchAtomicityTest.Fixture fixture,Map<String,Object> plan,boolean reserved){
        var db=fixture.db();UUID binding=helper().binding(plan);
        assertEquals(0,count(db,"SELECT COUNT(*) n FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding)));
        assertNull(db.one("SELECT accepted_at,closed_at FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding)).get("accepted_at"));
        assertEquals(reserved?1:0,count(db,"SELECT COUNT(*) n FROM admission_tickets WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding)));
        assertEquals(reserved?1:0,count(db,"SELECT COUNT(*) n FROM quota_reservations WHERE tenant_id=? AND binding_id=? AND state='reserved'",id(TENANT),id(binding)));
        assertEquals(reserved?1:0,count(db,"SELECT COUNT(*) n FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding)),"no hidden released/settled reservation may be created");
        assertEquals(reserved?2:0,count(db,"SELECT COUNT(*) n FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(fixture.owner().id())));
        for(String table:List.of("fixture_events","fixture_artifacts","fixture_commands","provider_events","artifacts"))assertEquals(0,count(db,"SELECT COUNT(*) n FROM "+table+" WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding)),table+" must contain no execution or import facts");
        if(!reserved){var bindingState=db.one("SELECT execution_spec_json,quota_snapshot_json,capability_snapshot_json FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(binding));for(Object value:bindingState.values())assertNull(value,"rejected dispatch cannot freeze an execution specification");}
        for(var account:db.rows("SELECT reserved,consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(fixture.owner().id()))){
            assertEquals(reserved?1:0,number(account.get("reserved")));assertEquals(0,number(account.get("consumed")));
        }
        assertEquals(reserved?1:0,number(db.one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(fixture.owner().id())).get("occupied")));
    }
    void close(DispatchAtomicityTest.Fixture fixture,Map<String,Object> plan,boolean revoked)throws Exception {
        if(revoked||!Boolean.TRUE.equals(fixture.db().one("SELECT active FROM grants WHERE tenant_id=? AND subject_id=? AND environment_id=?",id(TENANT),id(fixture.owner().id()),id(fixture.environment())).get("active")))new ConfigurationService(fixture.db(),fixture.scope()).grant(fixture.scope().actor("admin"),fixture.owner().alias(),fixture.environment(),"member",UUID.randomUUID().toString());
        helper().close(fixture,plan);
        assertNotNull(fixture.db().one("SELECT closed_at FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(helper().binding(plan))).get("closed_at"));
        assertEquals("done",fixture.db().one("SELECT state FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(helper().binding(plan))).get("state"));
        assertEquals(0,count(fixture.db(),"SELECT COUNT(*) n FROM fixture_submissions WHERE tenant_id=? AND binding_id=? AND execution_attempts<>0",id(TENANT),id(helper().binding(plan))),"cleanup must not start a rejected execution");
        assertEquals(0,number(fixture.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(fixture.owner().id())).get("occupied")));
        for(var account:fixture.db().rows("SELECT reserved FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(fixture.owner().id())))assertEquals(0,number(account.get("reserved")));
    }
    UUID ownDispatchOutbox(DispatchAtomicityTest.Fixture fixture,Map<String,Object> plan)throws Exception {
        var workerField=ExecutionWorker.class.getDeclaredField("worker");workerField.setAccessible(true);
        UUID worker=(UUID)workerField.get(fixture.worker());
        var row=fixture.db().one("SELECT id FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(helper().binding(plan)));
        fixture.db().update("UPDATE outbox SET worker_id=? WHERE tenant_id=? AND id=?",id(worker),id(TENANT),row.get("id"));
        return UUID.fromString(uuid(row.get("id")));
    }
    Object dispatchOwned(DispatchAtomicityTest.Fixture fixture,Map<String,Object> plan,UUID outbox)throws Exception {
        var dispatch=ExecutionWorker.class.getDeclaredMethod("prepareDispatch",UUID.class,UUID.class);dispatch.setAccessible(true);
        try{return dispatch.invoke(fixture.worker(),helper().binding(plan),outbox);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException cause)throw cause;throw e;}
    }
    static Stream<Arguments> changesAndBoundaries(){
        return Arrays.stream(Change.values()).flatMap(change->Arrays.stream(Boundary.values()).map(boundary->Arguments.of(change,boundary)));
    }
    @ParameterizedTest(name="{0} commits before {1}")
    @MethodSource("changesAndBoundaries")
    void committedChangesFenceTheNextAdmissionBoundary(Change change,Boundary boundary)throws Exception {
        var helper=helper();var fixture=fixture(Clock.systemUTC());var plan=helper.prepared(fixture,true);boolean reserved=boundary==Boundary.FIRST_ACCEPTANCE;
        try{
            if(reserved)assertTrue(helper.dispatch(fixture,plan));
            UUID outbox=reserved?null:ownDispatchOutbox(fixture,plan);
            guard(fixture).snapshotAtLeaderCommit=()->admissionState(fixture,plan);
            var pair=overlap(change+"-before-"+boundary,guard(fixture),()->change(change,fixture,plan),
                ()->reserved?fixture.provider().start(helper.binding(plan)):dispatchOwned(fixture,plan,outbox),()->{});
            success(pair.leader());rejectedAtBoundary(pair.follower(),expected(change,boundary),boundary,fixture,plan);noAcceptance(fixture,plan,reserved);
            assertEquals(guard(fixture).leaderState,admissionState(fixture,plan),"rejected follower must not change binding, node, approval, queue, conversation, events or audit");
        }finally{close(fixture,plan,change==Change.GRANT_REVOCATION);}
    }
    void expiryWhileWaiting(Boundary boundary)throws Exception {
        var helper=helper();var clock=new MutableClock("2036-10-26T12:00:00.000001Z");var fixture=fixture(clock);var plan=helper.prepared(fixture,true);boolean reserved=boundary==Boundary.FIRST_ACCEPTANCE;
        try{
            if(reserved)assertTrue(helper.dispatch(fixture,plan));
            UUID outbox=reserved?null:ownDispatchOutbox(fixture,plan);
            // Keep the dispatch lease beyond the approval deadline to exercise expiry itself.
            if(!reserved)fixture.db().update("UPDATE outbox SET lease_until=? WHERE tenant_id=? AND id=?",java.time.LocalDateTime.parse("2036-10-26T12:10:01.000001"),id(TENANT),id(outbox));
            guard(fixture).snapshotAtLeaderCommit=()->admissionState(fixture,plan);
            var pair=overlap("expiry-while-waiting-for-"+boundary,guard(fixture),()->fixture.db().transaction(()->Map.of("guardHeld",true)),
                ()->reserved?fixture.provider().start(helper.binding(plan)):dispatchOwned(fixture,plan,outbox),
                ()->clock.set("2036-10-26T12:10:00.000001Z"));
            success(pair.leader());rejectedAtBoundary(pair.follower(),"APPROVAL_EXPIRED",boundary,fixture,plan);noAcceptance(fixture,plan,reserved);
            assertEquals(guard(fixture).leaderState,admissionState(fixture,plan),"expiry rejection must not write dispatch state");
        }finally{close(fixture,plan,false);}
    }
    @Test void dispatchChecksExclusiveExpiryAfterWaitingForTheGuard()throws Exception {expiryWhileWaiting(Boundary.DISPATCH);}
    @Test void firstAcceptanceChecksExclusiveExpiryAfterWaitingForTheGuard()throws Exception {expiryWhileWaiting(Boundary.FIRST_ACCEPTANCE);}
    @Test void acceptedReceiptSurvivesConcurrentRevocationButNoNextToolStarts()throws Exception {
        var helper=helper();var fixture=fixture(Clock.systemUTC());var plan=helper.prepared(fixture,true);boolean revoked=false;
        try{
            assertTrue(helper.dispatch(fixture,plan));
            var pair=overlap("first-acceptance-before-revocation",guard(fixture),()->fixture.provider().start(helper.binding(plan)),
                ()->change(Change.GRANT_REVOCATION,fixture,plan),()->{});
            success(pair.leader());success(pair.follower());revoked=true;
            var first=(Map<?,?>)pair.leader().value();var replay=fixture.provider().start(helper.binding(plan));
            assertArrayEquals((byte[])first.get("receipt_id"),(byte[])replay.get("receipt_id"));
            assertEquals("cancelled",fixture.provider().execute(helper.binding(plan)).get("status"));
            assertEquals(0,count(fixture.db(),"SELECT COUNT(*) n FROM artifacts WHERE tenant_id=? AND binding_id=?",id(TENANT),id(helper.binding(plan))));
            helper.reconcile(fixture,plan);helper.reconcile(fixture,plan);
            for(var account:fixture.db().rows("SELECT consumed,reserved FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(fixture.owner().id()))){assertEquals(1,number(account.get("consumed")));assertEquals(0,number(account.get("reserved")));}
        }finally{close(fixture,plan,revoked);}
    }
}
