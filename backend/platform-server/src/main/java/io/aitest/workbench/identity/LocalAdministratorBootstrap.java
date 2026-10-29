package io.aitest.workbench.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/** Unregistered bootstrap primitive. The required, not-yet-created V11 table has exactly one
 * guard row: id=1, deployment_id BINARY(16), token_hash BINARY(32), expires_at DATETIME(6),
 * state provisioned/consumed, tenant_id BINARY(16) NULL. A separate trusted local provisioner
 * must install an expiring random-token hash and protect the guard against all other writers.
 * The runtime must verify a consumed guard and fixed tenant before treating this as initialized;
 * the existing production_boundary sentinel remains uninitialized. Neither provisioner nor
 * runtime verification currently exists. The current draft cannot run this primitive. */
final class LocalAdministratorBootstrap {
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    enum Outcome { INITIALIZED, REJECTED, UNKNOWN }
    interface Eligibility {
        /** Verify the exact trusted database, reviewed V11 schema, marker and migration history
         * on this same transaction. Production must fail closed until such an implementation exists. */
        boolean verify(Connection connection) throws SQLException;
    }
    private final DataSource source;
    private final Clock clock;
    private final Eligibility eligibility;

    LocalAdministratorBootstrap(DataSource source, Clock clock) {
        this(source, clock, connection -> false);
    }
    LocalAdministratorBootstrap(DataSource source, Clock clock, Eligibility eligibility) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        this.eligibility = Objects.requireNonNull(eligibility);
    }

    /** The caller must be a trusted local provisioner; no network or public endpoint may call this.
     * Guard material is persisted by a separate privileged provisioner and never accepted from HTTP.
     * Writers outside this protocol must be disabled while initialization is in progress. */
    Outcome initialize(byte[] token, UUID deployment, String login, String passwordHash) {
        if (token == null || token.length != 32 || deployment == null || login == null
                || !login.matches("[a-z0-9][a-z0-9._-]{2,63}") || passwordHash == null
                || !passwordHash.matches("\\{pbkdf2-sha256-600000-v1\\}[0-9a-f]{96}")) return Outcome.REJECTED;
        byte[] digest;
        try { digest = MessageDigest.getInstance("SHA-256").digest(token); }
        catch (NoSuchAlgorithmException impossible) { return Outcome.UNKNOWN; }
        boolean committing = false;
        try (Connection c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                if (!eligibility.verify(c) || !validBoundary(c) || !validGuard(c, deployment, digest) || !empty(c)) {
                    c.rollback(); return Outcome.REJECTED;
                }
                UUID subject = UUID.randomUUID();
                try (var s = c.prepareStatement("INSERT INTO tenants(id,revision) VALUES (?,1)")) { s.setBytes(1, id(TENANT)); if (s.executeUpdate()!=1) throw new SQLException("Bootstrap insert failed"); }
                try (var s = c.prepareStatement("INSERT INTO subjects(id,alias) VALUES (?,?)")) { s.setBytes(1,id(subject)); s.setBytes(2,login.getBytes(StandardCharsets.UTF_8)); if (s.executeUpdate()!=1) throw new SQLException("Bootstrap insert failed"); }
                try (var s = c.prepareStatement("INSERT INTO memberships(tenant_id,subject_id,role_name,state,revision) VALUES (?,?,'admin','active',1)")) { s.setBytes(1,id(TENANT)); s.setBytes(2,id(subject)); if (s.executeUpdate()!=1) throw new SQLException("Bootstrap insert failed"); }
                try (var s = c.prepareStatement("INSERT INTO local_auth_accounts(tenant_id,subject_id,login_name,password_hash,password_revision,enabled) VALUES (?,?,?,?,1,TRUE)")) { s.setBytes(1,id(TENANT)); s.setBytes(2,id(subject)); s.setBytes(3,login.getBytes(StandardCharsets.UTF_8)); s.setBytes(4,passwordHash.getBytes(StandardCharsets.UTF_8)); if (s.executeUpdate()!=1) throw new SQLException("Bootstrap insert failed"); }
                try (var s = c.prepareStatement("INSERT INTO audit_entries(tenant_id,id,subject_id,action_name,object_id,detail_json,created_at) VALUES (?,?,?,'identity.bootstrap',?,'{}',?)")) { s.setBytes(1,id(TENANT)); s.setBytes(2,id(UUID.randomUUID())); s.setBytes(3,id(subject)); s.setBytes(4,id(subject)); s.setTimestamp(5,Timestamp.from(now()),utc()); if (s.executeUpdate()!=1) throw new SQLException("Bootstrap insert failed"); }
                // Evaluate deadline again at consumption using the database's UTC clock: a token
                // expiring during slow account/password/audit writes is never consumed successfully.
                try (var s = c.prepareStatement("UPDATE production_bootstrap_guard SET state='consumed',token_hash=NULL,tenant_id=? WHERE id=1 AND state='provisioned' AND deployment_id=? AND expires_at > UTC_TIMESTAMP(6)")) {
                    s.setBytes(1,id(TENANT)); s.setBytes(2,id(deployment)); if (s.executeUpdate()!=1) throw new SQLException("Bootstrap guard changed");
                }
                committing = true; c.commit(); return Outcome.INITIALIZED;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException ignored) { return Outcome.UNKNOWN; }
                return committing ? Outcome.UNKNOWN : Outcome.REJECTED;
            }
        } catch (SQLException | RuntimeException failure) { return Outcome.UNKNOWN; }
        finally { java.util.Arrays.fill(digest, (byte)0); }
    }

    private boolean validGuard(Connection c, UUID deployment, byte[] digest) throws SQLException {
        try (var s=c.prepareStatement("SELECT deployment_id,token_hash,expires_at,state,tenant_id FROM production_bootstrap_guard WHERE id=1 FOR UPDATE"); var r=s.executeQuery()) {
            if (!r.next()) return false;
            byte[] stored=r.getBytes(2); Timestamp expiry=r.getTimestamp(3,utc());
            return MessageDigest.isEqual(id(deployment),r.getBytes(1)) && stored != null && stored.length==32
                    && MessageDigest.isEqual(digest,stored) && expiry != null && now().isBefore(expiry.toInstant())
                    && "provisioned".equals(r.getString(4)) && r.getBytes(5)==null && !r.next();
        }
    }
    private static boolean validBoundary(Connection c) throws SQLException {
        try (var s=c.prepareStatement("SELECT mode_name,deployment_id,tenant_id FROM production_boundary WHERE id=1 FOR UPDATE"); var r=s.executeQuery()) {
            return r.next() && "production_uninitialized".equals(r.getString(1)) && r.getBytes(2)==null && r.getBytes(3)==null && !r.next();
        }
    }
    private static boolean empty(Connection c) throws SQLException {
        // SERIALIZABLE locks these indexed ranges. No worker or external writer may be enabled.
        for (String table : new String[]{"tenants","subjects","memberships","config_objects","config_versions",
                "grants","commands","conversations","messages","platform_events","attachments","node_runs",
                "execution_bindings","approvals","quota_accounts","active_slots","quota_reservations","outbox",
                "admission_tickets","fixture_submissions","provider_events","artifacts","verifications","audit_entries",
                "content_leases","quota_policies","context_manifests","understanding_runs","fixture_events",
                "fixture_artifacts","fixture_commands","resource_operation_consumptions","local_auth_sessions",
                "local_auth_accounts","local_login_buckets"}) {
            try (var s=c.prepareStatement("SELECT 1 FROM " + table + " LIMIT 1 FOR UPDATE"); var r=s.executeQuery()) { if (r.next()) return false; }
        }
        return true;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static byte[] id(UUID u) { return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array(); }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
