package io.aitest.workbench.resource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/** Internal infrastructure; it is deliberately not registered as an application service. */
final class JdbcPermitConsumption {
    /** Must lock the authoritative principal, binding, authorization and stop records on this connection. */
    interface LockedAuthority {
        OperationPermitPolicy.Current lockAndRead(Connection connection, OperationPermitPolicy.Permit permit) throws SQLException;
    }
    enum Outcome { CONSUMED, REJECTED, UNKNOWN }
    private final DataSource source;
    private final LockedAuthority authority;
    private final Clock clock;

    JdbcPermitConsumption(DataSource source, LockedAuthority authority, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
    }

    Outcome consume(OperationPermitPolicy.Permit permit) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var current = authority.lockAndRead(connection, permit);
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                if (OperationPermitPolicy.evaluate(permit, current, now) != OperationPermitPolicy.Decision.ALLOW_CONSUMPTION
                        || !fits(permit.scope())) {
                    connection.rollback();
                    return Outcome.REJECTED;
                }
                var scope = permit.scope();
                try (var statement = connection.prepareStatement("INSERT INTO resource_operation_consumptions "
                        + "(tenant_id,operation_id,operation_hash,principal_id,binding_id,environment_id,resource_id,tool_id,parameters_hash,ownership_epoch,authorization_revision,consumed_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    statement.setBytes(1, bytes(scope.tenant()));
                    statement.setBytes(2, bytes(scope.operationId()));
                    statement.setBytes(3, HexFormat.of().parseHex(scope.operationHash()));
                    statement.setBytes(4, bytes(scope.principal()));
                    statement.setBytes(5, bytes(scope.binding()));
                    statement.setBytes(6, bytes(scope.environment()));
                    statement.setBytes(7, bytes(scope.resource()));
                    statement.setBytes(8, bytes(scope.tool()));
                    statement.setBytes(9, HexFormat.of().parseHex(scope.parametersHash()));
                    statement.setLong(10, scope.epoch());
                    statement.setLong(11, scope.authorizationRevision());
                    statement.setTimestamp(12, Timestamp.from(now), java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
                    if (statement.executeUpdate() != 1) throw new SQLException("consumption not recorded");
                }
                connection.commit();
                return Outcome.CONSUMED;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                // Includes commit uncertainty and duplicate-key races. Never start a tool from this result.
                return Outcome.UNKNOWN;
            }
        } catch (SQLException failure) {
            return Outcome.UNKNOWN;
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static boolean fits(OperationPermitPolicy.Scope s) {
        return bytes(s.tenant()).length <= 128 && bytes(s.operationId()).length <= 128
                && bytes(s.principal()).length <= 128 && bytes(s.binding()).length <= 128
                && bytes(s.environment()).length <= 128 && bytes(s.resource()).length <= 128 && bytes(s.tool()).length <= 128;
    }
}
