package io.aitest.workbench.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;

/** Unregistered internal primitive. A verified password is not a session or resource authorization.
 * The caller must resolve source identity at a trusted network boundary. Session issuance must
 * recheck account enabled, passwordRevision and membership revision in its own transaction. */
final class JdbcLocalAuthentication {
    enum Status { VERIFIED, DENIED, UNAVAILABLE }
    /** In-process, single-use login evidence; it must never cross a client boundary. */
    static final class Verified {
        private final LocalSessionTokens.Principal principal;
        private final long passwordRevision;
        private final java.util.concurrent.atomic.AtomicBoolean used = new java.util.concurrent.atomic.AtomicBoolean();
        Verified(LocalSessionTokens.Principal principal, long passwordRevision) {
            this.principal=Objects.requireNonNull(principal);
            if (passwordRevision < 0 || passwordRevision > MAX_REVISION) throw new IllegalArgumentException("Invalid password revision");
            this.passwordRevision=passwordRevision;
        }
        LocalSessionTokens.Principal principal() { return principal; }
        long passwordRevision() { return passwordRevision; }
        boolean consume() { return used.compareAndSet(false,true); }
    }
    record Result(Status status, Verified verified) {
        Result { if ((status == Status.VERIFIED) != (verified != null)) throw new IllegalArgumentException(); }
        static Result denied() { return new Result(Status.DENIED, null); }
        static Result unavailable() { return new Result(Status.UNAVAILABLE, null); }
    }
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final int MAX_ATTEMPTS = 8;
    private static final String DUMMY_PASSWORD = "local-auth-dummy-password-never-accepted";
    private final DataSource source;
    private final Clock clock;
    private final LocalPasswordCodec passwords;
    private final String dummyHash;

    JdbcLocalAuthentication(DataSource source, Clock clock) {
        this.source = Objects.requireNonNull(source); this.clock = Objects.requireNonNull(clock);
        this.passwords = new LocalPasswordCodec(); this.dummyHash = passwords.hash(DUMMY_PASSWORD);
    }

    /** sourceId must be a canonical address established by the server, not a forwarded header.
     * Only normalized lowercase ASCII logins are accepted; failures do not reveal account existence. */
    Result verify(UUID tenant, String login, String sourceId, String password) {
        if (tenant == null || login == null || !login.matches("[a-z0-9][a-z0-9._-]{2,63}")
                || sourceId == null || sourceId.isBlank() || sourceId.length() > 128
                || sourceId.chars().anyMatch(Character::isISOControl)) return Result.denied();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try (Connection c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try {
                // Global lock ordering: kind 0 then kind 1. A bounded hash collision can only deny.
                boolean loginAllowed = reserve(c, tenant, 0, slot(tenant, 0, login), now);
                boolean sourceAllowed = reserve(c, tenant, 1, slot(tenant, 1, sourceId), now);
                Result result = Result.denied();
                if (loginAllowed && sourceAllowed) {
                    Account account = account(c, tenant, login);
                    String candidate = validCandidate(password) ? password : DUMMY_PASSWORD;
                    String hash = account != null && wellFormed(account.hash()) ? account.hash() : dummyHash;
                    boolean matched = passwords.matches(candidate, hash);
                    if (account != null && wellFormed(account.hash()) && validCandidate(password) && matched && account.enabled()
                            && account.active() && account.passwordRevision() >= 0 && account.passwordRevision() <= MAX_REVISION
                            && account.membershipRevision() >= 0 && account.membershipRevision() <= MAX_REVISION) {
                        var principal = new LocalSessionTokens.Principal(tenant, account.subject(), account.membershipRevision());
                        result = new Result(Status.VERIFIED, new Verified(principal, account.passwordRevision()));
                    }
                }
                c.commit();
                return result;
            } catch (Exception failure) {
                try { c.rollback(); } catch (SQLException ignored) { }
                return Result.unavailable();
            }
        } catch (SQLException | RuntimeException failure) { return Result.unavailable(); }
    }

    private record Account(UUID subject, String hash, long passwordRevision, boolean enabled, boolean active, long membershipRevision) {}
    private static Account account(Connection c, UUID tenant, String login) throws SQLException {
        try (var s = c.prepareStatement("SELECT a.subject_id,a.password_hash,a.password_revision,a.enabled,m.state,m.revision FROM local_auth_accounts a JOIN memberships m ON m.tenant_id=a.tenant_id AND m.subject_id=a.subject_id WHERE a.tenant_id=? AND a.login_name=? FOR UPDATE")) {
            s.setBytes(1, id(tenant)); s.setBytes(2, login.getBytes(StandardCharsets.UTF_8));
            try (var r = s.executeQuery()) {
                if (!r.next()) return null;
                byte[] subject = r.getBytes(1);
                if (subject == null || subject.length != 16) throw new SQLException("Invalid account subject");
                ByteBuffer b = ByteBuffer.wrap(subject);
                return new Account(new UUID(b.getLong(),b.getLong()), r.getString(2), r.getLong(3), r.getBoolean(4), "active".equals(r.getString(5)), r.getLong(6));
            }
        }
    }
    private static boolean reserve(Connection c, UUID tenant, int kind, int slot, Instant now) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO local_login_buckets(tenant_id,bucket_kind,slot,attempts,window_until) VALUES(?,?,?,0,?) ON DUPLICATE KEY UPDATE slot=slot")) {
            s.setBytes(1,id(tenant)); s.setInt(2,kind); s.setInt(3,slot); s.setTimestamp(4,Timestamp.from(now.plusSeconds(60)),utc()); s.executeUpdate();
        }
        int attempts; Instant until;
        try (var s = c.prepareStatement("SELECT attempts,window_until FROM local_login_buckets WHERE tenant_id=? AND bucket_kind=? AND slot=? FOR UPDATE")) {
            s.setBytes(1,id(tenant)); s.setInt(2,kind); s.setInt(3,slot);
            try (var r=s.executeQuery()) { if (!r.next()) throw new SQLException("Missing rate bucket"); attempts=r.getInt(1); var time=r.getTimestamp(2,utc()); if (time==null) throw new SQLException("Missing rate window"); until=time.toInstant(); }
        }
        if (!now.isBefore(until)) { attempts=0; until=now.plusSeconds(60); }
        if (attempts >= MAX_ATTEMPTS) return false;
        try (var s = c.prepareStatement("UPDATE local_login_buckets SET attempts=?,window_until=? WHERE tenant_id=? AND bucket_kind=? AND slot=?")) {
            s.setInt(1,attempts+1); s.setTimestamp(2,Timestamp.from(until),utc()); s.setBytes(3,id(tenant)); s.setInt(4,kind); s.setInt(5,slot);
            if (s.executeUpdate()!=1) throw new SQLException("Rate update failed");
        }
        return true;
    }
    private static int slot(UUID tenant, int kind, String value) {
        try {
            var hash=MessageDigest.getInstance("SHA-256"); hash.update(id(tenant)); hash.update((byte)kind);
            byte[] bytes=hash.digest(value.getBytes(StandardCharsets.UTF_8));
            return ((bytes[0]&0xff)<<4) | ((bytes[1]&0xff)>>>4);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static boolean wellFormed(String hash) { return hash != null && hash.matches("\\{pbkdf2-sha256-600000-v1\\}[0-9a-f]{96}"); }
    private static boolean validCandidate(String value) {
        if (value == null || value.codePointCount(0,value.length()) < 12 || value.codePointCount(0,value.length()) > 128) return false;
        for (int i=0;i<value.length();i++) { char ch=value.charAt(i); if (Character.isHighSurrogate(ch)) { if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; } else if (Character.isLowSurrogate(ch) || Character.isISOControl(ch)) return false; }
        return true;
    }
    private static byte[] id(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
