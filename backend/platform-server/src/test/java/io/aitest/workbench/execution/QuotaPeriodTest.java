package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.aitest.workbench.infrastructure.Store.*;

@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class QuotaPeriodTest {
    @TempDir Path blobs;
    DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
    Map<String,Object> account(DispatchAtomicityTest.Fixture f,String name,String period){return f.db().one("SELECT consumed,reserved FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND account_key=? AND period_key=?",id(TENANT),id(f.owner().id()),key(name),period);}
    void balance(DispatchAtomicityTest.Fixture f,String key,String period,long consumed,long reserved){var row=account(f,key,period);assertEquals(consumed,number(row.get("consumed")),key+" consumed");assertEquals(reserved,number(row.get("reserved")),key+" reserved");}
    @Test void differentModelConnectionBindingsCannotBypassOneTotalOrCreateItTwice()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T23:59:59.999999Z");var f=h.fixture("normal",clock);var a=h.prepared(f,true);var b=h.prepared(f,true);var ledger=new QuotaLedger(f.db());
        f.db().update("UPDATE quota_policies SET turn_limit=1 WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()));f.db().update("UPDATE active_slots SET slot_limit=2 WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id()));
        // Two synthetic connection/model selections call the same user ledger; no real backend.
        var selections=Map.of("connection-A","fixture-A","connection-B","fixture-B");var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->{gate.await();return ledger.reserve(h.binding(a),f.owner().id(),selections.get("connection-A"),100);});
            var second=pool.submit(()->{gate.await();return ledger.reserve(h.binding(b),f.owner().id(),selections.get("connection-B"),100);});gate.countDown();
            boolean wonA=first.get(10,TimeUnit.SECONDS),wonB=second.get(10,TimeUnit.SECONDS);assertNotEquals(wonA,wonB);
            balance(f,"total","2026-10-26",0,1);balance(f,"model:fixture-A","2026-10-26",0,wonA?1:0);balance(f,"model:fixture-B","2026-10-26",0,wonB?1:0);
            assertEquals(1,f.db().rows("SELECT account_key FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND account_key=?",id(TENANT),id(f.owner().id()),key("total")).size());
        }finally{pool.shutdownNow();h.close(f,a);h.close(f,b);}
        balance(f,"total","2026-10-26",0,0);
    }
    @Test void frozenPairAndPeriodSurviveMidnightAndRejectChangedModel()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T23:59:59.999999Z");var f=h.fixture("normal",clock);var p=h.prepared(f,true);var ledger=new QuotaLedger(f.db());
        try{
            assertTrue(ledger.reserve(h.binding(p),f.owner().id(),"fixture-A",100));clock.set("2026-10-27T00:00:00.000001Z");
            assertTrue(ledger.reserve(h.binding(p),f.owner().id(),"fixture-A",100));
            var error=assertThrows(Store.Problem.class,()->ledger.reserve(h.binding(p),f.owner().id(),"fixture-B",100));assertEquals("QUOTA_BINDING_CONFLICT",error.code);
            var frozen=f.db().one("SELECT period_key,total_account_key,model_account_key FROM quota_reservations WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p)));
            assertEquals("2026-10-26",frozen.get("period_key"));assertArrayEquals(key("total"),(byte[])frozen.get("total_account_key"));assertArrayEquals(key("model:fixture-A"),(byte[])frozen.get("model_account_key"));
            balance(f,"total","2026-10-26",0,1);assertTrue(f.db().rows("SELECT account_key FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND period_key='2026-10-27'",id(TENANT),id(f.owner().id())).isEmpty());
        }finally{h.close(f,p);}
        balance(f,"total","2026-10-26",0,0);
    }
    @Test void oldUnknownReservationIsNotReleasedAtUtcRollover()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T23:59:59.999999Z");var f=h.fixture("normal",clock);var a=h.prepared(f,true);var b=h.prepared(f,true);
        try{
            assertTrue(h.dispatch(f,a));h.reconcile(f,a);clock.set("2026-10-27T00:00:00.000001Z");h.reconcile(f,a);
            assertEquals("acceptance_unknown",f.db().one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(a))).get("state"));
            assertFalse(new QuotaLedger(f.db()).reserve(h.binding(b),f.owner().id(),"fixture-B",100));
            balance(f,"total","2026-10-26",0,1);balance(f,"model:fixture-v1","2026-10-26",0,1);balance(f,"total","2026-10-27",0,0);
            assertEquals(1,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
        }finally{h.close(f,a);h.close(f,b);}
        balance(f,"total","2026-10-26",0,0);
    }
    @Test void acceptanceAndRepeatedTerminalAfterMidnightSettleOnlyTheOldPair()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T23:59:59.999999Z");var f=h.fixture("normal",clock);var p=h.prepared(f,true);
        try{
            assertTrue(h.dispatch(f,p));clock.set("2026-10-27T00:00:00.000001Z");f.provider().start(h.binding(p));h.reconcile(f,p);h.reconcile(f,p);
            balance(f,"total","2026-10-26",1,0);balance(f,"model:fixture-v1","2026-10-26",1,0);
            assertTrue(f.db().rows("SELECT account_key FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND period_key='2026-10-27'",id(TENANT),id(f.owner().id())).isEmpty());
            assertEquals(0,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
        }finally{h.close(f,p);}
    }
    static class RollbackProbe extends RuntimeException{}
    @Test void legacyUnresolvedPairCannotConsumeOrReleaseUnknownBalances()throws Exception {
        var h=helper();var f=h.fixture();var p=h.prepared(f,true);var ledger=new QuotaLedger(f.db());
        try{
            assertThrows(RollbackProbe.class,()->f.db().transaction(()->{
                ledger.reserve(h.binding(p),f.owner().id(),"fixture-v1",100);
                f.db().update("UPDATE quota_reservations SET total_account_key=NULL,model_account_key=NULL WHERE tenant_id=? AND binding_id=?",id(TENANT),id(h.binding(p)));
                for(Runnable action:List.<Runnable>of(()->ledger.consume(h.binding(p)),()->ledger.close(h.binding(p)))){
                    var error=assertThrows(Store.Problem.class,action::run);assertEquals("QUOTA_SCOPE_UNKNOWN",error.code);
                }
                balance(f,"total",f.db().quotaPeriod(),0,1);assertEquals(1,number(f.db().one("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.owner().id())).get("occupied")));
                throw new RollbackProbe();
            }));
        }finally{h.close(f,p);}
    }
    @Test void safeIntegerMaximumBlocksAnotherReservationWithoutOverflow()throws Exception {
        var h=helper();var f=h.fixture();var p=h.prepared(f,true);var ledger=new QuotaLedger(f.db());long max=9_007_199_254_740_991L;
        try{assertThrows(RollbackProbe.class,()->f.db().transaction(()->{
            f.db().update("INSERT INTO quota_accounts(tenant_id,subject_id,account_key,period_key,turn_limit,consumed) VALUES(?,?,?,?,?,?)",id(TENANT),id(f.owner().id()),key("total"),f.db().quotaPeriod(),max,max);
            f.db().update("UPDATE quota_policies SET turn_limit=? WHERE tenant_id=? AND subject_id=?",max,id(TENANT),id(f.owner().id()));
            assertFalse(ledger.reserve(h.binding(p),f.owner().id(),"fixture-v1",max));balance(f,"total",f.db().quotaPeriod(),max,0);throw new RollbackProbe();
        }));}
        finally{h.close(f,p);}
    }
}
