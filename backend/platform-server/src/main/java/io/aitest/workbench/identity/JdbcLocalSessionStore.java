package io.aitest.workbench.identity;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;

/** Unregistered infrastructure; membership recheck does not replace resource-level authorization. */
final class JdbcLocalSessionStore implements LocalSessionTokens.Store {
    private final DataSource source;

    JdbcLocalSessionStore(DataSource source) { this.source = Objects.requireNonNull(source); }

    @Override public void create(byte[] hash, LocalSessionTokens.Principal principal, Instant expires) throws SQLException {
        validateHash(hash); Objects.requireNonNull(principal); Objects.requireNonNull(expires);
        try (Connection c = transaction()) {
            try {
                if (!active(c, principal)) throw new SQLException("Session authority unavailable");
                try (var s = c.prepareStatement("INSERT INTO local_auth_sessions (token_hash,tenant_id,subject_id,authorization_revision,expires_at,revoked) VALUES (?,?,?,?,?,FALSE)")) {
                    s.setBytes(1, hash); s.setBytes(2, id(principal.tenant())); s.setBytes(3, id(principal.subject()));
                    s.setLong(4, principal.authorizationRevision()); s.setTimestamp(5, Timestamp.from(expires), utc());
                    if (s.executeUpdate() != 1) throw new SQLException("Session insert failed");
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { rollback(c, e); throw e; }
        }
    }

    @Override public LocalSessionTokens.Stored resolve(byte[] hash, Instant now) throws SQLException {
        validateHash(hash); Objects.requireNonNull(now);
        try (Connection c = transaction()) {
            try {
                LocalSessionTokens.Stored found = null;
                // A single locking read sees current membership and session together. Revocation and
                // membership writers serialize with this read until commit, not future operations.
                try (var s = c.prepareStatement("SELECT s.tenant_id,s.subject_id,s.authorization_revision,s.expires_at,s.revoked,m.state,m.revision FROM local_auth_sessions s JOIN memberships m ON m.tenant_id=s.tenant_id AND m.subject_id=s.subject_id WHERE s.token_hash=? FOR UPDATE")) {
                    s.setBytes(1, hash);
                    try (var r = s.executeQuery()) {
                        if (r.next()) {
                            long revision = r.getLong(3);
                            Timestamp expires = r.getTimestamp(4, utc());
                            if (expires != null && !r.getBoolean(5) && "active".equals(r.getString(6))
                                    && revision == r.getLong(7) && now.isBefore(expires.toInstant())) {
                                found = new LocalSessionTokens.Stored(new LocalSessionTokens.Principal(uuid(r.getBytes(1)), uuid(r.getBytes(2)), revision), expires.toInstant(), false);
                            }
                        }
                    }
                }
                c.commit(); return found;
            } catch (SQLException | RuntimeException e) { rollback(c, e); throw e; }
        }
    }

    @Override public void revoke(byte[] hash) throws SQLException {
        validateHash(hash);
        try (Connection c = transaction()) {
            try {
                try (var s = c.prepareStatement("UPDATE local_auth_sessions SET revoked=TRUE WHERE token_hash=? AND revoked=FALSE")) {
                    s.setBytes(1, hash); s.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { rollback(c, e); throw e; }
        }
    }

    private Connection transaction() throws SQLException {
        Connection c = source.getConnection();
        try { c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false); return c; }
        catch (SQLException e) { try { c.close(); } catch (SQLException close) { e.addSuppressed(close); } throw e; }
    }
    private boolean active(Connection c, LocalSessionTokens.Principal principal) throws SQLException {
        try (var s = c.prepareStatement("SELECT state,revision FROM memberships WHERE tenant_id=? AND subject_id=? FOR UPDATE")) {
            s.setBytes(1, id(principal.tenant())); s.setBytes(2, id(principal.subject()));
            try (var r = s.executeQuery()) { return r.next() && "active".equals(r.getString(1)) && r.getLong(2) == principal.authorizationRevision(); }
        }
    }
    private static byte[] id(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] bytes) throws SQLException {
        if (bytes == null || bytes.length != 16) throw new SQLException("Invalid session identity");
        ByteBuffer b = ByteBuffer.wrap(bytes); return new UUID(b.getLong(), b.getLong());
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static void validateHash(byte[] hash) { if (hash == null || hash.length != 32) throw new IllegalArgumentException("Invalid token hash"); }
    private static void rollback(Connection c, Throwable failure) { try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); } }
}
