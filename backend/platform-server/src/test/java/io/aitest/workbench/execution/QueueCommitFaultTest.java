package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** A05: only task-owned synthetic rows/connections. No network/TLS/account changes.
 * COMMIT faults are injected at the JDBC boundary of an actual MySQL transaction:
 * close before issuing COMMIT, or perform COMMIT then close and lose its response.
 * They do not claim packet-level TLS/proxy or production driver qualification. */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class QueueCommitFaultTest {
 @TempDir Path blobs;
 static final String CLAIM_SQL=ExecutionWorker.CLAIM_SQL;
 enum CommitLoss { NONE, REQUEST_NOT_SENT, COMMITTED_RESPONSE_LOST }
 static final class ProbeRollback extends RuntimeException {}
 static final class Hooks {
  final AtomicReference<CommitLoss> commitLoss=new AtomicReference<>(CommitLoss.NONE);
  final CountDownLatch ownerRead=new CountDownLatch(1),releaseOwner=new CountDownLatch(1),quotaLockAttempt=new CountDownLatch(1);
  volatile boolean holdOwner,shortLockTimeout;
  Clock clock=Clock.systemUTC();
 }
 static void await(CountDownLatch latch){try{assertTrue(latch.await(10,TimeUnit.SECONDS),"A05 deterministic checkpoint not reached");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
 static final class FaultSource extends AbstractDataSource {
  final DriverManagerDataSource delegate;final Hooks hooks;
  FaultSource(DriverManagerDataSource delegate,Hooks hooks){this.delegate=delegate;this.hooks=hooks;}
  @Override public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
  @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(delegate.getConnection(user,password));}
  Connection wrap(Connection physical)throws SQLException {
   if(hooks.shortLockTimeout)try(var s=physical.createStatement()){s.execute("SET SESSION innodb_lock_wait_timeout=1");}
   return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
    if(method.getName().equals("commit")){
     CommitLoss loss=hooks.commitLoss.getAndSet(CommitLoss.NONE);
     if(loss==CommitLoss.REQUEST_NOT_SENT){physical.close();throw new SQLNonTransientConnectionException("A05 owned connection closed before COMMIT request","08006");}
     if(loss==CommitLoss.COMMITTED_RESPONSE_LOST){physical.commit();physical.close();throw new SQLNonTransientConnectionException("A05 COMMIT durable but response unavailable","08006");}
    }
    try{return method.invoke(physical,args);}catch(InvocationTargetException e){throw e.getCause();}
   });
  }
 }
 static final class ProbeJdbc extends JdbcTemplate {
  final Hooks hooks;
  ProbeJdbc(FaultSource source,Hooks hooks){super(source);this.hooks=hooks;}
  @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
   if(sql.startsWith("SELECT occupied,slot_limit FROM active_slots"))hooks.quotaLockAttempt.countDown();
   return super.queryForList(sql,args);
  }
 }
 record Components(FaultSource source,ProbeJdbc jdbc){}
 static Components components(DriverManagerDataSource source,Hooks hooks){var wrapped=new FaultSource(source,hooks);return new Components(wrapped,new ProbeJdbc(wrapped,hooks));}
 static final class ProbeStore extends DispatchAtomicityTest.FaultStore {
  final FaultSource source;final Hooks hooks;
  ProbeStore(DriverManagerDataSource source,Hooks hooks){this(components(source,hooks),hooks);}
  ProbeStore(Components c,Hooks hooks){super(c.jdbc(),new DataSourceTransactionManager(c.source()),hooks.clock);this.source=c.source();this.hooks=hooks;}
  @Override public Map<String,Object> one(String sql,Object...args){
   var result=super.one(sql,args);
   // Pause after the initial owner/lease guard, before any reserve/write. Advancing
   // the injected clock then permits a real successor claim; a pre-guard pause
   // could pass merely because the first lease check rejects, without testing CAS.
   if(hooks.holdOwner&&sql.startsWith("SELECT published_json FROM config_objects")){hooks.holdOwner=false;hooks.ownerRead.countDown();await(hooks.releaseOwner);}
   return result;
  }
  @Override public void audit(UUID actor,String action,UUID object,Map<String,Object> detail){
   super.audit(actor,action,object,detail);
   // Keep the live service away from this fixture even if approval's COMMIT reply is lost.
   if(action.equals("approval.decide"))update("UPDATE outbox o JOIN approvals p ON p.tenant_id=o.tenant_id AND p.binding_id=o.binding_id SET o.lease_until=? WHERE p.tenant_id=? AND p.id=?",LocalDateTime.ofInstant(now().plusSeconds(600),ZoneOffset.UTC),id(TENANT),id(object));
  }
 }
 DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
 DispatchAtomicityTest.Fixture fixture(Hooks hooks)throws Exception{return helper().fixture("normal",hooks.clock,source->new ProbeStore(source,hooks));}
 ProbeStore db(DispatchAtomicityTest.Fixture f){return (ProbeStore)f.db();}
 long count(Store db,String sql,Object...args){return number(db.one(sql,args).get("n"));}
 Map<String,Object> queue(DispatchAtomicityTest.Fixture f,Map<String,Object> plan){return f.db().one("SELECT * FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(plan.get("bindingId").toString()));}
 Connection raw(ProbeStore db)throws SQLException {var c=db.source.delegate.getConnection();c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);return c;}
 static void execute(Connection c,String sql,Object...args)throws SQLException {try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}}
 static long connectionId(Connection c)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("SELECT CONNECTION_ID()")){assertTrue(r.next());return r.getLong(1);}}
 void proof(Store db,String name,Map<String,Object> detail){var value=new LinkedHashMap<String,Object>();value.put("case",name);value.putAll(detail);System.out.println("A05_PROOF "+db.json(value));}

 @Test void leaseHandoffAfterOwnerReadPreventsOldWorkerFromDispatching()throws Exception {
  var hooks=new Hooks();var clock=new MutableClock("2026-10-26T12:00:00.000000Z");hooks.clock=clock;var h=helper();var f=fixture(hooks);var db=db(f);var p=h.prepared(f,true);var q=queue(f,p);UUID nextWorker=UUID.randomUUID();var pool=Executors.newSingleThreadExecutor();
  try{
   db.update("UPDATE outbox SET state='claimed',lease_until=? WHERE tenant_id=? AND id=?",LocalDateTime.ofInstant(db.now().plusSeconds(1),ZoneOffset.UTC),id(TENANT),q.get("id"));hooks.holdOwner=true;
   var old=pool.submit(()->h.dispatch(f,p));await(hooks.ownerRead);
   clock.set("2026-10-26T12:00:02.000000Z");
   boolean taken=db.queueTransaction(()->{
    var rows=db.rows("SELECT * FROM outbox WHERE tenant_id=? AND id=? AND lease_until<=? FOR UPDATE SKIP LOCKED",id(TENANT),q.get("id"),LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC));if(rows.isEmpty())return false;
    db.update("UPDATE outbox SET state='claimed',worker_id=?,lease_until=?,attempt=attempt+1 WHERE tenant_id=? AND id=?",id(nextWorker),LocalDateTime.ofInstant(db.now().plusSeconds(600),ZoneOffset.UTC),id(TENANT),q.get("id"));return true;
   });assertTrue(taken,"new owner must commit its claim before the old worker continues");hooks.releaseOwner.countDown();boolean dispatched=old.get(10,TimeUnit.SECONDS);
   proof(db,"lease-handoff-after-owner-read",Map.of("takeoverCommitted",taken,"oldWorkerDispatched",dispatched,"newOwnerRetained",uuid(queue(f,p).get("worker_id")).equals(nextWorker.toString()),"reservations",count(db,"SELECT COUNT(*) n FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p)))));
   assertFalse(dispatched,"old owner must abort after the lease is claimed by another worker");
   assertEquals(0,count(db,"SELECT COUNT(*) n FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   assertEquals(0,count(db,"SELECT COUNT(*) n FROM admission_tickets WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   assertEquals(0,count(db,"SELECT COUNT(*) n FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())));
   assertEquals("prepared",db.one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(p))).get("state"));
   assertEquals("claimed",queue(f,p).get("state"));assertEquals(nextWorker.toString(),uuid(queue(f,p).get("worker_id")));
  }finally{hooks.releaseOwner.countDown();pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));h.close(f,p);}
 }

 List<Map<String,Object>> claimRows(ProbeStore db,List<Map<String,Object>> plans,Instant clock){
  var args=new ArrayList<Object>();args.add(id(TENANT));args.add(LocalDateTime.ofInstant(clock,ZoneOffset.UTC));for(var p:plans)args.add(id(p.get("bindingId").toString()));
  return db.rows("SELECT * FROM outbox WHERE tenant_id=? AND state IN ('queued','claimed','sending') AND (lease_until IS NULL OR lease_until<=?) AND binding_id IN ("+String.join(",",Collections.nCopies(plans.size(),"?"))+") ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",args.toArray());
 }
 @Test void lockedOldestQueueItemIsSkippedThenClaimedAfterRelease()throws Exception {
  var hooks=new Hooks();var h=helper();var f=fixture(hooks);var db=db(f);var plans=List.of(h.prepared(f,true),h.prepared(f,true),h.prepared(f,true));var sorted=new ArrayList<>(plans);sorted.sort(Comparator.comparing(p->HexFormat.of().formatHex((byte[])queue(f,p).get("id"))));Instant clock=db.now().plusSeconds(601);
  try(var lock=raw(db)){
   // Use the exact production claim query; an extra binding_id filter can change the
   // optimizer/lock range and hide filesort locking. Refuse to touch unrelated work.
   var eligible=db.rows("SELECT binding_id FROM outbox WHERE tenant_id=? AND state IN ('queued','claimed','sending') AND (lease_until IS NULL OR lease_until<=?)",id(TENANT),LocalDateTime.ofInstant(clock,ZoneOffset.UTC));
   assertEquals(new HashSet<>(plans.stream().map(p->p.get("bindingId").toString()).toList()),new HashSet<>(eligible.stream().map(r->uuid(r.get("binding_id"))).toList()),"A05 requires a serial test window with no unrelated eligible queue items");
   var oldest=queue(f,sorted.getFirst());
   try(var s=lock.prepareStatement(CLAIM_SQL)){s.setBytes(1,id(TENANT));s.setObject(2,LocalDateTime.ofInstant(clock,ZoneOffset.UTC));try(var r=s.executeQuery()){assertTrue(r.next());assertArrayEquals((byte[])oldest.get("id"),r.getBytes("id"));assertFalse(r.next());}}
   var secondRows=db.queueTransaction(()->db.rows(CLAIM_SQL,id(TENANT),LocalDateTime.ofInstant(clock,ZoneOffset.UTC)));
   proof(db,"queue-claim-lock-range-before-release",Map.of("leaderConnection",connectionId(lock),"leaderHeldBeforeCommit",true,"secondConsumerRows",secondRows.size(),"candidateRows",plans.size()));
   assertEquals(1,secondRows.size(),"LIMIT 1 claim must not lock every remaining eligible queue row");var second=secondRows.getFirst();assertEquals(uuid(queue(f,sorted.get(1)).get("id")),uuid(second.get("id")));
   lock.rollback();var after=db.queueTransaction(()->db.rows(CLAIM_SQL,id(TENANT),LocalDateTime.ofInstant(clock,ZoneOffset.UTC)));assertEquals(uuid(oldest.get("id")),uuid(after.getFirst().get("id")));
   var columns=db.rows("SHOW INDEX FROM outbox WHERE Key_name='ix_outbox'").stream().sorted(Comparator.comparingLong(r->number(r.get("Seq_in_index")))).map(r->r.get("Column_name").toString()).toList();assertEquals(List.of("state","lease_until","id"),columns);
   var explained=db.rows("EXPLAIN FORMAT=JSON "+CLAIM_SQL,id(TENANT),LocalDateTime.ofInstant(db.now(),ZoneOffset.UTC));
   proof(db,"queue-skip-then-oldest",Map.of("holderConnection",connectionId(lock),"skipped",uuid(oldest.get("id")),"selected",uuid(second.get("id")),"afterRelease",uuid(after.getFirst().get("id")),"indexColumns",columns,"explain",db.object(explained.getFirst().values().iterator().next())));
  }finally{for(var p:plans)h.close(f,p);}
 }

 @Test void rolledBackQueueClaimDoesNotConsumeLeaseAttemptOrOwner()throws Exception {
  var hooks=new Hooks();var h=helper();var f=fixture(hooks);var db=db(f);var p=h.prepared(f,true);var q=queue(f,p);
  try{
   assertThrows(ProbeRollback.class,()->db.queueTransaction(()->{var rows=claimRows(db,List.of(p),db.now().plusSeconds(601));assertEquals(1,rows.size());db.update("UPDATE outbox SET state='claimed',attempt=attempt+1,worker_id=? WHERE tenant_id=? AND id=?",id(UUID.randomUUID()),id(TENANT),q.get("id"));throw new ProbeRollback();}));
   var after=queue(f,p);assertEquals(q.get("state"),after.get("state"));assertEquals(number(q.get("attempt")),number(after.get("attempt")));assertNull(after.get("worker_id"));assertEquals(q.get("lease_until"),after.get("lease_until"));
  }finally{h.close(f,p);}
 }

 @Test void quotaRowWaitCannotBeSkippedAndTimeoutRollsBackBeforeWholeDispatchRetry()throws Exception {
  var hooks=new Hooks();var h=helper();var f=fixture(hooks);var db=db(f);var p=h.prepared(f,true);var q=queue(f,p);var pool=Executors.newSingleThreadExecutor();hooks.shortLockTimeout=true;
  try(var lock=raw(db)){
   execute(lock,"UPDATE active_slots SET occupied=occupied WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()));
   var attempt=pool.submit(()->{try{h.dispatch(f,p);return (Throwable)null;}catch(Throwable e){return e;}});await(hooks.quotaLockAttempt);
   // Queue lease commit and business reservation use different transactions. A quota wait
   // must not retain a queue row lock, nor turn the locked quota into unlimited capacity.
   try(var observer=raw(db)){execute(observer,"UPDATE outbox SET attempt=attempt WHERE tenant_id=? AND id=?",id(TENANT),q.get("id"));observer.rollback();}
   Throwable error=attempt.get(8,TimeUnit.SECONDS);assertNotNull(error,"locked quota cannot be silently skipped");SQLException sql=findSql(error);assertNotNull(sql);assertEquals(1205,sql.getErrorCode());
   assertEquals("prepared",db.one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(p))).get("state"));
   for(String table:List.of("quota_reservations","admission_tickets","fixture_submissions"))assertEquals(0,count(db,"SELECT COUNT(*) n FROM "+table+" WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   assertEquals(0,count(db,"SELECT COUNT(*) n FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())));lock.rollback();assertTrue(h.dispatch(f,p));
   assertEquals(1,count(db,"SELECT COUNT(*) n FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));assertEquals(1,number(db.one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
   proof(db,"quota-lock-timeout-whole-transaction-retry",Map.of("mysqlError",sql.getErrorCode(),"queueLockReleased",true,"rollbackAccounts",true,"retryReservations",1,"providerAcceptances",count(db,"SELECT COUNT(*) n FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p)))));
  }finally{hooks.shortLockTimeout=false;pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));h.close(f,p);}
 }
 static SQLException findSql(Throwable error){for(Throwable e=error;e!=null;e=e.getCause())if(e instanceof SQLException s)return s;return null;}

 record DeadlockOutcome(boolean committed,int code,long connection){}
 DeadlockOutcome deadlock(Connection c,byte[] first,byte[] second,UUID actor,UUID marker,CountDownLatch locked,CountDownLatch cross)throws Exception {
  try{
   execute(c,"UPDATE active_slots SET slot_limit=slot_limit+1 WHERE tenant_id=? AND subject_id=?",id(TENANT),first);
   execute(c,"INSERT INTO audit_entries VALUES(?,?,?,?,?,?,?)",id(TENANT),id(UUID.randomUUID()),id(actor),"a05.deadlock",id(marker),"{}",LocalDateTime.now(ZoneOffset.UTC));locked.countDown();await(cross);
   execute(c,"UPDATE active_slots SET slot_limit=slot_limit+1 WHERE tenant_id=? AND subject_id=?",id(TENANT),second);c.commit();return new DeadlockOutcome(true,0,connectionId(c));
  }catch(SQLException e){c.rollback();return new DeadlockOutcome(false,e.getErrorCode(),connectionId(c));}
 }
 @Test void mysqlDeadlockVictimRollsBackItsEntireOwnedTransaction()throws Exception {
  var hooks=new Hooks();var f=fixture(hooks);var db=db(f);UUID other=UUID.randomUUID(),marker=UUID.randomUUID();
  db.transaction(()->{db.update("INSERT INTO subjects VALUES(?,?)",id(other),key("a05-lock-"+other));db.update("INSERT INTO memberships(tenant_id,subject_id,role_name,state) VALUES(?,?,'member','active')",id(TENANT),id(other));db.update("INSERT INTO active_slots(tenant_id,subject_id,slot_limit) VALUES(?,?,1)",id(TENANT),id(other));return null;});
  var bothLocked=new CountDownLatch(2);var cross=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try(var a=raw(db);var b=raw(db)){
   var first=pool.submit(()->deadlock(a,id(f.owner().id()),id(other),f.owner().id(),marker,bothLocked,cross));var second=pool.submit(()->deadlock(b,id(other),id(f.owner().id()),other,marker,bothLocked,cross));await(bothLocked);cross.countDown();var left=first.get(10,TimeUnit.SECONDS);var right=second.get(10,TimeUnit.SECONDS);
   assertNotEquals(left.connection(),right.connection());assertEquals(1,(left.committed()?1:0)+(right.committed()?1:0));assertEquals(1213,left.committed()?right.code():left.code());assertEquals(1,count(db,"SELECT COUNT(*) n FROM audit_entries WHERE tenant_id=? AND object_id=? AND action_name='a05.deadlock'",id(TENANT),id(marker)));
   for(UUID subject:List.of(f.owner().id(),other)){var row=db.one("SELECT occupied,slot_limit FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(subject));assertEquals(0,number(row.get("occupied")));assertEquals(2,number(row.get("slot_limit")),"victim's first write must also roll back");}
   proof(db,"mysql-deadlock-entire-transaction-rollback",Map.of("connections",List.of(left.connection(),right.connection()),"victimError",1213,"committedAudits",1,"bothSlotLimits",2,"externalActions",0));
  }finally{cross.countDown();pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
 }

 Map<String,Object> approve(DispatchAtomicityTest.Fixture f,Map<String,Object> p,String key){return f.preparation().decide(f.reviewer(),UUID.fromString(p.get("approvalId").toString()),true,p.get("operationHash").toString(),1,"A05 fixed synthetic scope reviewed",key);}
 void commitLoss(CommitLoss mode)throws Exception {
  var hooks=new Hooks();var h=helper();var f=fixture(hooks);var db=db(f);var p=h.prepared(f,false);String command=UUID.randomUUID().toString();hooks.commitLoss.set(mode);
  try{
   RuntimeException error=assertThrows(RuntimeException.class,()->approve(f,p,command));assertNotNull(findSql(error));assertEquals(CommitLoss.NONE,hooks.commitLoss.get());
   long expected=mode==CommitLoss.COMMITTED_RESPONSE_LOST?1:0;
   assertEquals(expected,count(db,"SELECT COUNT(*) n FROM commands WHERE tenant_id=? AND subject_id=? AND kind=? AND idempotency_key=?",id(TENANT),id(f.reviewer().id()),key("approval.decide"),key(command)));
   assertEquals(expected,count(db,"SELECT COUNT(*) n FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   assertEquals(0,count(db,"SELECT COUNT(*) n FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   assertEquals(0,count(db,"SELECT COUNT(*) n FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   // Reconcile the original authenticated command namespace first. Retry uses the exact
   // old key/body, whether the observation proves committed or proves not committed.
   var prior=db.rows("SELECT id,response_json FROM commands WHERE tenant_id=? AND subject_id=? AND kind=? AND idempotency_key=?",id(TENANT),id(f.reviewer().id()),key("approval.decide"),key(command));
   var replay=approve(f,p,command);var again=approve(f,p,command);assertEquals(replay.get("commandId"),again.get("commandId"));if(!prior.isEmpty())assertEquals(uuid(prior.getFirst().get("id")),replay.get("commandId"));
   assertEquals(1,count(db,"SELECT COUNT(*) n FROM commands WHERE tenant_id=? AND subject_id=? AND kind=? AND idempotency_key=?",id(TENANT),id(f.reviewer().id()),key("approval.decide"),key(command)));
   assertEquals(1,count(db,"SELECT COUNT(*) n FROM outbox WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));assertEquals(1,count(db,"SELECT COUNT(*) n FROM audit_entries WHERE tenant_id=? AND object_id=? AND action_name='approval.decide'",id(TENANT),id(p.get("approvalId").toString())));
   proof(db,"commit-"+mode.name().toLowerCase(Locale.ROOT),Map.of("injectionBoundary","owned JDBC Connection.commit","durableBeforeReconcile",expected==1,"sameCommandReceipt",true,"outboxRows",1,"approvalAudits",1,"providerAcceptances",0,"quotaReservations",0,"packetLevelProxyValidated",false));
  }finally{hooks.commitLoss.set(CommitLoss.NONE);h.close(f,p);}
 }
 @Test void commitRequestNotSentRollsBackAndOriginalApprovalKeyRetriesOnce()throws Exception{commitLoss(CommitLoss.REQUEST_NOT_SENT);}
 @Test void commitResponseLostFindsDurableOriginalReceiptWithoutSecondOutbox()throws Exception{commitLoss(CommitLoss.COMMITTED_RESPONSE_LOST);}
 @Test void lostDispatchCommitResponseRetainsFrozenOccupationsUntilAuthoritativeClosure()throws Exception {
  var hooks=new Hooks();var h=helper();var f=fixture(hooks);var db=db(f);var p=h.prepared(f,true);hooks.commitLoss.set(CommitLoss.COMMITTED_RESPONSE_LOST);
  try{
   assertNotNull(findSql(assertThrows(RuntimeException.class,()->h.dispatch(f,p))));
   assertEquals("submitting",db.one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(p))).get("state"));
   assertEquals(1,count(db,"SELECT COUNT(*) n FROM admission_tickets WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   assertEquals(0,count(db,"SELECT COUNT(*) n FROM fixture_submissions WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))));
   var frozen=db.one("SELECT total_account_key,model_account_key,period_key FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p)));
   h.reconcile(f,p);h.reconcile(f,p);
   assertEquals("acceptance_unknown",db.one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(p))).get("state"));
   var r=db.one("SELECT * FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p)));assertEquals("reserved",r.get("state"));assertEquals(frozen.get("period_key"),r.get("period_key"));
   assertArrayEquals((byte[])frozen.get("total_account_key"),(byte[])r.get("total_account_key"));assertArrayEquals((byte[])frozen.get("model_account_key"),(byte[])r.get("model_account_key"));
   for(var account:db.rows("SELECT reserved,consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()))){assertEquals(1,number(account.get("reserved")));assertEquals(0,number(account.get("consumed")));}
   assertEquals(1,number(db.one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
   proof(db,"dispatch-commit-response-lost-keeps-unknown-occupation",Map.of("ticketRows",1,"sameFrozenAccountPair",true,"reservedPerAccount",1,"slotOccupation",1,"providerAcceptances",0,"reconcileReads",2,"reexecuted",false));
  }finally{hooks.commitLoss.set(CommitLoss.NONE);h.close(f,p);}
  assertEquals("released",db.one("SELECT state FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p))).get("state"));
  assertEquals(0,number(db.one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
 }
}
