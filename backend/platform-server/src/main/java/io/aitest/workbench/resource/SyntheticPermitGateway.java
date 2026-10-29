package io.aitest.workbench.resource;

import io.aitest.contract.CanonicalJson;
import java.util.Objects;

/** Internal fixture coordinator only. No bean is registered and no real resource is exposed.
 * The resolver must authenticate the caller and resolve a server-owned permit, never deserialize
 * an incoming permit. The consumer must lock and recheck current authority in its transaction.
 * Even after consumption, a real resource adapter must perform its own fencing and revocation check. */
final class SyntheticPermitGateway {
    interface PermitResolver {
        OperationPermitPolicy.Permit authenticatedPermit(String subject, String permitRef);
    }
    interface Consumer {
        JdbcPermitConsumption.Outcome consume(OperationPermitPolicy.Permit permit);
    }
    interface FixtureTool {
        /** Only an inert, synthetic test fixture may be supplied here. */
        String run(OperationPermitPolicy.Scope scope, String canonicalParameters) throws Exception;
    }
    enum Status { COMPLETED, REJECTED, UNKNOWN }
    record Result(Status status, String output) {
        Result { if (status != Status.COMPLETED && output != null) throw new IllegalArgumentException(); }
    }
    private final PermitResolver resolver;
    private final Consumer consumer;
    private final FixtureTool fixture;

    SyntheticPermitGateway(PermitResolver resolver, Consumer consumer, FixtureTool fixture) {
        this.resolver=Objects.requireNonNull(resolver);
        this.consumer=Objects.requireNonNull(consumer);
        this.fixture=Objects.requireNonNull(fixture);
    }

    Result execute(String authenticatedSubject, String permitRef, String parametersJson) {
        if (authenticatedSubject == null || authenticatedSubject.isBlank() || permitRef == null || permitRef.isBlank()
                || parametersJson == null || parametersJson.length() > 65536) return new Result(Status.REJECTED, null);
        final OperationPermitPolicy.Permit permit;
        final String canonical;
        try {
            permit=resolver.authenticatedPermit(authenticatedSubject,permitRef);
            canonical=CanonicalJson.canonicalize(parametersJson);
        } catch (RuntimeException failure) { return new Result(Status.REJECTED, null); }
        if (permit == null || permit.scope() == null || !authenticatedSubject.equals(permit.scope().principal())
                || !CanonicalJson.hashBytes(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(permit.scope().parametersHash()))
            return new Result(Status.REJECTED, null);
        final JdbcPermitConsumption.Outcome outcome;
        try { outcome=consumer.consume(permit); }
        catch (RuntimeException failure) { return new Result(Status.UNKNOWN, null); }
        if (outcome != JdbcPermitConsumption.Outcome.CONSUMED)
            return new Result(outcome == JdbcPermitConsumption.Outcome.REJECTED ? Status.REJECTED : Status.UNKNOWN, null);
        try { return new Result(Status.COMPLETED, fixture.run(permit.scope(), canonical)); }
        catch (Exception failure) { return new Result(Status.UNKNOWN, null); }
    }
}
