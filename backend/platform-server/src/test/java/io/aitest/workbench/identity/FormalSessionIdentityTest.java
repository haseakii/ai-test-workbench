package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.aitest.workbench.infrastructure.Store.TENANT;

class FormalSessionIdentityTest {
    private final UUID subject = UUID.randomUUID();
    private final LocalSessionTokens.Principal principal = new LocalSessionTokens.Principal(TENANT, subject, 3);
    private static byte[] id(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }

    @Test void serverTenantAndSessionSubjectAloneChooseIdentity() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBytes(1)).thenReturn("server-alias".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(rows.getString(2)).thenReturn("member"); when(rows.getString(3)).thenReturn("active");
        when(rows.getLong(4)).thenReturn(3L);
        var resolver = new FormalSessionIdentity(TENANT, token -> new LocalSessionTokens.Resolution(LocalSessionTokens.Status.VALID, principal), source);
        var result = resolver.resolve("opaque-token");
        assertEquals(FormalSessionIdentity.Status.VALID, result.status());
        assertEquals(subject, result.actor().id()); assertEquals("server-alias", result.actor().alias());
        verify(statement).setBytes(1, id(TENANT)); verify(statement).setBytes(2, id(subject));
        when(rows.getLong(4)).thenReturn(4L);
        assertEquals(FormalSessionIdentity.Status.INVALID, resolver.resolve("opaque-token").status());
        when(rows.getLong(4)).thenReturn(3L); when(rows.getString(3)).thenReturn("paused");
        assertEquals(FormalSessionIdentity.Status.INVALID, resolver.resolve("opaque-token").status());
    }

    @Test void badSessionAndWrongTenantNeverQueryDatabase() throws Exception {
        var source = mock(DataSource.class);
        var other = new LocalSessionTokens.Principal(UUID.randomUUID(), subject, 3);
        assertThrows(IllegalArgumentException.class, () -> new FormalSessionIdentity(other.tenant(), token -> null, source));
        var wrongTenant = new FormalSessionIdentity(TENANT, token -> new LocalSessionTokens.Resolution(LocalSessionTokens.Status.VALID, other), source);
        assertEquals(FormalSessionIdentity.Status.INVALID, wrongTenant.resolve("token").status());
        var expired = new FormalSessionIdentity(TENANT, token -> new LocalSessionTokens.Resolution(LocalSessionTokens.Status.INVALID, null), source);
        assertEquals(FormalSessionIdentity.Status.INVALID, expired.resolve("token").status());
        verifyNoInteractions(source);
    }

    @Test void unavailableStoreOrInvalidMemberNeverReturnsActor() throws Exception {
        var source = mock(DataSource.class);
        var unavailable = new FormalSessionIdentity(TENANT, token -> new LocalSessionTokens.Resolution(LocalSessionTokens.Status.UNAVAILABLE, null), source);
        assertEquals(FormalSessionIdentity.Status.UNAVAILABLE, unavailable.resolve("token").status());
        var resolved = new FormalSessionIdentity(TENANT, token -> new LocalSessionTokens.Resolution(LocalSessionTokens.Status.VALID, principal), source);
        when(source.getConnection()).thenThrow(new SQLException("private error"));
        assertEquals(FormalSessionIdentity.Status.UNAVAILABLE, resolved.resolve("token").status());
    }
}
