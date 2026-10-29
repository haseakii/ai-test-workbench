package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.*;
import java.time.Instant;
import java.util.Calendar;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC protocol doubles only; real MySQL locking and crash behavior require integration testing. */
class JdbcLocalSessionStoreTest {
    private final byte[] hash = new byte[32];
    private final UUID tenant = UUID.randomUUID(), subject = UUID.randomUUID();
    private final LocalSessionTokens.Principal principal = new LocalSessionTokens.Principal(tenant, subject, 4);
    private final Instant now = Instant.parse("2026-10-28T00:00:00Z");

    @Test void creationRequiresActiveCurrentRevisionBeforeInsert() throws Exception {
        var ds = mock(DataSource.class); var c = mock(Connection.class); var q = mock(PreparedStatement.class); var r = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c); when(c.prepareStatement(startsWith("SELECT state"))).thenReturn(q);
        when(q.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("active"); when(r.getLong(2)).thenReturn(5L);
        var store = new JdbcLocalSessionStore(ds);
        assertThrows(SQLException.class, () -> store.create(hash, principal, now.plusSeconds(60)));
        verify(c).rollback(); verify(c, never()).commit(); verify(c, never()).prepareStatement(startsWith("INSERT"));
    }

    @Test void commitFailureNeverReturnsIssuedToken() throws Exception {
        var ds = mock(DataSource.class); var c = mock(Connection.class); var q = mock(PreparedStatement.class); var i = mock(PreparedStatement.class); var r = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c); when(c.prepareStatement(startsWith("SELECT state"))).thenReturn(q); when(c.prepareStatement(startsWith("INSERT"))).thenReturn(i);
        when(q.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("active"); when(r.getLong(2)).thenReturn(4L);
        when(i.executeUpdate()).thenReturn(1); doThrow(new SQLException("uncertain")).when(c).commit();
        var store = new JdbcLocalSessionStore(ds);
        assertThrows(SQLException.class, () -> store.create(hash, principal, now.plusSeconds(60)));
        verify(c).rollback(); verify(i).setBytes(1, hash);
    }

    @Test void staleOrExpiredMembershipCannotResolveSession() throws Exception {
        var ds = mock(DataSource.class); var c = mock(Connection.class); var q = mock(PreparedStatement.class); var r = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c); when(c.prepareStatement(startsWith("SELECT s.tenant_id"))).thenReturn(q);
        when(q.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getLong(3)).thenReturn(4L); when(r.getLong(7)).thenReturn(5L);
        when(r.getString(6)).thenReturn("active"); when(r.getTimestamp(eq(4), any(Calendar.class))).thenReturn(Timestamp.from(now.plusSeconds(1)));
        var store = new JdbcLocalSessionStore(ds);
        assertNull(store.resolve(hash, now)); verify(c).commit();
        when(r.getLong(7)).thenReturn(4L); when(r.getBytes(1)).thenReturn(id(tenant)); when(r.getBytes(2)).thenReturn(id(subject));
        assertEquals(principal, store.resolve(hash, now).principal());
        assertNull(store.resolve(hash, now.plusSeconds(1)));
        when(r.getString(6)).thenReturn("paused"); assertNull(store.resolve(hash, now));
    }

    @Test void revocationCommitsAndBadHashIsRejected() throws Exception {
        var ds = mock(DataSource.class); var c = mock(Connection.class); var q = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(c); when(c.prepareStatement(startsWith("UPDATE local_auth_sessions"))).thenReturn(q);
        var store = new JdbcLocalSessionStore(ds); store.revoke(hash); verify(c).commit();
        assertThrows(IllegalArgumentException.class, () -> store.revoke(new byte[31]));
    }
    private static byte[] id(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
}
