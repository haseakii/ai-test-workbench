package io.aitest.workbench.resource;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JdbcPermitConsumptionTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final OperationPermitPolicy.Scope scope = new OperationPermitPolicy.Scope("tenant", "principal", "binding", 1, "env", "op", "a".repeat(64), "resource", "tool", "b".repeat(64), 4);
    private final OperationPermitPolicy.Permit permit = new OperationPermitPolicy.Permit(scope, now.minusSeconds(1), now.plusSeconds(1));
    private final OperationPermitPolicy.Current current = new OperationPermitPolicy.Current(true, true, false, false, scope);
    static class Fixture {
        final List<String> calls = new ArrayList<>(); boolean failCommit, failInsert;
        final PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p,m,a)-> {
            if (m.getName().equals("executeUpdate")) { calls.add("insert"); if (failInsert) throw new SQLException("duplicate"); return 1; }
            return null;
        });
        final Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a)-> {
            switch (m.getName()) {
                case "setAutoCommit": calls.add("begin"); break;
                case "prepareStatement": return statement;
                case "commit": calls.add("commit"); if (failCommit) throw new SQLException("commit unknown"); break;
                case "rollback": calls.add("rollback"); break;
            }
            return null;
        });
        final DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (p,m,a)-> m.getName().equals("getConnection") ? connection : null);
    }
    @Test void committedConsumptionIsOnlySuccess() {
        var f = new Fixture();
        var consumer = new JdbcPermitConsumption(f.source, (c,p)-> { assertSame(f.connection,c); return current; }, Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(JdbcPermitConsumption.Outcome.CONSUMED, consumer.consume(permit));
        assertEquals(List.of("begin", "insert", "commit"), f.calls);
    }
    @Test void revokedAuthorityNeverInserts() {
        var f = new Fixture();
        var consumer = new JdbcPermitConsumption(f.source, (c,p)->new OperationPermitPolicy.Current(true,false,false,false,scope), Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(JdbcPermitConsumption.Outcome.REJECTED, consumer.consume(permit));
        assertEquals(List.of("begin", "rollback"), f.calls);
    }
    @Test void commitFailureAndInsertConflictAreUnknown() {
        var f = new Fixture(); f.failCommit = true;
        var consumer = new JdbcPermitConsumption(f.source, (c,p)->current, Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(JdbcPermitConsumption.Outcome.UNKNOWN, consumer.consume(permit));
        f.failInsert = true;
        assertEquals(JdbcPermitConsumption.Outcome.UNKNOWN, consumer.consume(permit));
        assertEquals(List.of("begin", "insert", "commit", "rollback", "begin", "insert", "rollback"), f.calls);
    }
}
