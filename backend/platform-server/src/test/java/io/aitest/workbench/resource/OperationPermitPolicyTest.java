package io.aitest.workbench.resource;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.aitest.workbench.resource.OperationPermitPolicy.*;

class OperationPermitPolicyTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Scope scope = new Scope("tenant", "principal", "binding", 1, "env", "op", "a".repeat(64), "resource", "tool", "b".repeat(64), 4);
    private final Permit permit = new Permit(scope, now.minusSeconds(1), now.plusSeconds(1));
    private Current current(Scope requested) { return new Current(true, true, false, false, requested); }

    @Test void acceptsOnlyCurrentScopeBeforeExpiry() {
        assertEquals(Decision.ALLOW_CONSUMPTION, evaluate(permit, current(scope), now));
        assertEquals(Decision.EXPIRED, evaluate(permit, current(scope), now.plusSeconds(1)));
        assertEquals(Decision.EXPIRED, evaluate(permit, current(scope), now.minusSeconds(2)));
        var changed = new Scope("tenant", "principal", "binding", 2, "env", "op", "a".repeat(64), "resource", "tool", "b".repeat(64), 4);
        assertEquals(Decision.STALE, evaluate(permit, current(changed), now));
    }
    @Test void revocationStopAndConsumptionFailClosed() {
        assertEquals(Decision.FORBIDDEN, evaluate(permit, new Current(true, false, false, false, scope), now));
        assertEquals(Decision.FORBIDDEN, evaluate(permit, new Current(true, true, true, false, scope), now));
        assertEquals(Decision.FORBIDDEN, evaluate(permit, new Current(false, true, false, false, scope), now));
        assertEquals(Decision.ALREADY_CONSUMED, evaluate(permit, new Current(true, true, false, true, scope), now));
        assertEquals(Decision.INVALID, evaluate(null, current(scope), now));
    }
    @Test void rejectsUnsafeIntegersMalformedHashesAndSubmicrosecondTimes() {
        var maximum = new Scope("tenant", "principal", "binding", 9_007_199_254_740_991L, "env", "op", "a".repeat(64), "resource", "tool", "b".repeat(64), 9_007_199_254_740_991L);
        assertEquals(Decision.ALLOW_CONSUMPTION, evaluate(new Permit(maximum, now, now.plusSeconds(1)), current(maximum), now));
        var overflow = new Scope("tenant", "principal", "binding", 9_007_199_254_740_992L, "env", "op", "a".repeat(64), "resource", "tool", "b".repeat(64), 4);
        assertEquals(Decision.INVALID, evaluate(new Permit(overflow, now, now.plusSeconds(1)), current(overflow), now));
        var malformed = new Scope("tenant", "principal", "binding", 1, "env", "op", "A".repeat(64), "resource", "tool", "b".repeat(64), 4);
        assertEquals(Decision.INVALID, evaluate(new Permit(malformed, now, now.plusSeconds(1)), current(malformed), now));
        assertEquals(Decision.INVALID, evaluate(new Permit(scope, now, now.plusNanos(1)), current(scope), now));
    }
}
