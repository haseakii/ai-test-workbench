package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.*;
import java.time.*;
import java.util.Calendar;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC protocol doubles: concurrent locking and timing require real MySQL verification. */
class JdbcLocalAuthenticationTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final String PASSWORD = "correct-passphrase-123";
    private final UUID tenant = UUID.randomUUID(), subject = UUID.randomUUID();

    @Test void unknownWrongAndPausedHaveSameDenial() throws Exception {
        var f = fixture(); var auth = new JdbcLocalAuthentication(f.ds, Clock.fixed(NOW,ZoneOffset.UTC));
        when(f.account.next()).thenReturn(false);
        assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"person","127.0.0.1",PASSWORD).status());
        account(f, true, "active", 7, 4);
        assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"person","127.0.0.1","incorrect-passphrase").status());
        account(f, true, "paused", 7, 4);
        assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"person","127.0.0.1",PASSWORD).status());
        verify(f.c,times(3)).commit();
    }
    @Test void verifiedResultCarriesBothRevisionsForAtomicSessionRecheck() throws Exception {
        var f=fixture(); account(f,true,"active",7,4);
        var auth=new JdbcLocalAuthentication(f.ds,Clock.fixed(NOW,ZoneOffset.UTC));
        var result=auth.verify(tenant,"person","127.0.0.1",PASSWORD);
        assertEquals(JdbcLocalAuthentication.Status.VERIFIED,result.status());
        assertEquals(subject,result.verified().principal().subject());
        assertEquals(4,result.verified().principal().authorizationRevision());
        assertEquals(7,result.verified().passwordRevision());
    }
    @Test void boundedBucketsFailClosedOnThrottleOrCommitFailure() throws Exception {
        var f=fixture(); var auth=new JdbcLocalAuthentication(f.ds,Clock.fixed(NOW,ZoneOffset.UTC));
        when(f.bucket.getInt(1)).thenReturn(8);
        assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"person","127.0.0.1",PASSWORD).status());
        verify(f.c,never()).prepareStatement(startsWith("SELECT a.subject_id"));
        when(f.bucket.getInt(1)).thenReturn(0); account(f,true,"active",7,4);
        doThrow(new SQLException("unknown commit")).when(f.c).commit();
        assertEquals(JdbcLocalAuthentication.Status.UNAVAILABLE,auth.verify(tenant,"person","127.0.0.1",PASSWORD).status());
    }
    @Test void malformedInputDoesNotAccessStore() throws Exception {
        var f=fixture(); var auth=new JdbcLocalAuthentication(f.ds,Clock.fixed(NOW,ZoneOffset.UTC));
        assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"BAD LOGIN","127.0.0.1",PASSWORD).status());
        assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"person","x\ny",PASSWORD).status());
        verify(f.ds,never()).getConnection();
    }
    @Test void corruptedAccountHashCannotAuthenticateUsingDummyPassword() throws Exception {
        var f=fixture(); account(f,true,"active",7,4); when(f.account.getString(2)).thenReturn("invalid");
        var auth=new JdbcLocalAuthentication(f.ds,Clock.fixed(NOW,ZoneOffset.UTC));
        assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"person","127.0.0.1","local-auth-dummy-password-never-accepted").status());
    }
    private void account(Fixture f, boolean enabled, String state, long passwordRevision, long memberRevision) throws Exception {
        when(f.account.next()).thenReturn(true); when(f.account.getBytes(1)).thenReturn(id(subject));
        when(f.account.getString(2)).thenReturn(new LocalPasswordCodec().hash(PASSWORD)); when(f.account.getLong(3)).thenReturn(passwordRevision);
        when(f.account.getBoolean(4)).thenReturn(enabled); when(f.account.getString(5)).thenReturn(state); when(f.account.getLong(6)).thenReturn(memberRevision);
    }
    private static Fixture fixture() throws Exception {
        var ds=mock(DataSource.class); var c=mock(Connection.class); var insert=mock(PreparedStatement.class);
        var select=mock(PreparedStatement.class); var update=mock(PreparedStatement.class); var query=mock(PreparedStatement.class);
        var bucket=mock(ResultSet.class); var account=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c); when(c.prepareStatement(startsWith("INSERT INTO local_login_buckets"))).thenReturn(insert);
        when(c.prepareStatement(startsWith("SELECT attempts"))).thenReturn(select); when(select.executeQuery()).thenReturn(bucket);
        when(bucket.next()).thenReturn(true); when(bucket.getTimestamp(eq(2),any(Calendar.class))).thenReturn(Timestamp.from(NOW.plusSeconds(60)));
        when(c.prepareStatement(startsWith("UPDATE local_login_buckets"))).thenReturn(update); when(update.executeUpdate()).thenReturn(1);
        when(c.prepareStatement(startsWith("SELECT a.subject_id"))).thenReturn(query); when(query.executeQuery()).thenReturn(account);
        return new Fixture(ds,c,bucket,account);
    }
    private static byte[] id(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private record Fixture(DataSource ds, Connection c, ResultSet bucket, ResultSet account) {}
}
