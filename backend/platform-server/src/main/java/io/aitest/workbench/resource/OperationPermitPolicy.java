package io.aitest.workbench.resource;

import java.time.Instant;

/** Pure precondition check. Callers must lock current authority and consume atomically. */
public final class OperationPermitPolicy {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private OperationPermitPolicy() {}

    public record Scope(String tenant, String principal, String binding, long epoch,
                        String environment, String operationId, String operationHash,
                        String resource, String tool, String parametersHash,
                        long authorizationRevision) {}
    public record Permit(Scope scope, Instant notBefore, Instant expiresAt) {}
    public record Current(boolean authenticated, boolean authorized, boolean stopped,
                          boolean consumed, Scope requested) {}
    public enum Decision { ALLOW_CONSUMPTION, INVALID, EXPIRED, FORBIDDEN, STALE, ALREADY_CONSUMED }

    /** Does not authorize execution: durable, serialized consumption is also required. */
    public static Decision evaluate(Permit permit, Current current, Instant now) {
        if (permit == null || current == null || now == null || !valid(permit.scope())
                || !valid(current.requested()) || permit.notBefore() == null || permit.expiresAt() == null
                || !microsecond(permit.notBefore()) || !microsecond(permit.expiresAt())
                || !permit.notBefore().isBefore(permit.expiresAt())) return Decision.INVALID;
        if (!current.authenticated() || !current.authorized() || current.stopped()) return Decision.FORBIDDEN;
        if (!permit.scope().equals(current.requested())) return Decision.STALE;
        if (now.isBefore(permit.notBefore()) || !now.isBefore(permit.expiresAt())) return Decision.EXPIRED;
        if (current.consumed()) return Decision.ALREADY_CONSUMED;
        return Decision.ALLOW_CONSUMPTION;
    }

    private static boolean valid(Scope s) {
        return s != null && safe(s.epoch()) && safe(s.authorizationRevision())
                && present(s.tenant()) && present(s.principal()) && present(s.binding())
                && present(s.environment()) && present(s.operationId()) && hash(s.operationHash())
                && present(s.resource()) && present(s.tool()) && hash(s.parametersHash());
    }
    private static boolean safe(long n) { return n >= 0 && n <= MAX_SAFE_INTEGER; }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean microsecond(Instant time) { return time.getNano() % 1_000 == 0; }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
