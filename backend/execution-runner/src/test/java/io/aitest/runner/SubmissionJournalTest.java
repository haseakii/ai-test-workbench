package io.aitest.runner;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SubmissionJournalTest {
    private static SubmissionJournal.Command command(byte key, byte hash, long epoch) {
        byte[] tenant = new byte[16], binding = new byte[16], digest = new byte[32];
        Arrays.fill(digest, hash);
        return new SubmissionJournal.Command(tenant, binding, new byte[]{key}, digest, epoch);
    }
    @Test void commandIsImmutableAndBoundsEpoch() {
        var c = command((byte) 1, (byte) 2, 1);
        c.hash()[0] = 7;
        assertEquals(2, c.hash()[0]);
        assertThrows(IllegalArgumentException.class, () -> command((byte) 1, (byte) 2, -1));
        assertThrows(IllegalArgumentException.class, () -> command((byte) 1, (byte) 2, 9_007_199_254_740_992L));
    }
    @Test void unknownCommitNeverReportsAccepted() throws SQLException {
        var journal = new SubmissionJournal(emptyDatabase(true));
        assertEquals(SubmissionJournal.Result.UNKNOWN, journal.decide(command((byte) 1, (byte) 2, 1), SubmissionJournal.Operation.ACCEPT));
    }
    @Test void connectionCloseFailureAfterCommitIsUnknown() throws SQLException {
        var journal = new SubmissionJournal(emptyDatabase(false, true));
        assertEquals(SubmissionJournal.Result.UNKNOWN, journal.decide(command((byte) 1, (byte) 2, 1), SubmissionJournal.Operation.ACCEPT));
    }
    @Test void absentLookupIsUnknownAndCloseCanBeRecorded() throws SQLException {
        var journal = new SubmissionJournal(emptyDatabase(false));
        assertEquals(SubmissionJournal.Result.UNKNOWN, journal.lookup(command((byte) 1, (byte) 2, 1)));
        assertEquals(SubmissionJournal.Result.CLOSED, journal.decide(command((byte) 1, (byte) 2, 1), SubmissionJournal.Operation.CLOSE));
    }
    /** A protocol test double, not a database concurrency or durability test. */
    private static DataSource emptyDatabase(boolean failCommit) {
        return emptyDatabase(failCommit, false);
    }
    private static DataSource emptyDatabase(boolean failCommit, boolean failClose) {
        return proxy(DataSource.class, (method, args) -> {
            if (!method.equals("getConnection")) return null;
            return proxy(Connection.class, (call, parameters) -> switch (call) {
                case "prepareStatement" -> proxy(PreparedStatement.class, (query, values) -> switch (query) {
                    case "executeQuery" -> proxy(ResultSet.class, (read, ignored) -> read.equals("next") ? false : null);
                    case "executeUpdate" -> 1;
                    default -> null;
                });
                case "commit" -> { if (failCommit) throw new SQLException("lost commit response"); yield null; }
                case "close" -> { if (failClose) throw new SQLException("lost connection on close"); yield null; }
                default -> null;
            });
        });
    }
    @FunctionalInterface private interface Handler { Object invoke(String method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> handler.invoke(method.getName(), args)));
    }
}
