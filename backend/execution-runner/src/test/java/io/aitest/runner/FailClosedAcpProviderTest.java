package io.aitest.runner;

import io.aitest.contract.ExecutionProvider;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import static io.aitest.contract.ProviderTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class FailClosedAcpProviderTest {
    private static final String TENANT="00000000-0000-4000-8000-000000000001";
    private static final String OTHER="00000000-0000-4000-8000-000000000002";
    private CallContext context(String tenant) {
        return new CallContext("initial-draft","correlation",tenant,
            new CallContextSubjectContext("00000000-0000-4000-8000-000000000012","grant-ref"),"2026-10-28T00:00:00.000001Z");
    }
    private ProviderErrorNotSentOrAcceptedOrUnknown error(CompletionStage<? extends Result<?>> stage) {
        return assertInstanceOf(ProviderErrorNotSentOrAcceptedOrUnknown.class,
            assertInstanceOf(ResultFalse.class,stage.toCompletableFuture().join()).error());
    }
    @Test void authorityUnavailableFailsBeforeAllMethodsWithoutObservationOrLaunch() {
        ExecutionProvider p=new FailClosedAcpProvider(); var c=context(TENANT);
        List<CompletionStage<? extends Result<?>>> results=List.of(
            p.inspectCapabilities(c,null),p.validateBinding(c,null),p.start(c,null),p.lookupSubmission(c,null),
            p.closeSubmission(c,null),p.getSnapshot(c,null),p.readEvents(c,null),p.submitInput(c,null),
            p.cancel(c,null),p.preparePermissionResume(c,null),p.respondPermission(c,null),p.getArtifact(c,null));
        assertEquals(12, results.size());
        for(var result:results) assertEquals("UNAUTHENTICATED",error(result).code());
        assertEquals("not_sent",error(p.start(c,null)).acceptance());
        assertEquals("UNAUTHENTICATED",error(p.start(null,null)).code());
    }
    @Test void crossTenantAndRevokedGrantAreRejectedBeforeReturningCapabilityInformation() {
        var p=new FailClosedAcpProvider((c,method,request) ->
            TENANT.equals(c.tenantId()) && "valid-grant".equals(c.subjectContext().authorizationRef())
                ? FailClosedAcpProvider.Authorization.AUTHORIZED : FailClosedAcpProvider.Authorization.FORBIDDEN);
        var conn=new ConnectionRef("direct-acp","connection",1);
        assertEquals("FORBIDDEN",error(p.inspectCapabilities(context(OTHER),conn)).code());
        assertEquals("FORBIDDEN",error(p.start(context(TENANT),null)).code());
    }
    @Test void evenAuthorizedRequestCannotStartWithoutVerifiedIsolation() {
        var p=new FailClosedAcpProvider((c,method,request)->FailClosedAcpProvider.Authorization.AUTHORIZED);
        var result=error(p.start(context(TENANT),null));
        assertEquals("UNSUPPORTED",result.code()); assertEquals("not_sent",result.acceptance());
        assertEquals("UNSUPPORTED",error(p.inspectCapabilities(context(TENANT),new ConnectionRef("direct-acp","local",1))).code());
        assertEquals("unknown",error(p.lookupSubmission(context(TENANT),null)).acceptance());
    }
    @Test void authorityFailureIsRedactedAndNeverFallsThrough() {
        var p=new FailClosedAcpProvider((c,method,request)->{throw new IllegalStateException("sensitive authority detail");});
        var result=error(p.start(context(TENANT),null));
        assertEquals("UNAUTHENTICATED",result.code()); assertFalse(result.message().contains("sensitive"));
    }
}
