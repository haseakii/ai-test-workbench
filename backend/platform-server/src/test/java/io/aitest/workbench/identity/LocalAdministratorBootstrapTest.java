package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalAdministratorBootstrapTest {
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final String HASH = "{pbkdf2-sha256-600000-v1}" + "a".repeat(96);
    private static final UUID DEPLOYMENT = UUID.fromString("f4222678-df90-4153-b270-f18371ba978a");
    private static final byte[] TOKEN = new byte[32];

    @Test void refusesUnprovisionedExpiredAndDirtyDatabases() throws Exception {
        for (var mode : new String[]{"absent", "wrong-token", "expired", "dirty"}) {
            var fixture = fixture(mode);
            assertEquals(LocalAdministratorBootstrap.Outcome.REJECTED, fixture.bootstrap.initialize(TOKEN, DEPLOYMENT, "administrator", HASH));
            verify(fixture.connection, never()).commit();
            verify(fixture.connection).rollback();
        }
    }

    @Test void singleProvisionedGuardAndAtomicCommitAreRequired() throws Exception {
        var fixture = fixture("valid");
        assertEquals(LocalAdministratorBootstrap.Outcome.INITIALIZED, fixture.bootstrap.initialize(TOKEN, DEPLOYMENT, "administrator", HASH));
        verify(fixture.connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        verify(fixture.connection).commit();
        assertEquals(LocalAdministratorBootstrap.Outcome.REJECTED, fixture.bootstrap.initialize(TOKEN, DEPLOYMENT, "administrator", "default-password"));
    }

    @Test void defaultEligibilityAndExpiryDuringTransactionRefuseInitialization() throws Exception {
        var source=mock(DataSource.class); var c=mock(Connection.class); when(source.getConnection()).thenReturn(c);
        var disabled=new LocalAdministratorBootstrap(source,Clock.fixed(NOW,ZoneOffset.UTC));
        assertEquals(LocalAdministratorBootstrap.Outcome.REJECTED, disabled.initialize(TOKEN, DEPLOYMENT, "administrator", HASH));
        verify(c, never()).commit();
        var expired=fixture("expires-during-write");
        assertEquals(LocalAdministratorBootstrap.Outcome.REJECTED, expired.bootstrap.initialize(TOKEN, DEPLOYMENT, "administrator", HASH));
        verify(expired.connection, never()).commit(); verify(expired.connection).rollback();
    }

    @Test void lostCommitOrUncertainCloseNeverClaimsInitialization() throws Exception {
        var commit = fixture("valid"); doThrow(new SQLException("lost response")).when(commit.connection).commit();
        assertEquals(LocalAdministratorBootstrap.Outcome.UNKNOWN, commit.bootstrap.initialize(TOKEN, DEPLOYMENT, "administrator", HASH));
        var close = fixture("valid"); doThrow(new SQLException("close failed")).when(close.connection).close();
        assertEquals(LocalAdministratorBootstrap.Outcome.UNKNOWN, close.bootstrap.initialize(TOKEN, DEPLOYMENT, "administrator", HASH));
    }

    private record Fixture(LocalAdministratorBootstrap bootstrap, Connection connection) {}
    private static Fixture fixture(String mode) throws Exception {
        var source=mock(DataSource.class); var c=mock(Connection.class); when(source.getConnection()).thenReturn(c);
        when(c.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql=invocation.getArgument(0); var statement=mock(PreparedStatement.class);
            when(statement.executeUpdate()).thenReturn(1);
            if (mode.equals("expires-during-write") && sql.startsWith("UPDATE production_bootstrap_guard")) {
                assertTrue(sql.contains("expires_at > UTC_TIMESTAMP(6)"));
                when(statement.executeUpdate()).thenReturn(0);
            }
            if (sql.startsWith("SELECT")) {
                var rows=mock(ResultSet.class); when(statement.executeQuery()).thenReturn(rows);
                if (sql.contains("production_boundary")) {
                    when(rows.next()).thenReturn(true,false); when(rows.getString(1)).thenReturn("production_uninitialized");
                } else if (sql.contains("production_bootstrap_guard")) {
                    if (!mode.equals("absent")) {
                        when(rows.next()).thenReturn(true,false); when(rows.getBytes(1)).thenReturn(id(DEPLOYMENT));
                        when(rows.getBytes(2)).thenReturn(mode.equals("wrong-token") ? new byte[32] : MessageDigest.getInstance("SHA-256").digest(TOKEN));
                        when(rows.getTimestamp(eq(3),any())).thenReturn(Timestamp.from(mode.equals("expired") ? NOW : NOW.plusSeconds(60)));
                        when(rows.getString(4)).thenReturn("provisioned");
                    }
                } else if (mode.equals("dirty") && sql.contains(" FROM tenants ")) when(rows.next()).thenReturn(true);
            }
            return statement;
        });
        return new Fixture(new LocalAdministratorBootstrap(source,Clock.fixed(NOW,ZoneOffset.UTC), connection -> true),c);
    }
    private static byte[] id(UUID u) { return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array(); }
}
