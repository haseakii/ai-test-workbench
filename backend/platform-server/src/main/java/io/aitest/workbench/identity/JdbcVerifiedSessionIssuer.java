package io.aitest.workbench.identity;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;

/** Internal only: not a registered login endpoint. Verified is an in-process short-lived result,
 * not a bearer proof and must never be accepted from clients or cached across requests. */
final class JdbcVerifiedSessionIssuer {
    private final DataSource source;
    private final Clock clock;
    private final Duration lifetime;
    JdbcVerifiedSessionIssuer(DataSource source, Clock clock, Duration lifetime) {
        this.source=Objects.requireNonNull(source); this.clock=Objects.requireNonNull(clock); this.lifetime=Objects.requireNonNull(lifetime);
    }

    /** Lock account and membership, recheck both revisions, insert the hash and confirm commit
     * before returning a fresh token. Any failure, including unknown commit, returns no token. */
    LocalSessionTokens.Issued issue(JdbcLocalAuthentication.Verified verified) {
        Objects.requireNonNull(verified); Objects.requireNonNull(verified.principal());
        if (!verified.consume()) throw new IllegalStateException("Session issuance unavailable");
        var store = new LocalSessionTokens.Store() {
            @Override public void create(byte[] hash, LocalSessionTokens.Principal principal, Instant expires) throws Exception {
                try (Connection c=source.getConnection()) {
                    c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
                    try {
                        try (var s=c.prepareStatement("SELECT a.enabled,a.password_revision,m.state,m.revision FROM local_auth_accounts a JOIN memberships m ON m.tenant_id=a.tenant_id AND m.subject_id=a.subject_id WHERE a.tenant_id=? AND a.subject_id=? FOR UPDATE")) {
                            s.setBytes(1,id(principal.tenant())); s.setBytes(2,id(principal.subject()));
                            try (var r=s.executeQuery()) {
                                if (!r.next() || !r.getBoolean(1) || r.getLong(2)!=verified.passwordRevision()
                                        || !"active".equals(r.getString(3)) || r.getLong(4)!=principal.authorizationRevision())
                                    throw new SQLException("Current account authorization unavailable");
                            }
                        }
                        try (var s=c.prepareStatement("INSERT INTO local_auth_sessions (token_hash,tenant_id,subject_id,authorization_revision,expires_at,revoked) VALUES (?,?,?,?,?,FALSE)")) {
                            s.setBytes(1,hash); s.setBytes(2,id(principal.tenant())); s.setBytes(3,id(principal.subject()));
                            s.setLong(4,principal.authorizationRevision()); s.setTimestamp(5,Timestamp.from(expires),utc());
                            if(s.executeUpdate()!=1) throw new SQLException("Session insert failed");
                        }
                        c.commit();
                    } catch (Exception failure) {
                        try { c.rollback(); } catch (SQLException suppressed) { failure.addSuppressed(suppressed); }
                        throw failure;
                    }
                }
            }
            @Override public LocalSessionTokens.Stored resolve(byte[] hash,Instant now) { throw new UnsupportedOperationException(); }
            @Override public void revoke(byte[] hash) { throw new UnsupportedOperationException(); }
        };
        return new LocalSessionTokens(store,clock,lifetime).issue(verified.principal());
    }

    private static byte[] id(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
