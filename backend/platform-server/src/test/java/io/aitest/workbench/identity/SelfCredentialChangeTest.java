package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SelfCredentialChangeTest {
    private static final Instant NOW=Instant.parse("2026-10-28T00:00:00Z");
    private static final String TOKEN=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String OLD="valid old password 123";
    private static final String NEXT="valid new password 456";

    private record Fixture(SelfCredentialChange change, Connection connection, PreparedStatement statement, ResultSet row) {}
    private Fixture fixture(boolean revoked, boolean commitFailure) throws Exception {
        DataSource ds=mock(DataSource.class); Connection c=mock(Connection.class);
        PreparedStatement select=mock(PreparedStatement.class), update=mock(PreparedStatement.class);
        ResultSet row=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.prepareStatement(startsWith("SELECT "))).thenReturn(select);
        when(c.prepareStatement(startsWith("UPDATE "))).thenReturn(update);
        when(c.prepareStatement(startsWith("INSERT "))).thenReturn(update);
        when(select.executeQuery()).thenReturn(row); when(update.executeUpdate()).thenReturn(1);
        when(row.next()).thenReturn(true); when(row.getBytes(1)).thenReturn(new byte[16]); when(row.getBytes(2)).thenReturn(new byte[16]);
        when(row.getLong(3)).thenReturn(5L); when(row.getLong(7)).thenReturn(5L); when(row.getLong(9)).thenReturn(2L);
        when(row.getTimestamp(eq(4),any())).thenReturn(Timestamp.from(NOW.plusSeconds(60)));
        when(row.getBoolean(5)).thenReturn(revoked); when(row.getString(6)).thenReturn("active"); when(row.getBoolean(8)).thenReturn(true);
        when(row.getString(10)).thenReturn(new LocalPasswordCodec().hash(OLD));
        if(commitFailure) doThrow(new SQLException("lost commit response")).when(c).commit();
        return new Fixture(new SelfCredentialChange(ds,Clock.fixed(NOW,ZoneOffset.UTC)),c,update,row);
    }

    @Test void validSessionAndPasswordChangeRevokeSessionsAndWriteAuditAtomically() throws Exception {
        var f=fixture(false,false);
        assertEquals(SelfCredentialChange.Result.CHANGED,f.change.change(TOKEN,OLD,NEXT));
        verify(f.connection).prepareStatement(startsWith("UPDATE local_auth_accounts"));
        verify(f.connection).prepareStatement(startsWith("UPDATE memberships"));
        verify(f.connection).prepareStatement(startsWith("UPDATE local_auth_sessions"));
        verify(f.connection).prepareStatement(startsWith("INSERT INTO audit_entries"));
        verify(f.connection).commit(); verify(f.connection,never()).rollback();
    }

    @Test void revokedSessionOrWrongPasswordCannotChangeCredentials() throws Exception {
        var revoked=fixture(true,false);
        assertEquals(SelfCredentialChange.Result.DENIED,revoked.change.change(TOKEN,OLD,NEXT));
        verify(revoked.connection,never()).prepareStatement(startsWith("UPDATE "));
        var wrong=fixture(false,false);
        assertEquals(SelfCredentialChange.Result.DENIED,wrong.change.change(TOKEN,"wrong old password",NEXT));
        verify(wrong.connection,never()).prepareStatement(startsWith("UPDATE "));
    }

    @Test void lostCommitResponseDoesNotClaimSuccess() throws Exception {
        var f=fixture(false,true);
        assertEquals(SelfCredentialChange.Result.UNKNOWN,f.change.change(TOKEN,OLD,NEXT));
        verify(f.connection).rollback();
    }
}
