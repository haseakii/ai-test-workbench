package io.aitest.runner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import javax.sql.DataSource;

/** Durable arbitration primitive only. Caller must authenticate and validate admission before accept.
 * A returned ACCEPTED is a journal fact, not permission to start or restart a process. */
public final class SubmissionJournal {
    public enum Result { ACCEPTED, CLOSED, CONFLICT, STALE_EPOCH, UNKNOWN }
    public enum Operation { ACCEPT, CLOSE }
    private static final long MAX_EPOCH = 9_007_199_254_740_991L;
    private final DataSource dataSource;

    public SubmissionJournal(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    public record Command(byte[] tenant, byte[] binding, byte[] key, byte[] hash, long epoch) {
        public Command {
            tenant = copy(tenant, 16, 16); binding = copy(binding, 16, 16);
            key = copy(key, 1, 128); hash = copy(hash, 32, 32);
            if (epoch < 0 || epoch > MAX_EPOCH) throw new IllegalArgumentException("invalid epoch");
        }
        private static byte[] copy(byte[] value, int min, int max) {
            if (value == null || value.length < min || value.length > max) throw new IllegalArgumentException("invalid identifier");
            return value.clone();
        }
        @Override public byte[] tenant() { return tenant.clone(); }
        @Override public byte[] binding() { return binding.clone(); }
        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }

    /** Serializes competing first accept/close using both unique keys. An ambiguous commit stays UNKNOWN. */
    public Result decide(Command command, Operation operation) throws SQLException {
        Objects.requireNonNull(command); Objects.requireNonNull(operation);
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean commitStarted = false;
            try (Connection c = dataSource.getConnection()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                try {
                    Row keyRow = byKey(c, command);
                    Result result;
                    if (keyRow != null) result = compare(keyRow, command);
                    else {
                        Row bindingRow = byBinding(c, command);
                        if (bindingRow != null) result = Result.CONFLICT;
                        else {
                            result = operation == Operation.ACCEPT ? Result.ACCEPTED : Result.CLOSED;
                            insert(c, command, result);
                        }
                    }
                    commitStarted = true;
                    c.commit();
                    return result;
                } catch (SQLException e) {
                    try { c.rollback(); } catch (SQLException suppressed) { return Result.UNKNOWN; }
                    if (commitStarted) return Result.UNKNOWN;
                    if (("23000".equals(e.getSQLState()) || "40001".equals(e.getSQLState())) && attempt < 2) continue;
                    throw e;
                }
            } catch (SQLException e) {
                // close() can fail after commit succeeded; callers must not infer non-acceptance.
                if (commitStarted) return Result.UNKNOWN;
                throw e;
            }
        }
        return Result.UNKNOWN;
    }

    /** A missing row is unknown; it is not a final non-acceptance proof. */
    public Result lookup(Command command) throws SQLException {
        Objects.requireNonNull(command);
        try (Connection c = dataSource.getConnection()) {
            Row row = byKey(c, command);
            return row == null ? Result.UNKNOWN : compare(row, command);
        }
    }

    private record Row(byte[] binding, byte[] hash, long epoch, String state) {}
    private static Result compare(Row row, Command command) throws SQLException {
        if (!Arrays.equals(row.binding, command.binding) || !Arrays.equals(row.hash, command.hash)) return Result.CONFLICT;
        if (row.epoch != command.epoch) return Result.STALE_EPOCH;
        return switch (row.state) {
            case "ACCEPTED" -> Result.ACCEPTED;
            case "CLOSED" -> Result.CLOSED;
            default -> throw new SQLException("unknown journal state");
        };
    }
    private static Row byKey(Connection c, Command cmd) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT binding_id,spec_hash,epoch,state FROM submission_journal WHERE tenant_id=? AND idempotency_key=?")) {
            s.setBytes(1, cmd.tenant); s.setBytes(2, cmd.key);
            try (ResultSet r = s.executeQuery()) { return r.next() ? new Row(r.getBytes(1), r.getBytes(2), r.getLong(3), r.getString(4)) : null; }
        }
    }
    private static Row byBinding(Connection c, Command cmd) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT binding_id,spec_hash,epoch,state FROM submission_journal WHERE tenant_id=? AND binding_id=?")) {
            s.setBytes(1, cmd.tenant); s.setBytes(2, cmd.binding);
            try (ResultSet r = s.executeQuery()) { return r.next() ? new Row(r.getBytes(1), r.getBytes(2), r.getLong(3), r.getString(4)) : null; }
        }
    }
    private static void insert(Connection c, Command cmd, Result result) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO submission_journal(tenant_id,binding_id,idempotency_key,spec_hash,state,epoch) VALUES(?,?,?,?,?,?)")) {
            s.setBytes(1, cmd.tenant); s.setBytes(2, cmd.binding); s.setBytes(3, cmd.key);
            s.setBytes(4, cmd.hash); s.setString(5, result.name()); s.setLong(6, cmd.epoch); s.executeUpdate();
        }
    }
}
