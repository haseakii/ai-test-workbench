package io.aitest.runner;

import io.aitest.contract.ExecutionProvider;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static io.aitest.contract.ProviderTypes.*;

/** Unregistered placeholder: no method launches an agent, calls a journal, or reports capabilities.
 * A future registered provider must replace this with authenticated, resource-scoped operations. */
final class FailClosedAcpProvider implements ExecutionProvider {
    enum Authorization { UNAUTHENTICATED, FORBIDDEN, AUTHORIZED }
    interface Authority {
        /** Server-side identity and request-resource verification; never trust caller-supplied identity alone. */
        Authorization verify(CallContext context, String method, Object request);
    }
    private final Authority authority;
    FailClosedAcpProvider() { authority = (context, method, request) -> Authorization.UNAUTHENTICATED; }
    FailClosedAcpProvider(Authority authority) { this.authority = Objects.requireNonNull(authority); }

    private <T> CompletionStage<Result<T>> reject(CallContext context, String method, Object request, String phase) {
        Authorization authorization = Authorization.UNAUTHENTICATED;
        if (context != null) {
            try { authorization = authority.verify(context, method, request); }
            catch (RuntimeException ignored) { /* Authority failure is never evidence of authentication. */ }
        }
        String code = authorization == Authorization.AUTHORIZED ? "UNSUPPORTED"
                : authorization == Authorization.FORBIDDEN ? "FORBIDDEN" : "UNAUTHENTICATED";
        String correlation = context == null ? "unavailable" : context.correlationId();
        String acceptance = method.equals("start") ? "not_sent" : "unknown";
        var error = new ProviderErrorNotSentOrAcceptedOrUnknown(code, code, correlation, phase,
                method.equals("start") ? "none" : "reconcile", null, acceptance, null);
        return CompletableFuture.completedFuture(new ResultFalse<>(false, error));
    }
    @Override public CompletionStage<Result<CapabilitySnapshot>> inspectCapabilities(CallContext c, ConnectionRef r) { return reject(c,"inspectCapabilities",r,"validation"); }
    @Override public CompletionStage<Result<ValidationReport>> validateBinding(CallContext c, ExecutionSpec r) { return reject(c,"validateBinding",r,"validation"); }
    @Override public CompletionStage<Result<StartReceipt>> start(CallContext c, StartRequest r) { return reject(c,"start",r,"submission"); }
    @Override public CompletionStage<Result<SubmissionLookup>> lookupSubmission(CallContext c, SubmissionRef r) { return reject(c,"lookupSubmission",r,"observation"); }
    @Override public CompletionStage<Result<SubmissionClosure>> closeSubmission(CallContext c, CloseSubmissionRequest r) { return reject(c,"closeSubmission",r,"submission"); }
    @Override public CompletionStage<Result<ExecutionSnapshot>> getSnapshot(CallContext c, BindingRef r) { return reject(c,"getSnapshot",r,"observation"); }
    @Override public CompletionStage<Result<EventPage>> readEvents(CallContext c, EventReadRequest r) { return reject(c,"readEvents",r,"observation"); }
    @Override public CompletionStage<Result<InputReceipt>> submitInput(CallContext c, InputRequest r) { return reject(c,"submitInput",r,"execution"); }
    @Override public CompletionStage<Result<CancelReceipt>> cancel(CallContext c, CancelRequest r) { return reject(c,"cancel",r,"execution"); }
    @Override public CompletionStage<Result<ResumeReceipt>> preparePermissionResume(CallContext c, ResumePreparation r) { return reject(c,"preparePermissionResume",r,"permission"); }
    @Override public CompletionStage<Result<PermissionReceipt>> respondPermission(CallContext c, PermissionResponse r) { return reject(c,"respondPermission",r,"permission"); }
    @Override public CompletionStage<Result<ArtifactDescriptor>> getArtifact(CallContext c, ArtifactRequest r) { return reject(c,"getArtifact",r,"artifact"); }
}
