package io.aitest.runner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.security.SecureRandom;

/** Durable single-use launch arbitration, not authorization or OS fencing.
 * Only the invocation that commits a new intent may proceed to its one launch attempt.
 * A crash after this point requires process reconciliation; the intent is never reset. */
public final class StartOwnership {
    public enum Result { NEW_INTENT_COMMITTED, RECONCILE, UNKNOWN }
    private final DataSource source;
    public StartOwnership(DataSource source) { this.source = Objects.requireNonNull(source); }

    /** Must be called only after current admission and resource isolation were verified.
     * A caller must never cache/replay NEW_INTENT_COMMITTED across retries or restarts. */
    public Result claim(SubmissionJournal.Command command) throws SQLException {
        Objects.requireNonNull(command);
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        boolean commitStarted = false;
        try (Connection c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                int changed;
                try (var statement = c.prepareStatement("UPDATE submission_journal SET start_intent=? WHERE tenant_id=? AND binding_id=? AND idempotency_key=? AND spec_hash=? AND epoch=? AND state='ACCEPTED' AND start_intent IS NULL")) {
                    statement.setBytes(1, nonce);
                    statement.setBytes(2, command.tenant());
                    statement.setBytes(3, command.binding());
                    statement.setBytes(4, command.key());
                    statement.setBytes(5, command.hash());
                    statement.setLong(6, command.epoch());
                    changed = statement.executeUpdate();
                }
                if (changed != 0 && changed != 1) throw new SQLException("invalid start arbitration row count");
                commitStarted = true;
                c.commit();
                return changed == 1 ? Result.NEW_INTENT_COMMITTED : Result.RECONCILE;
            } catch (SQLException failure) {
                try { c.rollback(); } catch (SQLException rollbackFailure) { return Result.UNKNOWN; }
                if (commitStarted) return Result.UNKNOWN;
                throw failure;
            }
        } catch (SQLException failure) {
            if (commitStarted) return Result.UNKNOWN;
            throw failure;
        }
    }
}
