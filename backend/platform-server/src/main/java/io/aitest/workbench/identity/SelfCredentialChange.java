package io.aitest.workbench.identity;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import java.nio.ByteBuffer;
import javax.sql.DataSource;

/** Unregistered, self-service primitive. Requires a server-held session token, never a client-supplied subject.
 * The HTTP layer must independently enforce TLS, CSRF, and rate limiting before exposing this operation. */
final class SelfCredentialChange {
    enum Result { CHANGED, DENIED, UNKNOWN }
    private static final long MAX = 9_007_199_254_740_991L;
    private final DataSource source;
    private final Clock clock;
    private final LocalPasswordCodec passwords;

    SelfCredentialChange(DataSource source, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        this.passwords = new LocalPasswordCodec();
    }

    Result change(String sessionToken, String currentPassword, String nextPassword) {
        byte[] tokenHash = hashToken(sessionToken);
        if (tokenHash == null) return Result.DENIED;
        final String nextHash;
        try { nextHash = passwords.hash(nextPassword); }
        catch (IllegalArgumentException failure) { return Result.DENIED; }
        try (Connection c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                byte[] tenant, subject; long memberRevision, passwordRevision;
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                try (var s = c.prepareStatement("SELECT s.tenant_id,s.subject_id,s.authorization_revision,s.expires_at,s.revoked,m.state,m.revision,a.enabled,a.password_revision,a.password_hash FROM local_auth_sessions s JOIN memberships m ON m.tenant_id=s.tenant_id AND m.subject_id=s.subject_id JOIN local_auth_accounts a ON a.tenant_id=s.tenant_id AND a.subject_id=s.subject_id WHERE s.token_hash=? FOR UPDATE")) {
                    s.setBytes(1, tokenHash);
                    try (var r = s.executeQuery()) {
                        if (!r.next()) { c.rollback(); return Result.DENIED; }
                        tenant = r.getBytes(1); subject = r.getBytes(2); memberRevision = r.getLong(3);
                        Timestamp expires = r.getTimestamp(4, utc()); passwordRevision = r.getLong(9);
                        if (tenant == null || tenant.length != 16 || subject == null || subject.length != 16
                                || expires == null || !now.isBefore(expires.toInstant()) || r.getBoolean(5)
                                || !"active".equals(r.getString(6)) || memberRevision != r.getLong(7)
                                || !r.getBoolean(8) || memberRevision < 0 || memberRevision >= MAX
                                || passwordRevision < 0 || passwordRevision >= MAX
                                || !passwords.matches(currentPassword, r.getString(10))) {
                            c.rollback(); return Result.DENIED;
                        }
                    }
                }
                try (var s = c.prepareStatement("UPDATE local_auth_accounts SET password_hash=?,password_revision=password_revision+1 WHERE tenant_id=? AND subject_id=? AND enabled=TRUE AND password_revision=?")) {
                    s.setString(1, nextHash); s.setBytes(2, tenant); s.setBytes(3, subject); s.setLong(4, passwordRevision);
                    if (s.executeUpdate() != 1) throw new SQLException("Account changed");
                }
                try (var s = c.prepareStatement("UPDATE memberships SET revision=revision+1 WHERE tenant_id=? AND subject_id=? AND state='active' AND revision=?")) {
                    s.setBytes(1, tenant); s.setBytes(2, subject); s.setLong(3, memberRevision);
                    if (s.executeUpdate() != 1) throw new SQLException("Membership changed");
                }
                try (var s = c.prepareStatement("UPDATE local_auth_sessions SET revoked=TRUE WHERE tenant_id=? AND subject_id=? AND revoked=FALSE")) {
                    s.setBytes(1, tenant); s.setBytes(2, subject); s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO audit_entries (tenant_id,id,subject_id,action_name,object_id,detail_json,created_at) VALUES (?,?,?,?,?,?,?)")) {
                    s.setBytes(1, tenant); s.setBytes(2, id(UUID.randomUUID())); s.setBytes(3, subject);
                    s.setString(4, "identity.password.change"); s.setBytes(5, subject); s.setString(6, "{}"); s.setTimestamp(7, Timestamp.from(now), utc());
                    if (s.executeUpdate() != 1) throw new SQLException("Audit failed");
                }
                c.commit();
                return Result.CHANGED;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException ignored) { }
                return Result.UNKNOWN;
            }
        } catch (SQLException failure) {
            // Includes close failure after a successful commit; do not claim the outcome.
            return Result.UNKNOWN;
        } finally { java.util.Arrays.fill(tokenHash, (byte) 0); }
    }

    private static byte[] hashToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) return null;
            try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
            finally { java.util.Arrays.fill(bytes, (byte) 0); }
        } catch (Exception failure) { return null; }
    }
    private static byte[] id(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
