package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Transaction protocol tests; no real database or login endpoint. */
class JdbcVerifiedSessionIssuerTest {
    private final LocalSessionTokens.Principal principal=new LocalSessionTokens.Principal(UUID.randomUUID(),UUID.randomUUID(),4);
    private final JdbcLocalAuthentication.Verified verified=new JdbcLocalAuthentication.Verified(principal,7);
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"),ZoneOffset.UTC);

    @Test void passwordOrMembershipChangeRejectsBeforeInsert() throws Exception {
        var f=fixture(); var issuer=new JdbcVerifiedSessionIssuer(f.ds,CLOCK,Duration.ofMinutes(5));
        when(f.row.getLong(2)).thenReturn(8L);
        assertThrows(IllegalStateException.class,()->issuer.issue(new JdbcLocalAuthentication.Verified(principal,7)));
        verify(f.c,never()).prepareStatement(startsWith("INSERT")); verify(f.c).rollback();
        when(f.row.getLong(2)).thenReturn(7L); when(f.row.getLong(4)).thenReturn(5L);
        assertThrows(IllegalStateException.class,()->issuer.issue(new JdbcLocalAuthentication.Verified(principal,7)));
        verify(f.c,never()).prepareStatement(startsWith("INSERT"));
    }
    @Test void disabledAccountOrMemberNeverGetsSession() throws Exception {
        var f=fixture(); var issuer=new JdbcVerifiedSessionIssuer(f.ds,CLOCK,Duration.ofMinutes(5));
        when(f.row.getBoolean(1)).thenReturn(false);
        assertThrows(IllegalStateException.class,()->issuer.issue(verified));
        when(f.row.getBoolean(1)).thenReturn(true); when(f.row.getString(3)).thenReturn("paused");
        assertThrows(IllegalStateException.class,()->issuer.issue(new JdbcLocalAuthentication.Verified(principal,7)));
        verify(f.c,never()).prepareStatement(startsWith("INSERT"));
    }
    @Test void unknownCommitNeverReturnsToken() throws Exception {
        var f=fixture(); doThrow(new SQLException("uncertain")).when(f.c).commit();
        var issuer=new JdbcVerifiedSessionIssuer(f.ds,CLOCK,Duration.ofMinutes(5));
        assertThrows(IllegalStateException.class,()->issuer.issue(verified)); verify(f.c).rollback();
        assertThrows(IllegalStateException.class,()->issuer.issue(verified)); verify(f.ds,times(1)).getConnection();
    }
    @Test void successfulCommitStoresOnlyHashAndReturnsToken() throws Exception {
        var f=fixture(); var issuer=new JdbcVerifiedSessionIssuer(f.ds,CLOCK,Duration.ofMinutes(5));
        var issued=issuer.issue(verified);
        assertEquals(43,issued.value().length()); assertEquals("Issued[redacted]",issued.toString());
        verify(f.insert).setBytes(eq(1),argThat(value->value.length==32)); verify(f.c).commit();
        assertThrows(IllegalStateException.class,()->issuer.issue(verified));
        verify(f.ds,times(1)).getConnection();
    }
    private static Fixture fixture() throws Exception {
        var ds=mock(DataSource.class); var c=mock(Connection.class); var q=mock(PreparedStatement.class); var insert=mock(PreparedStatement.class); var row=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c); when(c.prepareStatement(startsWith("SELECT a.enabled"))).thenReturn(q);
        when(q.executeQuery()).thenReturn(row); when(row.next()).thenReturn(true); when(row.getBoolean(1)).thenReturn(true);
        when(row.getLong(2)).thenReturn(7L); when(row.getString(3)).thenReturn("active"); when(row.getLong(4)).thenReturn(4L);
        when(c.prepareStatement(startsWith("INSERT INTO local_auth_sessions"))).thenReturn(insert); when(insert.executeUpdate()).thenReturn(1);
        return new Fixture(ds,c,row,insert);
    }
    private record Fixture(DataSource ds,Connection c,ResultSet row,PreparedStatement insert) {}
}
