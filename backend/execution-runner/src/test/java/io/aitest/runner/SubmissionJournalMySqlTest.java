package io.aitest.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Restricted to the explicitly configured local synthetic Runner database. */
@EnabledIfEnvironmentVariable(named="WB_RUNNER_JOURNAL_TEST", matches="true")
class SubmissionJournalMySqlTest {
    @Test void competingAcceptAndCloseHaveOnePermanentOutcome() throws Exception {
        String url = System.getenv("WB_RUNNER_DB_URL"), password = System.getenv("WB_RUNNER_PASSWORD");
        assertNotNull(url); assertNotNull(password);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/workbench_runner?"));
        assertTrue(url.contains("sslMode=VERIFY"));
        DataSource source = new LocalSource(url, password);
        byte[] tenant = uuid(), binding = uuid(), hash = new byte[32], key = uuid();
        var command = new SubmissionJournal.Command(tenant, binding, key, hash, 1);
        var journal = new SubmissionJournal(source);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean targetVerified = false;
        try {
            try (Connection c=source.getConnection(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
                assertTrue(r.next()); assertEquals("workbench_runner", r.getString(1)); assertTrue(r.getString(2).startsWith("workbench_runner@"));
            }
            targetVerified = true;
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            Callable<SubmissionJournal.Result> accept = () -> { ready.countDown(); go.await(); return journal.decide(command, SubmissionJournal.Operation.ACCEPT); };
            Callable<SubmissionJournal.Result> close = () -> { ready.countDown(); go.await(); return journal.decide(command, SubmissionJournal.Operation.CLOSE); };
            var a=executor.submit(accept); var b=executor.submit(close);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            var first=a.get(20,TimeUnit.SECONDS); var second=b.get(20,TimeUnit.SECONDS);
            assertEquals(first, second); assertTrue(first == SubmissionJournal.Result.ACCEPTED || first == SubmissionJournal.Result.CLOSED);
            assertEquals(first, journal.lookup(command));
            assertEquals(first, journal.decide(command, SubmissionJournal.Operation.ACCEPT));
            assertEquals(first, journal.decide(command, SubmissionJournal.Operation.CLOSE));
            byte[] changedHash = hash.clone(); changedHash[0] = 1;
            assertEquals(SubmissionJournal.Result.CONFLICT, journal.decide(new SubmissionJournal.Command(tenant,binding,key,changedHash,1), SubmissionJournal.Operation.ACCEPT));
            assertEquals(SubmissionJournal.Result.STALE_EPOCH, journal.lookup(new SubmissionJournal.Command(tenant,binding,key,hash,2)));
            assertEquals(SubmissionJournal.Result.CONFLICT, journal.decide(new SubmissionJournal.Command(tenant,binding,uuid(),hash,1), SubmissionJournal.Operation.ACCEPT));
            assertEquals(SubmissionJournal.Result.UNKNOWN, journal.lookup(new SubmissionJournal.Command(uuid(),binding,key,hash,1)));
        } finally {
            executor.shutdown(); assertTrue(executor.awaitTermination(30,TimeUnit.SECONDS));
            if (targetVerified) try (Connection c=source.getConnection(); PreparedStatement s=c.prepareStatement("DELETE FROM submission_journal WHERE tenant_id=? AND binding_id=? AND idempotency_key=?")) {
                    s.setBytes(1,tenant); s.setBytes(2,binding); s.setBytes(3,key); s.executeUpdate();
                }
        }
    }
    private static byte[] uuid(){ UUID id=UUID.randomUUID(); return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private record LocalSource(String url, String password) implements DataSource {
        public Connection getConnection() throws SQLException { return DriverManager.getConnection(url,"workbench_runner",password); }
        public Connection getConnection(String u,String p) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        public PrintWriter getLogWriter(){return null;} public void setLogWriter(PrintWriter p){} public void setLoginTimeout(int s){} public int getLoginTimeout(){return 0;}
        public Logger getParentLogger(){return Logger.getGlobal();} public <T>T unwrap(Class<T> c) throws SQLException{throw new SQLException("not supported");} public boolean isWrapperFor(Class<?> c){return false;}
    }
}
