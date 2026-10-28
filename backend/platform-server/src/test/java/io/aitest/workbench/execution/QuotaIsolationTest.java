package io.aitest.workbench.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real dispatch and settlement, with a second synthetic model ledger. */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class QuotaIsolationTest {
    @TempDir Path blobs;
    DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
    void secondAccount(DispatchAtomicityTest.Fixture f,long limit){
        f.db().update("INSERT INTO quota_accounts(tenant_id,subject_id,account_key,period_key,turn_limit) VALUES(?,?,?,?,?)",id(TENANT),id(f.owner().id()),key("model:other-fixture"),f.db().quotaPeriod(),limit);
    }
    Map<String,Object> other(DispatchAtomicityTest.Fixture f){return f.db().one("SELECT reserved,consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND account_key=?",id(TENANT),id(f.owner().id()),key("model:other-fixture"));}
    @Test void dispatchDoesNotReserveOrReleaseAnotherModelAccount()throws Exception {
        var h=helper();var f=h.fixture();var p=h.prepared(f,true);secondAccount(f,100);
        try{assertTrue(h.dispatch(f,p));assertEquals(0,number(other(f).get("reserved")),"unselected model must not be reserved");}
        finally{h.close(f,p);}
        assertEquals(0,number(other(f).get("reserved")));assertEquals(0,number(other(f).get("consumed")));
    }
    @Test void exhaustedUnselectedModelDoesNotBlockSelectedModel()throws Exception {
        var h=helper();var f=h.fixture();var p=h.prepared(f,true);secondAccount(f,0);
        try{assertTrue(h.dispatch(f,p),"only total and selected model may gate dispatch");}
        finally{h.close(f,p);}
    }
    @Test void terminalReplayDoesNotChargeAnotherModelAccount()throws Exception {
        var h=helper();var f=h.fixture();var p=h.prepared(f,true);secondAccount(f,100);
        try{
            assertTrue(h.dispatch(f,p));f.provider().start(h.binding(p));h.reconcile(f,p);h.reconcile(f,p);
            assertEquals(0,number(other(f).get("consumed")),"unselected model must never be charged");
            assertEquals(0,number(other(f).get("reserved")));
        }finally{h.close(f,p);}
    }
}
