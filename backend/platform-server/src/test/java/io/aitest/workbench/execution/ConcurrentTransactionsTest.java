package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** Separate actual MySQL transactions overlap while the leader is uncommitted.
 * Hooks are test-only; no sleeps, HTTP failpoints, new DB identities or schema changes. */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class ConcurrentTransactionsTest {
 @TempDir Path blobs;
 static class RollbackProbe extends RuntimeException{}
 record Outcome(Object value,Throwable error){}
 record Pair(Outcome leader,Outcome follower){}
 static void await(CountDownLatch latch){try{assertTrue(latch.await(10,TimeUnit.SECONDS),"deterministic transaction checkpoint not reached");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
 static class ObservedJdbc extends JdbcTemplate {
  RaceStore race;
  ObservedJdbc(DriverManagerDataSource source){super(source);}
  void observe(){if(race==null||!race.armed)return;String thread=Thread.currentThread().getName();if(!Set.of("a04-leader","a04-follower","a04-reader").contains(thread))return;
   assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
   long connection=execute((ConnectionCallback<Long>) c->{assertFalse(c.getAutoCommit());try(var s=c.createStatement();var r=s.executeQuery("SELECT CONNECTION_ID()")){assertTrue(r.next());return r.getLong(1);}});
   Long previous=race.connections.putIfAbsent(thread,connection);if(previous!=null)assertEquals(previous.longValue(),connection);
   if(thread.equals("a04-follower"))race.followerActive.countDown();if(thread.equals("a04-reader"))race.readerActive.countDown();
  }
  @Override public List<Map<String,Object>> queryForList(String sql,Object...args){if(sql.startsWith("SELECT id FROM tenants WHERE id="))observe();return super.queryForList(sql,args);}
  @Override public int update(String sql,Object...args){if(race!=null&&race.rawBindingRace&&sql.startsWith("INSERT INTO execution_bindings"))observe();return super.update(sql,args);}
 }
 static class RaceStore extends DispatchAtomicityTest.FaultStore {
  volatile boolean armed,rollbackLeader,rawBindingRace;
  final CountDownLatch leaderAtCommit=new CountDownLatch(1),followerActive=new CountDownLatch(1),readerActive=new CountDownLatch(1),release=new CountDownLatch(1);
  final Map<String,Long> connections=new ConcurrentHashMap<>();final ThreadLocal<Integer> depth=ThreadLocal.withInitial(()->0);
  final DriverManagerDataSource source;volatile Object uncommittedValue;
  RaceStore(DriverManagerDataSource source){this(source,new ObservedJdbc(source));}
  RaceStore(DriverManagerDataSource source,ObservedJdbc jdbc){super(jdbc,new DataSourceTransactionManager(source),Clock.systemUTC());this.source=source;jdbc.race=this;}
  <T>T held(T result){if(armed&&Thread.currentThread().getName().equals("a04-leader")){uncommittedValue=result;leaderAtCommit.countDown();await(release);if(rollbackLeader)throw new RollbackProbe();}return result;}
  @Override public <T>T transaction(Supplier<T> work){int d=depth.get();depth.set(d+1);try{return super.transaction(()->{T value=work.get();return d==0?held(value):value;});}finally{depth.set(d);}}
  <T>T raw(Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return tx.execute(status->held(work.get()));}
 }
 DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
 DispatchAtomicityTest.Fixture fixture()throws Exception{return helper().fixture("normal",Clock.systemUTC(),RaceStore::new);}
 RaceStore race(DispatchAtomicityTest.Fixture f){return (RaceStore)f.db();}
 UUID conversation(DispatchAtomicityTest.Fixture f){return UUID.fromString(f.conversations().create(f.owner(),f.environment(),UUID.randomUUID().toString()).get("id").toString());}
 static Outcome call(String thread,Callable<Object> work){Thread.currentThread().setName(thread);try{return new Outcome(work.call(),null);}catch(Throwable error){return new Outcome(null,error);}}
 Pair overlap(String name,RaceStore db,Callable<Object> leader,Callable<Object> follower,Runnable whileHeld)throws Exception {
  var pool=Executors.newFixedThreadPool(2);db.armed=true;
  try{
   var first=pool.submit(()->call("a04-leader",leader));await(db.leaderAtCommit);
   var second=pool.submit(()->call("a04-follower",follower));await(db.followerActive);
   assertFalse(second.isDone(),"follower cannot complete while leader's conflicting write is uncommitted");
   assertNotEquals(db.connections.get("a04-leader"),db.connections.get("a04-follower"));whileHeld.run();db.release.countDown();
   Pair pair=new Pair(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
   System.out.println("A04_PROOF "+db.json(Map.of("case",name,"leaderConnection",db.connections.get("a04-leader"),"followerConnection",db.connections.get("a04-follower"),"activeTransactionsOverlapped",true,"leaderHeldBeforeCommit",true,"followerWaited",true,"leaderRollback",db.rollbackLeader)));
   return pair;
  }finally{db.release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));db.armed=false;}
 }
 static Map<String,Object> success(Outcome outcome){assertNull(outcome.error(),()->Objects.toString(outcome.error()));return (Map<String,Object>)outcome.value();}
 static void conflict(Outcome outcome,String code){var error=assertInstanceOf(Store.Problem.class,outcome.error());assertEquals(409,error.status);assertEquals(code,error.code);}
 long count(Store db,String sql,Object...args){return number(db.one(sql,args).get("n"));}
 void messageState(DispatchAtomicityTest.Fixture f,UUID c,long before,long commands){
  assertEquals(1,count(f.db(),"SELECT COUNT(*) n FROM messages WHERE tenant_id=? AND conversation_id=?",id(TENANT),id(c)));
  assertEquals(1,number(f.db().one("SELECT input_revision FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(c)).get("input_revision")));
  assertEquals(before+1,number(f.db().one("SELECT event_sequence FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(c)).get("event_sequence")));
  assertEquals(commands,count(f.db(),"SELECT COUNT(*) n FROM commands WHERE tenant_id=? AND subject_id=? AND kind=?",id(TENANT),id(f.owner().id()),key("message.save")));
 }
 @Test void sameCommandKeyAndBodyReturnOneCommittedReceiptWhileFirstTransactionIsUncommitted()throws Exception {
  var f=fixture();var db=race(f);UUID c=conversation(f);String key=UUID.randomUUID().toString(),client=UUID.randomUUID().toString();long before=number(f.conversations().snapshot(f.owner(),c).get("eventWatermark"));
  Callable<Object> save=()->f.conversations().message(f.owner(),c,client,"same logical message",1,key);
  var pair=overlap("same-command-equal-body",db,save,save,()->assertEquals(0,count(db,"SELECT COUNT(*) n FROM messages WHERE tenant_id=? AND conversation_id=?",id(TENANT),id(c))));
  assertEquals(success(pair.leader()).get("commandId"),success(pair.follower()).get("commandId"));messageState(f,c,before,1);
 }
 @Test void sameCommandKeyAndDifferentBodiesCommitOneAndRejectOtherHash()throws Exception {
  var f=fixture();var db=race(f);UUID c=conversation(f);String key=UUID.randomUUID().toString(),client=UUID.randomUUID().toString();long before=number(f.conversations().snapshot(f.owner(),c).get("eventWatermark"));
  var pair=overlap("same-command-different-body",db,()->f.conversations().message(f.owner(),c,client,"first content",1,key),()->f.conversations().message(f.owner(),c,client,"changed content",1,key),()->{});
  success(pair.leader());conflict(pair.follower(),"IDEMPOTENCY_CONFLICT");messageState(f,c,before,1);
 }
 @Test void sameClientMessageIdAndDifferentKeysSaveExactlyOneMessage()throws Exception {
  var f=fixture();var db=race(f);UUID c=conversation(f);String client=UUID.randomUUID().toString();long before=number(f.conversations().snapshot(f.owner(),c).get("eventWatermark"));
  var pair=overlap("same-message-equal-text",db,()->f.conversations().message(f.owner(),c,client,"same client identity",1,UUID.randomUUID().toString()),()->f.conversations().message(f.owner(),c,client,"same client identity",1,UUID.randomUUID().toString()),()->{});
  assertEquals(1,number(success(pair.leader()).get("inputRevision")));assertEquals(1,number(success(pair.follower()).get("inputRevision")));messageState(f,c,before,2);
 }
 @Test void sameClientMessageIdAndChangedTextCannotOverwriteCommittedMessage()throws Exception {
  var f=fixture();var db=race(f);UUID c=conversation(f);String client=UUID.randomUUID().toString();long before=number(f.conversations().snapshot(f.owner(),c).get("eventWatermark"));
  var pair=overlap("same-message-changed-text",db,()->f.conversations().message(f.owner(),c,client,"first text",1,UUID.randomUUID().toString()),()->f.conversations().message(f.owner(),c,client,"second text",1,UUID.randomUUID().toString()),()->{});
  success(pair.leader());conflict(pair.follower(),"CLIENT_MESSAGE_CONFLICT");messageState(f,c,before,1);
 }
 @Test void differentMessagesWithSameRevisionRequireExplicitConflictRebase()throws Exception {
  var f=fixture();var db=race(f);UUID c=conversation(f);long before=number(f.conversations().snapshot(f.owner(),c).get("eventWatermark"));
  var pair=overlap("distinct-message-same-revision",db,()->f.conversations().message(f.owner(),c,UUID.randomUUID().toString(),"first text",1,UUID.randomUUID().toString()),()->f.conversations().message(f.owner(),c,UUID.randomUUID().toString(),"second text",1,UUID.randomUUID().toString()),()->{});
  success(pair.leader());conflict(pair.follower(),"REVISION_CONFLICT");messageState(f,c,before,1);
 }
 @Test void rolledBackLeaderDoesNotConsumeMessageSequenceOrCommandKeyBeforeConcurrentRetry()throws Exception {
  var f=fixture();var db=race(f);UUID c=conversation(f);String client=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();long before=number(f.conversations().snapshot(f.owner(),c).get("eventWatermark"));db.rollbackLeader=true;
  Callable<Object> save=()->f.conversations().message(f.owner(),c,client,"retry rolled-back message",1,key);
  var pair=overlap("rollback-and-concurrent-retry",db,save,save,()->assertEquals(before,number(db.one("SELECT event_sequence FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(c)).get("event_sequence"))));
  assertInstanceOf(RollbackProbe.class,pair.leader().error());var receipt=success(pair.follower());assertNotEquals(((Map<?,?>)db.uncommittedValue).get("commandId"),receipt.get("commandId"));messageState(f,c,before,1);
  var replay=f.conversations().message(f.owner(),c,client,"retry rolled-back message",1,key);assertEquals(receipt.get("commandId"),replay.get("commandId"));messageState(f,c,before,1);
 }
 @Test void sameNodeRunUniqueIndexAllowsOneOpenOwnerAndRetainsClosedHistory()throws Exception {
  var f=fixture();var db=race(f);UUID c=conversation(f),node=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),next=UUID.randomUUID();
  db.transaction(()->{db.update("INSERT INTO node_runs VALUES(?,?,?,?)",id(TENANT),id(node),id(c),"blocked");return null;});db.rawBindingRace=true;
  java.util.function.Function<UUID,Object> insert=value->{db.update("INSERT INTO execution_bindings(tenant_id,id,node_run_id,conversation_id,state,spec_hash) VALUES(?,?,?,?,?,?)",id(TENANT),id(value),id(node),id(c),"prepared",new byte[32]);return value;};
  try{
   var pair=overlap("same-node-open-binding-unique-index",db,()->db.raw(()->insert.apply(a)),()->db.raw(()->insert.apply(b)),()->assertEquals(0,count(db,"SELECT COUNT(*) n FROM execution_bindings WHERE tenant_id=? AND node_run_id=?",id(TENANT),id(node))));
   assertNull(pair.leader().error());assertInstanceOf(org.springframework.dao.DuplicateKeyException.class,pair.follower().error());assertEquals(1,count(db,"SELECT COUNT(*) n FROM execution_bindings WHERE tenant_id=? AND node_run_id=? AND closed_at IS NULL",id(TENANT),id(node)));
   // These constraint probes were never approved, queued or sent to a provider.
   closeUnsentProbe(db,a);db.transaction(()->insert.apply(next));assertEquals(2,count(db,"SELECT COUNT(*) n FROM execution_bindings WHERE tenant_id=? AND node_run_id=?",id(TENANT),id(node)));assertEquals(1,count(db,"SELECT COUNT(*) n FROM execution_bindings WHERE tenant_id=? AND node_run_id=? AND closed_at IS NULL",id(TENANT),id(node)));
  }finally{for(UUID value:List.of(a,b,next))closeUnsentProbe(db,value);}
 }
 void closeUnsentProbe(Store db,UUID binding){db.transaction(()->{assertEquals(0,count(db,"SELECT COUNT(*) n FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding)));assertEquals(0,count(db,"SELECT COUNT(*) n FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(binding)));db.update("UPDATE execution_bindings SET state='rejected',closed_at=? WHERE tenant_id=? AND id=? AND closed_at IS NULL AND accepted_at IS NULL",LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC),id(TENANT),id(binding));return null;});}
 @Test void concurrentTerminalDeliveriesConsumeFrozenPairAndReleaseSlotOnlyOnce()throws Exception {
  var h=helper();var f=fixture();var db=race(f);var p=h.prepared(f,true);
  try{
   assertTrue(h.dispatch(f,p));f.provider().start(h.binding(p));var fact=f.provider().execute(h.binding(p));long before=number(f.conversations().snapshot(f.owner(),h.conversation(f,p)).get("eventWatermark"));
   Callable<Object> finish=()->{EventTerminalConsistencyTest.finish(f.worker(),h.binding(p),fact);return Map.of("finished",true);};
   var pair=overlap("same-terminal-concurrent-settlement",db,finish,finish,()->{
    assertEquals("reserved",db.one("SELECT state FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))).get("state"));assertEquals(0,count(db,"SELECT COUNT(*) n FROM provider_events WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   });success(pair.leader());success(pair.follower());
   for(var account:db.rows("SELECT consumed,reserved FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()))){assertEquals(1,number(account.get("consumed")));assertEquals(0,number(account.get("reserved")));}
   assertEquals(0,number(db.one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));assertEquals(1,count(db,"SELECT COUNT(*) n FROM provider_events WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));assertEquals(1,count(db,"SELECT COUNT(*) n FROM artifacts WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   assertEquals(before+2,number(f.conversations().snapshot(f.owner(),h.conversation(f,p)).get("eventWatermark")));
  }finally{h.close(f,p);}
 }
 @Test void lateEventCommitAndConcurrentSnapshotResumeAtCommittedContiguousWatermark()throws Exception {
  var f=fixture();var db=race(f);UUID c=conversation(f);var baseline=f.conversations().snapshot(f.owner(),c);long w=number(baseline.get("eventWatermark"));var pool=Executors.newFixedThreadPool(3);db.armed=true;
  try{
   var first=pool.submit(()->call("a04-leader",()->{db.transaction(()->{db.event(c,"probe.late",Map.of("value","first uncommitted"));return null;});return Map.of();}));await(db.leaderAtCommit);
   var second=pool.submit(()->call("a04-follower",()->{db.event(c,"probe.concurrent",Map.of("value","second"));return Map.of();}));await(db.followerActive);
   var snapshot=pool.submit(()->call("a04-reader",()->f.conversations().snapshot(f.owner(),c)));await(db.readerActive);assertFalse(second.isDone());assertFalse(snapshot.isDone());
   assertEquals(w,number(db.one("SELECT event_sequence FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(c)).get("event_sequence")));assertEquals(0,count(db,"SELECT COUNT(*) n FROM platform_events WHERE tenant_id=? AND conversation_id=? AND sequence>?",id(TENANT),id(c),w));db.release.countDown();
   success(first.get(10,TimeUnit.SECONDS));success(second.get(10,TimeUnit.SECONDS));var captured=success(snapshot.get(10,TimeUnit.SECONDS));long watermark=number(captured.get("eventWatermark"));assertTrue(watermark==w+1||watermark==w+2);
   var all=f.conversations().events(f.owner(),c,baseline.get("resumeCursor").toString(),1);var firstPage=(List<Map<String,Object>>)all.get("events");assertEquals(w+1,number(firstPage.getFirst().get("sequence")));assertEquals(true,all.get("hasMore"));
   var rest=f.conversations().events(f.owner(),c,all.get("cursor").toString(),1);assertEquals(w+2,number(((List<Map<String,Object>>)rest.get("events")).getFirst().get("sequence")));assertEquals(false,rest.get("hasMore"));
   var resumed=f.conversations().events(f.owner(),c,captured.get("resumeCursor").toString(),100);var rows=(List<Map<String,Object>>)resumed.get("events");assertEquals(w+2-watermark,rows.size());if(!rows.isEmpty())assertEquals(watermark+1,number(rows.getFirst().get("sequence")));
   System.out.println("A04_PROOF "+db.json(Map.of("case","late-event-commit-and-snapshot","connections",db.connections,"before",w,"snapshotWatermark",watermark,"finalWatermark",w+2,"pagedContinuation",true)));
  }finally{db.release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));db.armed=false;}
 }
}
