// Generated from docs/design/contracts/execution-provider.d.ts; SHA-256 96702856a346556ae04a882693731b21729e8b59458de847e1f6b606e319535a. Do not edit.
package io.aitest.contract;

import java.util.concurrent.CompletionStage;
import static io.aitest.contract.ProviderTypes.*;

/** Transport-independent draft boundary. Implementing it does not imply conformance. */
public interface ExecutionProvider {
    CompletionStage<Result<CapabilitySnapshot>> inspectCapabilities(CallContext context, ConnectionRef connection);
    CompletionStage<Result<ValidationReport>> validateBinding(CallContext context, ExecutionSpec spec);
    CompletionStage<Result<StartReceipt>> start(CallContext context, StartRequest request);
    CompletionStage<Result<SubmissionLookup>> lookupSubmission(CallContext context, SubmissionRef request);
    CompletionStage<Result<SubmissionClosure>> closeSubmission(CallContext context, CloseSubmissionRequest request);
    CompletionStage<Result<ExecutionSnapshot>> getSnapshot(CallContext context, BindingRef binding);
    CompletionStage<Result<EventPage>> readEvents(CallContext context, EventReadRequest request);
    CompletionStage<Result<InputReceipt>> submitInput(CallContext context, InputRequest request);
    CompletionStage<Result<CancelReceipt>> cancel(CallContext context, CancelRequest request);
    CompletionStage<Result<ResumeReceipt>> preparePermissionResume(CallContext context, ResumePreparation request);
    CompletionStage<Result<PermissionReceipt>> respondPermission(CallContext context, PermissionResponse request);
    CompletionStage<Result<ArtifactDescriptor>> getArtifact(CallContext context, ArtifactRequest request);
}
