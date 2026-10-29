package io.aitest.workbench.identity;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import static io.aitest.workbench.infrastructure.Store.TENANT;

/** Internal, unregistered identity resolution for the existing single-tenant data model.
 * It does not replace transactional resource authorization or a TLS/CSRF boundary. */
final class FormalSessionIdentity {
    enum Status { VALID, INVALID, UNAVAILABLE }
    record Result(Status status, Scope.Actor actor) {
        Result { if ((status == Status.VALID) != (actor != null)) throw new IllegalArgumentException(); }
    }
    interface SessionLookup { LocalSessionTokens.Resolution resolve(String token); }
    private final UUID tenant;
    private final SessionLookup sessions;
    private final DataSource source;

    /** Existing business queries use Store.TENANT; reject a configuration they cannot honor. */
    FormalSessionIdentity(UUID tenant, LocalSessionTokens tokens, DataSource source) {
        this(tenant, Objects.requireNonNull(tokens)::resolve, source);
    }
    FormalSessionIdentity(UUID tenant, SessionLookup sessions, DataSource source) {
        if (!TENANT.equals(tenant)) throw new IllegalArgumentException("Unsupported server tenant");
        this.tenant = tenant;
        this.sessions = Objects.requireNonNull(sessions);
        this.source = Objects.requireNonNull(source);
    }

    /** Never accepts a client-supplied subject, alias, role, tenant or revision. */
    Result resolve(String token) {
        try {
            var session = sessions.resolve(token);
            if (session == null || session.status() == LocalSessionTokens.Status.UNAVAILABLE)
                return result(Status.UNAVAILABLE);
            if (session.status() != LocalSessionTokens.Status.VALID || session.principal() == null
                    || !tenant.equals(session.principal().tenant())) return result(Status.INVALID);
            var principal = session.principal();
            try (var c = source.getConnection();
                 var s = c.prepareStatement("SELECT s.alias,m.role_name,m.state,m.revision FROM memberships m JOIN subjects s ON s.id=m.subject_id WHERE m.tenant_id=? AND m.subject_id=?")) {
                s.setBytes(1, id(tenant)); s.setBytes(2, id(principal.subject()));
                try (var r = s.executeQuery()) {
                    if (!r.next()) return result(Status.INVALID);
                    var aliasBytes = r.getBytes(1);
                    if (aliasBytes == null || aliasBytes.length == 0 || aliasBytes.length > 80) return result(Status.INVALID);
                    String alias = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(aliasBytes)).toString();
                    String role = r.getString(2), state = r.getString(3);
                    long revision = r.getLong(4);
                    if (r.wasNull() || !"active".equals(state) || revision != principal.authorizationRevision()
                            || !java.util.Set.of("admin", "reviewer", "member").contains(role)) return result(Status.INVALID);
                    return new Result(Status.VALID, new Scope.Actor(principal.subject(), alias, role, state, revision));
                }
            }
        } catch (SQLException | java.nio.charset.CharacterCodingException | RuntimeException failure) {
            // Avoid exposing database, token or session details to a future caller.
            return result(Status.UNAVAILABLE);
        }
    }

    private static Result result(Status status) { return new Result(status, null); }
    private static byte[] id(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
}
