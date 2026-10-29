package io.aitest.runner;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class StartOwnershipTest {
    private static SubmissionJournal.Command command() {
        return new SubmissionJournal.Command(new byte[16], new byte[16], new byte[]{1}, new byte[32], 1);
    }
    @Test void committedIntentIsSingleUseAcrossRepeatedClaims() throws Exception {
        var consumed = new AtomicBoolean();
        var journal = new StartOwnership(source(consumed, false, false));
        assertEquals(StartOwnership.Result.NEW_INTENT_COMMITTED, journal.claim(command()));
        assertEquals(StartOwnership.Result.RECONCILE, journal.claim(command()));
    }
    @Test void lostCommitReplyNeverPermitsLaunchOrRetry() throws Exception {
        var consumed = new AtomicBoolean();
        var journal = new StartOwnership(source(consumed, true, false));
        assertEquals(StartOwnership.Result.UNKNOWN, journal.claim(command()));
        assertEquals(StartOwnership.Result.RECONCILE, new StartOwnership(source(consumed, false, false)).claim(command()));
    }
    @Test void closeFailureAfterCommitNeverPermitsLaunch() throws Exception {
        assertEquals(StartOwnership.Result.UNKNOWN, new StartOwnership(source(new AtomicBoolean(), false, true)).claim(command()));
    }
    @Test void existingIntentIsNeverResetByReconstructedOwner() throws Exception {
        var consumed = new AtomicBoolean(true);
        assertEquals(StartOwnership.Result.RECONCILE, new StartOwnership(source(consumed, false, false)).claim(command()));
    }
    /** JDBC protocol double only; it does not prove MySQL concurrency or durable process fencing. */
    private static DataSource source(AtomicBoolean consumed, boolean loseCommit, boolean failClose) {
        return proxy(DataSource.class, (method,args) -> method.equals("getConnection") ? proxy(Connection.class, (call,values) -> switch(call) {
            case "prepareStatement" -> {
                String sql=(String)values[0];
                assertTrue(sql.contains("epoch=? AND state='ACCEPTED' AND start_intent IS NULL"));
                yield proxy(PreparedStatement.class, (operation,parameters) -> operation.equals("executeUpdate") ? (consumed.compareAndSet(false,true) ? 1 : 0) : null);
            }
            case "commit" -> { if(loseCommit)throw new SQLException("lost reply"); yield null; }
            case "close" -> { if(failClose)throw new SQLException("close failed"); yield null; }
            default -> null;
        }) : null);
    }
    @FunctionalInterface private interface Handler { Object invoke(String method,Object[] args) throws Throwable; }
    private static <T>T proxy(Class<T> type,Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(ignored,method,args)->handler.invoke(method.getName(),args)));
    }
}
