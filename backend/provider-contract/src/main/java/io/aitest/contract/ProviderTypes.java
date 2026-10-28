// Generated from docs/design/contracts/execution-provider.d.ts; SHA-256 96702856a346556ae04a882693731b21729e8b59458de847e1f6b606e319535a. Do not edit.
package io.aitest.contract;

import java.util.*;
import com.fasterxml.jackson.annotation.*;

public final class ProviderTypes {
    private ProviderTypes() {}
    private static void utc(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{6}Z")) throw new IllegalArgumentException("Six-digit UTC required");
        ContractChecks.microsecond(java.time.Instant.parse(value));
    }
    public enum KnownBoolean {
        TRUE(true), FALSE(false), UNKNOWN("unknown");
        private final Object value;
        KnownBoolean(Object value) { this.value=value; }
        @JsonValue public Object value() { return value; }
        @JsonCreator public static KnownBoolean from(Object value) {
            for (var option : values()) if (option.value.equals(value)) return option;
            throw new IllegalArgumentException("KnownBoolean required");
        }
    }
    public record CallContext(
            String contractVersion,
            String correlationId,
            String tenantId,
            CallContextSubjectContext subjectContext,
            String deadline
    ) {
        public CallContext {
            Objects.requireNonNull(contractVersion, "contractVersion");
            if (!Set.of("initial-draft").contains(contractVersion)) throw new IllegalArgumentException("Invalid contractVersion");
            Objects.requireNonNull(correlationId, "correlationId");
            if (correlationId.isEmpty()) throw new IllegalArgumentException("correlationId is empty");
            Objects.requireNonNull(tenantId, "tenantId");
            if (tenantId.length()!=36 || !UUID.fromString(tenantId).toString().equalsIgnoreCase(tenantId)) throw new IllegalArgumentException("tenantId is not UUID");
            Objects.requireNonNull(subjectContext, "subjectContext");
            Objects.requireNonNull(deadline, "deadline");
            utc(deadline);
        }
    }

    public record CallContextSubjectContext(
            String subjectId,
            String authorizationRef
    ) {
        public CallContextSubjectContext {
            Objects.requireNonNull(subjectId, "subjectId");
            if (subjectId.length()!=36 || !UUID.fromString(subjectId).toString().equalsIgnoreCase(subjectId)) throw new IllegalArgumentException("subjectId is not UUID");
            Objects.requireNonNull(authorizationRef, "authorizationRef");
            if (authorizationRef.isEmpty()) throw new IllegalArgumentException("authorizationRef is empty");
        }
    }

    public record ConnectionRef(
            String providerId,
            String connectionId,
            long connectionRevision
    ) {
        public ConnectionRef {
            Objects.requireNonNull(providerId, "providerId");
            if (providerId.isEmpty()) throw new IllegalArgumentException("providerId is empty");
            Objects.requireNonNull(connectionId, "connectionId");
            if (connectionId.isEmpty()) throw new IllegalArgumentException("connectionId is empty");
            ContractChecks.safeInteger(connectionRevision);
        }
    }

    public record BindingRef(
            String bindingId,
            String nodeRunId,
            String providerId
    ) {
        public BindingRef {
            Objects.requireNonNull(bindingId, "bindingId");
            if (bindingId.length()!=36 || !UUID.fromString(bindingId).toString().equalsIgnoreCase(bindingId)) throw new IllegalArgumentException("bindingId is not UUID");
            Objects.requireNonNull(nodeRunId, "nodeRunId");
            if (nodeRunId.length()!=36 || !UUID.fromString(nodeRunId).toString().equalsIgnoreCase(nodeRunId)) throw new IllegalArgumentException("nodeRunId is not UUID");
            Objects.requireNonNull(providerId, "providerId");
            if (providerId.isEmpty()) throw new IllegalArgumentException("providerId is empty");
        }
    }

    public record SubmissionRef(
            String bindingId,
            String nodeRunId,
            String providerId,
            String tenantId,
            String idempotencyKey,
            String specHash
    ) {
        public SubmissionRef {
            Objects.requireNonNull(bindingId, "bindingId");
            if (bindingId.length()!=36 || !UUID.fromString(bindingId).toString().equalsIgnoreCase(bindingId)) throw new IllegalArgumentException("bindingId is not UUID");
            Objects.requireNonNull(nodeRunId, "nodeRunId");
            if (nodeRunId.length()!=36 || !UUID.fromString(nodeRunId).toString().equalsIgnoreCase(nodeRunId)) throw new IllegalArgumentException("nodeRunId is not UUID");
            Objects.requireNonNull(providerId, "providerId");
            if (providerId.isEmpty()) throw new IllegalArgumentException("providerId is empty");
            Objects.requireNonNull(tenantId, "tenantId");
            if (tenantId.length()!=36 || !UUID.fromString(tenantId).toString().equalsIgnoreCase(tenantId)) throw new IllegalArgumentException("tenantId is not UUID");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (idempotencyKey.isEmpty()) throw new IllegalArgumentException("idempotencyKey is empty");
            Objects.requireNonNull(specHash, "specHash");
            if (!specHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("specHash is not SHA-256");
        }
    }

    public record CommandRef(
            String commandId,
            String idempotencyKey,
            String requestHash,
            long expectedRevision
    ) {
        public CommandRef {
            Objects.requireNonNull(commandId, "commandId");
            if (commandId.length()!=36 || !UUID.fromString(commandId).toString().equalsIgnoreCase(commandId)) throw new IllegalArgumentException("commandId is not UUID");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (idempotencyKey.isEmpty()) throw new IllegalArgumentException("idempotencyKey is empty");
            Objects.requireNonNull(requestHash, "requestHash");
            if (!requestHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("requestHash is not SHA-256");
            ContractChecks.safeInteger(expectedRevision);
        }
    }

    public record CapabilitySnapshot(
            ConnectionRef connection,
            String adapterVersion,
            String backendVersion,
            String agentVersion,
            String negotiatedProtocol,
            String configurationHash,
            String observedAt,
            String validUntil,
            List<CapabilitySnapshotCapabilitiesItem> capabilities,
            List<CapabilitySnapshotModelOptionsItem> modelOptions
    ) {
        public CapabilitySnapshot {
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(adapterVersion, "adapterVersion");
            Objects.requireNonNull(configurationHash, "configurationHash");
            if (!configurationHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("configurationHash is not SHA-256");
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
            Objects.requireNonNull(validUntil, "validUntil");
            utc(validUntil);
            Objects.requireNonNull(capabilities, "capabilities");
            capabilities = List.copyOf(capabilities);
            Objects.requireNonNull(modelOptions, "modelOptions");
            modelOptions = List.copyOf(modelOptions);
        }
    }

    public record CapabilitySnapshotCapabilitiesItem(
            String name,
            String level,
            String evidenceRef
    ) {
        public CapabilitySnapshotCapabilitiesItem {
            Objects.requireNonNull(name, "name");
            if (!Set.of("image_input", "resume_checkpoint", "permission_bridge", "binary_artifact", "durable_events", "idempotent_start", "submission_fence", "hard_call_limit", "usage_reporting", "hard_token_limit", "admission_guard").contains(name)) throw new IllegalArgumentException("Invalid name");
            Objects.requireNonNull(level, "level");
            if (!Set.of("advertised", "adapter_supported", "verified", "unknown", "unsupported").contains(level)) throw new IllegalArgumentException("Invalid level");
            if (evidenceRef != null) { if (evidenceRef.isEmpty()) throw new IllegalArgumentException("evidenceRef is empty"); }
        }
    }

    public record CapabilitySnapshotModelOptionsItem(
            String modelId,
            boolean confirmed
    ) {
        public CapabilitySnapshotModelOptionsItem {
            Objects.requireNonNull(modelId, "modelId");
            if (modelId.isEmpty()) throw new IllegalArgumentException("modelId is empty");
        }
    }

    public record ExecutionSpec(
            String contractVersion,
            AdmissionTicket admission,
            String purpose,
            String tenantId,
            String conversationId,
            String workflowRunId,
            String workflowVersion,
            String nodeRunId,
            String bindingId,
            long ownershipEpoch,
            ConnectionRef connection,
            String capabilitySnapshotHash,
            String agentId,
            String systemId,
            String environmentId,
            String primaryRepositoryId,
            String deployedCommit,
            String codeCommit,
            String objective,
            ExecutionSpecPreparation preparation,
            List<ExecutionSpecInputsItem> inputs,
            String executionIdentityRef,
            String workspaceAuthorizationRef,
            List<String> resourceAuthorizationRefs,
            ExecutionSpecModelRequest modelRequest,
            List<ExecutionSpecSkillsItem> skills,
            ExecutionSpecBudget budget,
            String deadline,
            ExecutionSpecOutputContract outputContract
    ) {
        public ExecutionSpec {
            Objects.requireNonNull(contractVersion, "contractVersion");
            if (!Set.of("initial-draft").contains(contractVersion)) throw new IllegalArgumentException("Invalid contractVersion");
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(purpose, "purpose");
            if (!Set.of("understanding", "investigation", "title", "summary").contains(purpose)) throw new IllegalArgumentException("Invalid purpose");
            Objects.requireNonNull(tenantId, "tenantId");
            if (tenantId.length()!=36 || !UUID.fromString(tenantId).toString().equalsIgnoreCase(tenantId)) throw new IllegalArgumentException("tenantId is not UUID");
            Objects.requireNonNull(conversationId, "conversationId");
            if (conversationId.length()!=36 || !UUID.fromString(conversationId).toString().equalsIgnoreCase(conversationId)) throw new IllegalArgumentException("conversationId is not UUID");
            Objects.requireNonNull(workflowRunId, "workflowRunId");
            if (workflowRunId.length()!=36 || !UUID.fromString(workflowRunId).toString().equalsIgnoreCase(workflowRunId)) throw new IllegalArgumentException("workflowRunId is not UUID");
            Objects.requireNonNull(workflowVersion, "workflowVersion");
            if (workflowVersion.isEmpty()) throw new IllegalArgumentException("workflowVersion is empty");
            Objects.requireNonNull(nodeRunId, "nodeRunId");
            if (nodeRunId.length()!=36 || !UUID.fromString(nodeRunId).toString().equalsIgnoreCase(nodeRunId)) throw new IllegalArgumentException("nodeRunId is not UUID");
            Objects.requireNonNull(bindingId, "bindingId");
            if (bindingId.length()!=36 || !UUID.fromString(bindingId).toString().equalsIgnoreCase(bindingId)) throw new IllegalArgumentException("bindingId is not UUID");
            ContractChecks.safeInteger(ownershipEpoch);
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(capabilitySnapshotHash, "capabilitySnapshotHash");
            if (!capabilitySnapshotHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("capabilitySnapshotHash is not SHA-256");
            Objects.requireNonNull(agentId, "agentId");
            if (agentId.isEmpty()) throw new IllegalArgumentException("agentId is empty");
            Objects.requireNonNull(systemId, "systemId");
            if (systemId.length()!=36 || !UUID.fromString(systemId).toString().equalsIgnoreCase(systemId)) throw new IllegalArgumentException("systemId is not UUID");
            Objects.requireNonNull(environmentId, "environmentId");
            if (environmentId.length()!=36 || !UUID.fromString(environmentId).toString().equalsIgnoreCase(environmentId)) throw new IllegalArgumentException("environmentId is not UUID");
            if (primaryRepositoryId != null) { if (primaryRepositoryId.length()!=36 || !UUID.fromString(primaryRepositoryId).toString().equalsIgnoreCase(primaryRepositoryId)) throw new IllegalArgumentException("primaryRepositoryId is not UUID"); }
            Objects.requireNonNull(objective, "objective");
            Objects.requireNonNull(preparation, "preparation");
            Objects.requireNonNull(inputs, "inputs");
            inputs = List.copyOf(inputs);
            Objects.requireNonNull(executionIdentityRef, "executionIdentityRef");
            if (executionIdentityRef.isEmpty()) throw new IllegalArgumentException("executionIdentityRef is empty");
            Objects.requireNonNull(workspaceAuthorizationRef, "workspaceAuthorizationRef");
            if (workspaceAuthorizationRef.isEmpty()) throw new IllegalArgumentException("workspaceAuthorizationRef is empty");
            Objects.requireNonNull(resourceAuthorizationRefs, "resourceAuthorizationRefs");
            resourceAuthorizationRefs = List.copyOf(resourceAuthorizationRefs);
            for (var item : resourceAuthorizationRefs) { if (item.isEmpty()) throw new IllegalArgumentException("item is empty"); }
            Objects.requireNonNull(modelRequest, "modelRequest");
            Objects.requireNonNull(skills, "skills");
            skills = List.copyOf(skills);
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(deadline, "deadline");
            utc(deadline);
            Objects.requireNonNull(outputContract, "outputContract");
        }
    }

    public record AdmissionTicket(
            String ticketId,
            String bindingId,
            String executionContentHash,
            long authorizationRevision,
            String approvalRef,
            Long approvalRevision,
            String notBefore,
            String notAfter,
            String authorityRef
    ) {
        public AdmissionTicket {
            Objects.requireNonNull(ticketId, "ticketId");
            if (ticketId.length()!=36 || !UUID.fromString(ticketId).toString().equalsIgnoreCase(ticketId)) throw new IllegalArgumentException("ticketId is not UUID");
            Objects.requireNonNull(bindingId, "bindingId");
            if (bindingId.length()!=36 || !UUID.fromString(bindingId).toString().equalsIgnoreCase(bindingId)) throw new IllegalArgumentException("bindingId is not UUID");
            Objects.requireNonNull(executionContentHash, "executionContentHash");
            if (!executionContentHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("executionContentHash is not SHA-256");
            ContractChecks.safeInteger(authorizationRevision);
            if (approvalRef != null) { if (approvalRef.isEmpty()) throw new IllegalArgumentException("approvalRef is empty"); }
            if (approvalRevision != null) { ContractChecks.safeInteger(approvalRevision); }
            Objects.requireNonNull(notBefore, "notBefore");
            utc(notBefore);
            Objects.requireNonNull(notAfter, "notAfter");
            utc(notAfter);
            Objects.requireNonNull(authorityRef, "authorityRef");
            if (authorityRef.isEmpty()) throw new IllegalArgumentException("authorityRef is empty");
        }
    }

    public record ExecutionSpecPreparation(
            long inputRevision,
            long configurationSnapshotRevision,
            long admissionPolicyRevision,
            String integrationSnapshotRef,
            String integrationSnapshotHash,
            String environmentResourcesRef,
            String environmentResourcesHash,
            String executionPolicyDecisionRef,
            String effectiveWorkflowRef,
            String effectiveWorkflowHash,
            String runtimeManifestRef,
            String runtimeManifestHash,
            String contextManifestRef,
            String contextManifestHash,
            long authorizationRevision
    ) {
        public ExecutionSpecPreparation {
            ContractChecks.safeInteger(inputRevision);
            ContractChecks.safeInteger(configurationSnapshotRevision);
            ContractChecks.safeInteger(admissionPolicyRevision);
            Objects.requireNonNull(integrationSnapshotRef, "integrationSnapshotRef");
            if (integrationSnapshotRef.isEmpty()) throw new IllegalArgumentException("integrationSnapshotRef is empty");
            Objects.requireNonNull(integrationSnapshotHash, "integrationSnapshotHash");
            if (!integrationSnapshotHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("integrationSnapshotHash is not SHA-256");
            Objects.requireNonNull(environmentResourcesRef, "environmentResourcesRef");
            if (environmentResourcesRef.isEmpty()) throw new IllegalArgumentException("environmentResourcesRef is empty");
            Objects.requireNonNull(environmentResourcesHash, "environmentResourcesHash");
            if (!environmentResourcesHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("environmentResourcesHash is not SHA-256");
            Objects.requireNonNull(executionPolicyDecisionRef, "executionPolicyDecisionRef");
            if (executionPolicyDecisionRef.isEmpty()) throw new IllegalArgumentException("executionPolicyDecisionRef is empty");
            Objects.requireNonNull(effectiveWorkflowRef, "effectiveWorkflowRef");
            if (effectiveWorkflowRef.isEmpty()) throw new IllegalArgumentException("effectiveWorkflowRef is empty");
            Objects.requireNonNull(effectiveWorkflowHash, "effectiveWorkflowHash");
            if (!effectiveWorkflowHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("effectiveWorkflowHash is not SHA-256");
            Objects.requireNonNull(runtimeManifestRef, "runtimeManifestRef");
            if (runtimeManifestRef.isEmpty()) throw new IllegalArgumentException("runtimeManifestRef is empty");
            Objects.requireNonNull(runtimeManifestHash, "runtimeManifestHash");
            if (!runtimeManifestHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("runtimeManifestHash is not SHA-256");
            Objects.requireNonNull(contextManifestRef, "contextManifestRef");
            if (contextManifestRef.isEmpty()) throw new IllegalArgumentException("contextManifestRef is empty");
            Objects.requireNonNull(contextManifestHash, "contextManifestHash");
            if (!contextManifestHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("contextManifestHash is not SHA-256");
            ContractChecks.safeInteger(authorizationRevision);
        }
    }

    public record ExecutionSpecInputsItem(
            String attachmentId,
            String sha256,
            String authorizationRef
    ) {
        public ExecutionSpecInputsItem {
            Objects.requireNonNull(attachmentId, "attachmentId");
            if (attachmentId.isEmpty()) throw new IllegalArgumentException("attachmentId is empty");
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is not SHA-256");
            Objects.requireNonNull(authorizationRef, "authorizationRef");
            if (authorizationRef.isEmpty()) throw new IllegalArgumentException("authorizationRef is empty");
        }
    }

    public record ExecutionSpecModelRequest(
            String modelId,
            String presetId,
            long presetRevision
    ) {
        public ExecutionSpecModelRequest {
            Objects.requireNonNull(modelId, "modelId");
            if (modelId.isEmpty()) throw new IllegalArgumentException("modelId is empty");
            Objects.requireNonNull(presetId, "presetId");
            if (presetId.isEmpty()) throw new IllegalArgumentException("presetId is empty");
            ContractChecks.safeInteger(presetRevision);
        }
    }

    public record ExecutionSpecSkillsItem(
            String skillId,
            long revision,
            String sha256
    ) {
        public ExecutionSpecSkillsItem {
            Objects.requireNonNull(skillId, "skillId");
            if (skillId.isEmpty()) throw new IllegalArgumentException("skillId is empty");
            ContractChecks.safeInteger(revision);
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is not SHA-256");
        }
    }

    public record ExecutionSpecBudget(
            String modelQuotaSnapshotRef,
            String modelQuotaSnapshotHash,
            String reservationId,
            String scopeId,
            long maxNewCalls,
            long maxConcurrency,
            String estimatedAmount,
            String currency,
            String unknownUsagePolicy,
            String riskAcceptanceRef,
            String userTotalAccountRef,
            String platformTurnReservationId,
            boolean requireHardModelLimits,
            String callLimitEnforcement
    ) {
        public ExecutionSpecBudget {
            Objects.requireNonNull(modelQuotaSnapshotRef, "modelQuotaSnapshotRef");
            if (modelQuotaSnapshotRef.isEmpty()) throw new IllegalArgumentException("modelQuotaSnapshotRef is empty");
            Objects.requireNonNull(modelQuotaSnapshotHash, "modelQuotaSnapshotHash");
            if (!modelQuotaSnapshotHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("modelQuotaSnapshotHash is not SHA-256");
            Objects.requireNonNull(reservationId, "reservationId");
            if (reservationId.isEmpty()) throw new IllegalArgumentException("reservationId is empty");
            Objects.requireNonNull(scopeId, "scopeId");
            if (scopeId.isEmpty()) throw new IllegalArgumentException("scopeId is empty");
            ContractChecks.safeInteger(maxNewCalls);
            ContractChecks.safeInteger(maxConcurrency);
            if (estimatedAmount != null) { if (!estimatedAmount.matches("(0|[1-9][0-9]*)([.][0-9]+)?")) throw new IllegalArgumentException("estimatedAmount is not Decimal"); }
            Objects.requireNonNull(unknownUsagePolicy, "unknownUsagePolicy");
            if (!Set.of("block_new_billable_actions", "allow_with_platform_limits").contains(unknownUsagePolicy)) throw new IllegalArgumentException("Invalid unknownUsagePolicy");
            if (riskAcceptanceRef != null) { if (riskAcceptanceRef.isEmpty()) throw new IllegalArgumentException("riskAcceptanceRef is empty"); }
            Objects.requireNonNull(userTotalAccountRef, "userTotalAccountRef");
            if (userTotalAccountRef.isEmpty()) throw new IllegalArgumentException("userTotalAccountRef is empty");
            Objects.requireNonNull(platformTurnReservationId, "platformTurnReservationId");
            if (platformTurnReservationId.isEmpty()) throw new IllegalArgumentException("platformTurnReservationId is empty");
            Objects.requireNonNull(callLimitEnforcement, "callLimitEnforcement");
            if (!Set.of("verified", "adapter_only", "unknown").contains(callLimitEnforcement)) throw new IllegalArgumentException("Invalid callLimitEnforcement");
        }
    }

    public record ExecutionSpecOutputContract(
            String schemaRef,
            List<String> artifactTypes,
            boolean evidenceRequired
    ) {
        public ExecutionSpecOutputContract {
            Objects.requireNonNull(schemaRef, "schemaRef");
            if (schemaRef.isEmpty()) throw new IllegalArgumentException("schemaRef is empty");
            Objects.requireNonNull(artifactTypes, "artifactTypes");
            artifactTypes = List.copyOf(artifactTypes);
        }
    }

    public record ValidationReport(
            String specHash,
            String checkedAt,
            String validUntil,
            boolean canStart,
            List<ValidationReportBlockersItem> blockers,
            List<String> warnings
    ) {
        public ValidationReport {
            Objects.requireNonNull(specHash, "specHash");
            if (!specHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("specHash is not SHA-256");
            Objects.requireNonNull(checkedAt, "checkedAt");
            utc(checkedAt);
            Objects.requireNonNull(validUntil, "validUntil");
            utc(validUntil);
            Objects.requireNonNull(blockers, "blockers");
            blockers = List.copyOf(blockers);
            Objects.requireNonNull(warnings, "warnings");
            warnings = List.copyOf(warnings);
        }
    }

    public record ValidationReportBlockersItem(
            String code,
            String field,
            String detail
    ) {
        public ValidationReportBlockersItem {
            Objects.requireNonNull(code, "code");
            if (!Set.of("UNAUTHENTICATED", "FORBIDDEN", "VALIDATION_FAILED", "CAPABILITY_MISMATCH", "UNSUPPORTED", "REVISION_CONFLICT", "IDEMPOTENCY_CONFLICT", "PROVIDER_UNAVAILABLE", "DEADLINE_EXCEEDED", "SUBMISSION_CLOSED", "CURSOR_EXPIRED", "EVENT_GAP", "PERMISSION_STALE", "APPROVAL_EXPIRED", "PRECONDITION_CHANGED", "BUDGET_BLOCKED", "USAGE_UNKNOWN", "ARTIFACT_UNAVAILABLE", "INTEGRITY_MISMATCH", "RESULT_UNKNOWN").contains(code)) throw new IllegalArgumentException("Invalid code");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public record StartRequest(
            SubmissionRef submission,
            CommandRef command,
            ExecutionSpec spec
    ) {
        public StartRequest {
            Objects.requireNonNull(submission, "submission");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(spec, "spec");
        }
    }

    public record ProviderRunRef(
            String providerRunId,
            String taskId,
            String sessionId,
            String attemptId
    ) {
        public ProviderRunRef {
            Objects.requireNonNull(providerRunId, "providerRunId");
            if (providerRunId.isEmpty()) throw new IllegalArgumentException("providerRunId is empty");
            if (taskId != null) { if (taskId.isEmpty()) throw new IllegalArgumentException("taskId is empty"); }
            if (sessionId != null) { if (sessionId.isEmpty()) throw new IllegalArgumentException("sessionId is empty"); }
            if (attemptId != null) { if (attemptId.isEmpty()) throw new IllegalArgumentException("attemptId is empty"); }
        }
    }

    public record StartReceipt(
            String status,
            BindingRef binding,
            ProviderRunRef run,
            String acceptedAt,
            String receiptId,
            String specHash,
            boolean reused
    ) {
        public StartReceipt {
            Objects.requireNonNull(status, "status");
            if (!Set.of("accepted").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            utc(acceptedAt);
            Objects.requireNonNull(receiptId, "receiptId");
            if (receiptId.isEmpty()) throw new IllegalArgumentException("receiptId is empty");
            Objects.requireNonNull(specHash, "specHash");
            if (!specHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("specHash is not SHA-256");
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="status", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=SubmissionLookupFound.class, name="found"),
        @JsonSubTypes.Type(value=SubmissionLookupNotFound.class, name="not_found"),
        @JsonSubTypes.Type(value=SubmissionLookupUnknown.class, name="unknown")
    })
    public sealed interface SubmissionLookup permits SubmissionLookupFound, SubmissionLookupNotFound, SubmissionLookupUnknown {}

    public record SubmissionLookupFound(
            String status,
            StartReceipt receipt
    ) implements SubmissionLookup {
        public SubmissionLookupFound {
            Objects.requireNonNull(status, "status");
            if (!Set.of("found").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    public record SubmissionLookupNotFound(
            String status,
            String observedAt,
            boolean authoritativeNonAcceptance
    ) implements SubmissionLookup {
        public SubmissionLookupNotFound {
            Objects.requireNonNull(status, "status");
            if (!Set.of("not_found").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
            if (authoritativeNonAcceptance != false) throw new IllegalArgumentException("Invalid authoritativeNonAcceptance");
        }
    }

    public record SubmissionLookupUnknown(
            String status,
            String reason,
            String observedAt
    ) implements SubmissionLookup {
        public SubmissionLookupUnknown {
            Objects.requireNonNull(status, "status");
            if (!Set.of("unknown").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
        }
    }

    public record CloseSubmissionRequest(
            SubmissionRef submission,
            CommandRef command
    ) {
        public CloseSubmissionRequest {
            Objects.requireNonNull(submission, "submission");
            Objects.requireNonNull(command, "command");
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="status", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=SubmissionClosureFound.class, name="found"),
        @JsonSubTypes.Type(value=SubmissionClosureNotAcceptedFinal.class, name="not_accepted_final"),
        @JsonSubTypes.Type(value=SubmissionClosureUnknown.class, name="unknown")
    })
    public sealed interface SubmissionClosure permits SubmissionClosureFound, SubmissionClosureNotAcceptedFinal, SubmissionClosureUnknown {}

    public record SubmissionClosureFound(
            String status,
            StartReceipt receipt
    ) implements SubmissionClosure {
        public SubmissionClosureFound {
            Objects.requireNonNull(status, "status");
            if (!Set.of("found").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    public record SubmissionClosureNotAcceptedFinal(
            String status,
            String admissionFence,
            String closedAt,
            String proofRef
    ) implements SubmissionClosure {
        public SubmissionClosureNotAcceptedFinal {
            Objects.requireNonNull(status, "status");
            if (!Set.of("not_accepted_final").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(admissionFence, "admissionFence");
            if (admissionFence.isEmpty()) throw new IllegalArgumentException("admissionFence is empty");
            Objects.requireNonNull(closedAt, "closedAt");
            utc(closedAt);
            Objects.requireNonNull(proofRef, "proofRef");
            if (proofRef.isEmpty()) throw new IllegalArgumentException("proofRef is empty");
        }
    }

    public record SubmissionClosureUnknown(
            String status,
            String reason
    ) implements SubmissionClosure {
        public SubmissionClosureUnknown {
            Objects.requireNonNull(status, "status");
            if (!Set.of("unknown").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record ProviderPermissionRequest(
            String requestId,
            String requestContextHash,
            String operationId,
            String operationHash,
            long mappingRevision,
            String validity,
            String observedAt,
            String expiresAt,
            List<ProviderPermissionRequestOptionsItem> options
    ) {
        public ProviderPermissionRequest {
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId is empty");
            Objects.requireNonNull(requestContextHash, "requestContextHash");
            if (!requestContextHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("requestContextHash is not SHA-256");
            Objects.requireNonNull(operationId, "operationId");
            if (operationId.isEmpty()) throw new IllegalArgumentException("operationId is empty");
            Objects.requireNonNull(operationHash, "operationHash");
            if (!operationHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("operationHash is not SHA-256");
            ContractChecks.safeInteger(mappingRevision);
            Objects.requireNonNull(validity, "validity");
            if (!Set.of("live", "stale", "unknown").contains(validity)) throw new IllegalArgumentException("Invalid validity");
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
            if (expiresAt != null) { utc(expiresAt); }
            Objects.requireNonNull(options, "options");
            options = List.copyOf(options);
        }
    }

    public record ProviderPermissionRequestOptionsItem(
            String optionId,
            String kind
    ) {
        public ProviderPermissionRequestOptionsItem {
            Objects.requireNonNull(optionId, "optionId");
            if (optionId.isEmpty()) throw new IllegalArgumentException("optionId is empty");
            Objects.requireNonNull(kind, "kind");
            if (!Set.of("allow_once", "reject_once").contains(kind)) throw new IllegalArgumentException("Invalid kind");
        }
    }

    public record ExecutionSnapshot(
            BindingRef binding,
            ProviderRunRef run,
            String state,
            long snapshotRevision,
            long eventWatermark,
            String resumeCursor,
            String observedAt,
            KnownBoolean executionActive,
            String outcome,
            String effectStatus,
            List<String> effectReceiptRefs,
            String modelConfirmed,
            List<ProviderPermissionRequest> permissions,
            List<ExecutionSnapshotPermissionDeliveriesItem> permissionDeliveries,
            List<ExecutionSnapshotOperationFactsItem> operationFacts,
            String checkpointRef
    ) {
        public ExecutionSnapshot {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(state, "state");
            if (!Set.of("prepared", "submitting", "acceptance_unknown", "accepted", "active", "waiting_permission", "stopping", "reconciling", "terminal", "rejected").contains(state)) throw new IllegalArgumentException("Invalid state");
            ContractChecks.safeInteger(snapshotRevision);
            ContractChecks.safeInteger(eventWatermark);
            Objects.requireNonNull(resumeCursor, "resumeCursor");
            if (resumeCursor.isEmpty()) throw new IllegalArgumentException("resumeCursor is empty");
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
            Objects.requireNonNull(executionActive, "executionActive");
            if (outcome != null) { if (!Set.of("succeeded", "failed", "cancelled").contains(outcome)) throw new IllegalArgumentException("Invalid outcome"); }
            Objects.requireNonNull(effectStatus, "effectStatus");
            if (!Set.of("none", "verified", "unknown").contains(effectStatus)) throw new IllegalArgumentException("Invalid effectStatus");
            Objects.requireNonNull(effectReceiptRefs, "effectReceiptRefs");
            effectReceiptRefs = List.copyOf(effectReceiptRefs);
            for (var item : effectReceiptRefs) { if (item.isEmpty()) throw new IllegalArgumentException("item is empty"); }
            if (modelConfirmed != null) { if (modelConfirmed.isEmpty()) throw new IllegalArgumentException("modelConfirmed is empty"); }
            Objects.requireNonNull(permissions, "permissions");
            permissions = List.copyOf(permissions);
            Objects.requireNonNull(permissionDeliveries, "permissionDeliveries");
            permissionDeliveries = List.copyOf(permissionDeliveries);
            Objects.requireNonNull(operationFacts, "operationFacts");
            operationFacts = List.copyOf(operationFacts);
            if (checkpointRef != null) { if (checkpointRef.isEmpty()) throw new IllegalArgumentException("checkpointRef is empty"); }
        }
    }

    public record ExecutionSnapshotPermissionDeliveriesItem(
            String requestId,
            String decisionCommandId,
            String status,
            String providerReceiptRef,
            String observedAt
    ) {
        public ExecutionSnapshotPermissionDeliveriesItem {
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId is empty");
            Objects.requireNonNull(decisionCommandId, "decisionCommandId");
            if (decisionCommandId.isEmpty()) throw new IllegalArgumentException("decisionCommandId is empty");
            Objects.requireNonNull(status, "status");
            if (!Set.of("not_sent", "delivered", "unknown", "denied_by_provider").contains(status)) throw new IllegalArgumentException("Invalid status");
            if (providerReceiptRef != null) { if (providerReceiptRef.isEmpty()) throw new IllegalArgumentException("providerReceiptRef is empty"); }
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
        }
    }

    public record ExecutionSnapshotOperationFactsItem(
            String operationId,
            String operationHash,
            String state,
            String receiptRef,
            String observedAt
    ) {
        public ExecutionSnapshotOperationFactsItem {
            Objects.requireNonNull(operationId, "operationId");
            if (operationId.isEmpty()) throw new IllegalArgumentException("operationId is empty");
            Objects.requireNonNull(operationHash, "operationHash");
            if (!operationHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("operationHash is not SHA-256");
            Objects.requireNonNull(state, "state");
            if (!Set.of("not_started", "running", "succeeded", "failed", "cancelled", "unknown").contains(state)) throw new IllegalArgumentException("Invalid state");
            if (receiptRef != null) { if (receiptRef.isEmpty()) throw new IllegalArgumentException("receiptRef is empty"); }
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="type", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=EventPayloadExecutionAccepted.class, name="execution.accepted"),
        @JsonSubTypes.Type(value=EventPayloadExecutionUpdated.class, name="execution.updated"),
        @JsonSubTypes.Type(value=EventPayloadPermissionRequested.class, name="permission.requested"),
        @JsonSubTypes.Type(value=EventPayloadPermissionDelivery.class, name="permission.delivery"),
        @JsonSubTypes.Type(value=EventPayloadInputAdopted.class, name="input.adopted"),
        @JsonSubTypes.Type(value=EventPayloadUsageReported.class, name="usage.reported"),
        @JsonSubTypes.Type(value=EventPayloadArtifactReady.class, name="artifact.ready"),
        @JsonSubTypes.Type(value=EventPayloadDiagnostic.class, name="diagnostic")
    })
    public sealed interface EventPayload permits EventPayloadExecutionAccepted, EventPayloadExecutionUpdated, EventPayloadPermissionRequested, EventPayloadPermissionDelivery, EventPayloadInputAdopted, EventPayloadUsageReported, EventPayloadArtifactReady, EventPayloadDiagnostic {}

    public record EventPayloadExecutionAccepted(
            String type,
            StartReceipt receipt
    ) implements EventPayload {
        public EventPayloadExecutionAccepted {
            Objects.requireNonNull(type, "type");
            if (!Set.of("execution.accepted").contains(type)) throw new IllegalArgumentException("Invalid type");
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    public record EventPayloadExecutionUpdated(
            String type,
            ExecutionSnapshot snapshot
    ) implements EventPayload {
        public EventPayloadExecutionUpdated {
            Objects.requireNonNull(type, "type");
            if (!Set.of("execution.updated").contains(type)) throw new IllegalArgumentException("Invalid type");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public record EventPayloadPermissionRequested(
            String type,
            ProviderPermissionRequest request
    ) implements EventPayload {
        public EventPayloadPermissionRequested {
            Objects.requireNonNull(type, "type");
            if (!Set.of("permission.requested").contains(type)) throw new IllegalArgumentException("Invalid type");
            Objects.requireNonNull(request, "request");
        }
    }

    public record EventPayloadPermissionDelivery(
            String type,
            String requestId,
            String decisionCommandId,
            String status
    ) implements EventPayload {
        public EventPayloadPermissionDelivery {
            Objects.requireNonNull(type, "type");
            if (!Set.of("permission.delivery").contains(type)) throw new IllegalArgumentException("Invalid type");
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId is empty");
            Objects.requireNonNull(decisionCommandId, "decisionCommandId");
            if (decisionCommandId.isEmpty()) throw new IllegalArgumentException("decisionCommandId is empty");
            Objects.requireNonNull(status, "status");
            if (!Set.of("delivered", "denied_by_provider").contains(status)) throw new IllegalArgumentException("Invalid status");
        }
    }

    public record EventPayloadInputAdopted(
            String type,
            String inputId,
            String adoptedAtBoundary
    ) implements EventPayload {
        public EventPayloadInputAdopted {
            Objects.requireNonNull(type, "type");
            if (!Set.of("input.adopted").contains(type)) throw new IllegalArgumentException("Invalid type");
            Objects.requireNonNull(inputId, "inputId");
            if (inputId.isEmpty()) throw new IllegalArgumentException("inputId is empty");
            Objects.requireNonNull(adoptedAtBoundary, "adoptedAtBoundary");
            if (adoptedAtBoundary.isEmpty()) throw new IllegalArgumentException("adoptedAtBoundary is empty");
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="quality", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=UsageFactActualOrEstimate.class, name="actual"),
        @JsonSubTypes.Type(value=UsageFactActualOrEstimate.class, name="estimate"),
        @JsonSubTypes.Type(value=UsageFactUnreported.class, name="unreported")
    })
    public sealed interface UsageFact permits UsageFactActualOrEstimate, UsageFactUnreported {}

    public record UsageFactActualOrEstimate(
            String scopeId,
            String sourceId,
            String meter,
            String aggregation,
            String coverage,
            String observedAt,
            String quality,
            String amount,
            String unit,
            String currency
    ) implements UsageFact {
        public UsageFactActualOrEstimate {
            Objects.requireNonNull(scopeId, "scopeId");
            if (scopeId.isEmpty()) throw new IllegalArgumentException("scopeId is empty");
            Objects.requireNonNull(sourceId, "sourceId");
            if (sourceId.isEmpty()) throw new IllegalArgumentException("sourceId is empty");
            Objects.requireNonNull(meter, "meter");
            Objects.requireNonNull(aggregation, "aggregation");
            if (!Set.of("delta", "cumulative").contains(aggregation)) throw new IllegalArgumentException("Invalid aggregation");
            Objects.requireNonNull(coverage, "coverage");
            if (!Set.of("run_total", "child_only").contains(coverage)) throw new IllegalArgumentException("Invalid coverage");
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
            Objects.requireNonNull(quality, "quality");
            if (!Set.of("actual", "estimate").contains(quality)) throw new IllegalArgumentException("Invalid quality");
            Objects.requireNonNull(amount, "amount");
            if (!amount.matches("(0|[1-9][0-9]*)([.][0-9]+)?")) throw new IllegalArgumentException("amount is not Decimal");
            Objects.requireNonNull(unit, "unit");
        }
    }

    public record UsageFactUnreported(
            String scopeId,
            String sourceId,
            String meter,
            String aggregation,
            String coverage,
            String observedAt,
            String quality,
            Void amount,
            String unit,
            String currency
    ) implements UsageFact {
        public UsageFactUnreported {
            Objects.requireNonNull(scopeId, "scopeId");
            if (scopeId.isEmpty()) throw new IllegalArgumentException("scopeId is empty");
            Objects.requireNonNull(sourceId, "sourceId");
            if (sourceId.isEmpty()) throw new IllegalArgumentException("sourceId is empty");
            Objects.requireNonNull(meter, "meter");
            Objects.requireNonNull(aggregation, "aggregation");
            if (!Set.of("delta", "cumulative").contains(aggregation)) throw new IllegalArgumentException("Invalid aggregation");
            Objects.requireNonNull(coverage, "coverage");
            if (!Set.of("run_total", "child_only").contains(coverage)) throw new IllegalArgumentException("Invalid coverage");
            Objects.requireNonNull(observedAt, "observedAt");
            utc(observedAt);
            Objects.requireNonNull(quality, "quality");
            if (!Set.of("unreported").contains(quality)) throw new IllegalArgumentException("Invalid quality");
            if (amount != null) throw new IllegalArgumentException("amount must be null");
        }
    }

    public record EventPayloadUsageReported(
            String type,
            UsageFact usage
    ) implements EventPayload {
        public EventPayloadUsageReported {
            Objects.requireNonNull(type, "type");
            if (!Set.of("usage.reported").contains(type)) throw new IllegalArgumentException("Invalid type");
            Objects.requireNonNull(usage, "usage");
        }
    }

    public record ArtifactDescriptor(
            String artifactId,
            String bindingId,
            String mimeType,
            long bytes,
            String sha256,
            String sourceRef,
            String importState,
            String authorizationRef,
            ArtifactDescriptorAccess access
    ) {
        public ArtifactDescriptor {
            Objects.requireNonNull(artifactId, "artifactId");
            if (artifactId.isEmpty()) throw new IllegalArgumentException("artifactId is empty");
            Objects.requireNonNull(bindingId, "bindingId");
            if (bindingId.isEmpty()) throw new IllegalArgumentException("bindingId is empty");
            Objects.requireNonNull(mimeType, "mimeType");
            ContractChecks.safeInteger(bytes);
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is not SHA-256");
            Objects.requireNonNull(sourceRef, "sourceRef");
            if (sourceRef.isEmpty()) throw new IllegalArgumentException("sourceRef is empty");
            Objects.requireNonNull(importState, "importState");
            if (!Set.of("metadata_only", "importing", "verified", "failed").contains(importState)) throw new IllegalArgumentException("Invalid importState");
            Objects.requireNonNull(authorizationRef, "authorizationRef");
            if (authorizationRef.isEmpty()) throw new IllegalArgumentException("authorizationRef is empty");
        }
    }

    public record ArtifactDescriptorAccess(
            String ticketRef,
            String expiresAt,
            String purpose
    ) {
        public ArtifactDescriptorAccess {
            Objects.requireNonNull(ticketRef, "ticketRef");
            if (ticketRef.isEmpty()) throw new IllegalArgumentException("ticketRef is empty");
            Objects.requireNonNull(expiresAt, "expiresAt");
            utc(expiresAt);
            Objects.requireNonNull(purpose, "purpose");
            if (!Set.of("inspect", "import", "download").contains(purpose)) throw new IllegalArgumentException("Invalid purpose");
        }
    }

    public record EventPayloadArtifactReady(
            String type,
            ArtifactDescriptor artifact
    ) implements EventPayload {
        public EventPayloadArtifactReady {
            Objects.requireNonNull(type, "type");
            if (!Set.of("artifact.ready").contains(type)) throw new IllegalArgumentException("Invalid type");
            Objects.requireNonNull(artifact, "artifact");
        }
    }

    public record EventPayloadDiagnostic(
            String type,
            String code,
            String summary
    ) implements EventPayload {
        public EventPayloadDiagnostic {
            Objects.requireNonNull(type, "type");
            if (!Set.of("diagnostic").contains(type)) throw new IllegalArgumentException("Invalid type");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(summary, "summary");
        }
    }

    public record ExecutionEvent(
            String providerId,
            String providerRunId,
            String providerEventId,
            String bindingId,
            long sequence,
            Long sourceSequence,
            String occurredAt,
            String receivedAt,
            long payloadVersion,
            String visibility,
            EventPayload payload
    ) {
        public ExecutionEvent {
            Objects.requireNonNull(providerId, "providerId");
            if (providerId.isEmpty()) throw new IllegalArgumentException("providerId is empty");
            Objects.requireNonNull(providerRunId, "providerRunId");
            if (providerRunId.isEmpty()) throw new IllegalArgumentException("providerRunId is empty");
            Objects.requireNonNull(providerEventId, "providerEventId");
            if (providerEventId.isEmpty()) throw new IllegalArgumentException("providerEventId is empty");
            Objects.requireNonNull(bindingId, "bindingId");
            if (bindingId.length()!=36 || !UUID.fromString(bindingId).toString().equalsIgnoreCase(bindingId)) throw new IllegalArgumentException("bindingId is not UUID");
            ContractChecks.safeInteger(sequence);
            if (sourceSequence != null) { ContractChecks.safeInteger(sourceSequence); }
            Objects.requireNonNull(occurredAt, "occurredAt");
            utc(occurredAt);
            Objects.requireNonNull(receivedAt, "receivedAt");
            utc(receivedAt);
            ContractChecks.safeInteger(payloadVersion);
            if (payloadVersion != 1) throw new IllegalArgumentException("Invalid payloadVersion");
            Objects.requireNonNull(visibility, "visibility");
            if (!Set.of("business", "operator").contains(visibility)) throw new IllegalArgumentException("Invalid visibility");
            Objects.requireNonNull(payload, "payload");
        }
    }

    public record EventReadRequest(
            BindingRef binding,
            String cursor,
            long limit
    ) {
        public EventReadRequest {
            Objects.requireNonNull(binding, "binding");
            if (cursor != null) { if (cursor.isEmpty()) throw new IllegalArgumentException("cursor is empty"); }
            ContractChecks.safeInteger(limit);
        }
    }

    public record EventPage(
            List<ExecutionEvent> events,
            String cursor,
            boolean hasMore,
            long earliestRetainedSequence,
            long snapshotRevision
    ) {
        public EventPage {
            Objects.requireNonNull(events, "events");
            events = List.copyOf(events);
            if (cursor != null) { if (cursor.isEmpty()) throw new IllegalArgumentException("cursor is empty"); }
            ContractChecks.safeInteger(earliestRetainedSequence);
            ContractChecks.safeInteger(snapshotRevision);
        }
    }

    public record InputRequest(
            BindingRef binding,
            CommandRef command,
            String inputId,
            String clientMessageId,
            String kind,
            String text,
            List<String> attachmentRefs,
            String checkpointRef
    ) {
        public InputRequest {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(inputId, "inputId");
            if (inputId.isEmpty()) throw new IllegalArgumentException("inputId is empty");
            Objects.requireNonNull(clientMessageId, "clientMessageId");
            if (clientMessageId.isEmpty()) throw new IllegalArgumentException("clientMessageId is empty");
            Objects.requireNonNull(kind, "kind");
            if (!Set.of("supplement", "resume_checkpoint").contains(kind)) throw new IllegalArgumentException("Invalid kind");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(attachmentRefs, "attachmentRefs");
            attachmentRefs = List.copyOf(attachmentRefs);
            for (var item : attachmentRefs) { if (item.isEmpty()) throw new IllegalArgumentException("item is empty"); }
            if (checkpointRef != null) { if (checkpointRef.isEmpty()) throw new IllegalArgumentException("checkpointRef is empty"); }
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="status", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=InputReceiptAcceptedPending.class, name="accepted_pending"),
        @JsonSubTypes.Type(value=InputReceiptAdopted.class, name="adopted"),
        @JsonSubTypes.Type(value=InputReceiptRejected.class, name="rejected")
    })
    public sealed interface InputReceipt permits InputReceiptAcceptedPending, InputReceiptAdopted, InputReceiptRejected {}

    public record InputReceiptAcceptedPending(
            String status,
            String inputId,
            Long queuePosition
    ) implements InputReceipt {
        public InputReceiptAcceptedPending {
            Objects.requireNonNull(status, "status");
            if (!Set.of("accepted_pending").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(inputId, "inputId");
            if (inputId.isEmpty()) throw new IllegalArgumentException("inputId is empty");
            if (queuePosition != null) { ContractChecks.safeInteger(queuePosition); }
        }
    }

    public record InputReceiptAdopted(
            String status,
            String inputId,
            String adoptedAtBoundary
    ) implements InputReceipt {
        public InputReceiptAdopted {
            Objects.requireNonNull(status, "status");
            if (!Set.of("adopted").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(inputId, "inputId");
            if (inputId.isEmpty()) throw new IllegalArgumentException("inputId is empty");
            Objects.requireNonNull(adoptedAtBoundary, "adoptedAtBoundary");
            if (adoptedAtBoundary.isEmpty()) throw new IllegalArgumentException("adoptedAtBoundary is empty");
        }
    }

    public record InputReceiptRejected(
            String status,
            String inputId,
            String reason
    ) implements InputReceipt {
        public InputReceiptRejected {
            Objects.requireNonNull(status, "status");
            if (!Set.of("rejected").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(inputId, "inputId");
            if (inputId.isEmpty()) throw new IllegalArgumentException("inputId is empty");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record CancelRequest(
            BindingRef binding,
            CommandRef command,
            String reason
    ) {
        public CancelRequest {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(reason, "reason");
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="status", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=CancelReceiptRequestedOrStopping.class, name="requested"),
        @JsonSubTypes.Type(value=CancelReceiptRequestedOrStopping.class, name="stopping"),
        @JsonSubTypes.Type(value=CancelReceiptConfirmed.class, name="confirmed"),
        @JsonSubTypes.Type(value=CancelReceiptUnknown.class, name="unknown")
    })
    public sealed interface CancelReceipt permits CancelReceiptRequestedOrStopping, CancelReceiptConfirmed, CancelReceiptUnknown {}

    public record CancelReceiptRequestedOrStopping(
            String status,
            String commandId,
            String receivedAt
    ) implements CancelReceipt {
        public CancelReceiptRequestedOrStopping {
            Objects.requireNonNull(status, "status");
            if (!Set.of("requested", "stopping").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(commandId, "commandId");
            if (commandId.isEmpty()) throw new IllegalArgumentException("commandId is empty");
            Objects.requireNonNull(receivedAt, "receivedAt");
            utc(receivedAt);
        }
    }

    public record CancelReceiptConfirmed(
            String status,
            ExecutionSnapshot snapshot,
            String proofRef
    ) implements CancelReceipt {
        public CancelReceiptConfirmed {
            Objects.requireNonNull(status, "status");
            if (!Set.of("confirmed").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(proofRef, "proofRef");
            if (proofRef.isEmpty()) throw new IllegalArgumentException("proofRef is empty");
        }
    }

    public record CancelReceiptUnknown(
            String status,
            String commandId,
            String reason
    ) implements CancelReceipt {
        public CancelReceiptUnknown {
            Objects.requireNonNull(status, "status");
            if (!Set.of("unknown").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(commandId, "commandId");
            if (commandId.isEmpty()) throw new IllegalArgumentException("commandId is empty");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record ResumePreparation(
            BindingRef binding,
            CommandRef command,
            String checkpointRef,
            String priorRequestId,
            String operationHash,
            String noEffectProofRef
    ) {
        public ResumePreparation {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(checkpointRef, "checkpointRef");
            if (checkpointRef.isEmpty()) throw new IllegalArgumentException("checkpointRef is empty");
            Objects.requireNonNull(priorRequestId, "priorRequestId");
            if (priorRequestId.isEmpty()) throw new IllegalArgumentException("priorRequestId is empty");
            Objects.requireNonNull(operationHash, "operationHash");
            if (!operationHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("operationHash is not SHA-256");
            Objects.requireNonNull(noEffectProofRef, "noEffectProofRef");
            if (noEffectProofRef.isEmpty()) throw new IllegalArgumentException("noEffectProofRef is empty");
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="status", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=ResumeReceiptReady.class, name="ready"),
        @JsonSubTypes.Type(value=ResumeReceiptPending.class, name="pending")
    })
    public sealed interface ResumeReceipt permits ResumeReceiptReady, ResumeReceiptPending {}

    public record ResumeReceiptReady(
            String status,
            ProviderPermissionRequest newRequest,
            boolean operationExecuted
    ) implements ResumeReceipt {
        public ResumeReceiptReady {
            Objects.requireNonNull(status, "status");
            if (!Set.of("ready").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(newRequest, "newRequest");
            if (operationExecuted != false) throw new IllegalArgumentException("Invalid operationExecuted");
        }
    }

    public record ResumeReceiptPending(
            String status,
            String preparationId,
            boolean operationExecuted
    ) implements ResumeReceipt {
        public ResumeReceiptPending {
            Objects.requireNonNull(status, "status");
            if (!Set.of("pending").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(preparationId, "preparationId");
            if (preparationId.isEmpty()) throw new IllegalArgumentException("preparationId is empty");
            if (operationExecuted != false) throw new IllegalArgumentException("Invalid operationExecuted");
        }
    }

    public record OperationPlan(
            String operationId,
            String operationHash,
            String systemId,
            String environmentId,
            String parameterHash,
            String targetSetHash,
            String preconditionHash,
            String planContentRef,
            String impactPreviewRef,
            List<String> resourceAuthorizationRefs,
            String budgetReservationId
    ) {
        public OperationPlan {
            Objects.requireNonNull(operationId, "operationId");
            if (operationId.isEmpty()) throw new IllegalArgumentException("operationId is empty");
            Objects.requireNonNull(operationHash, "operationHash");
            if (!operationHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("operationHash is not SHA-256");
            Objects.requireNonNull(systemId, "systemId");
            if (systemId.length()!=36 || !UUID.fromString(systemId).toString().equalsIgnoreCase(systemId)) throw new IllegalArgumentException("systemId is not UUID");
            Objects.requireNonNull(environmentId, "environmentId");
            if (environmentId.length()!=36 || !UUID.fromString(environmentId).toString().equalsIgnoreCase(environmentId)) throw new IllegalArgumentException("environmentId is not UUID");
            Objects.requireNonNull(parameterHash, "parameterHash");
            if (!parameterHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("parameterHash is not SHA-256");
            Objects.requireNonNull(targetSetHash, "targetSetHash");
            if (!targetSetHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("targetSetHash is not SHA-256");
            Objects.requireNonNull(preconditionHash, "preconditionHash");
            if (!preconditionHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("preconditionHash is not SHA-256");
            Objects.requireNonNull(planContentRef, "planContentRef");
            if (planContentRef.isEmpty()) throw new IllegalArgumentException("planContentRef is empty");
            Objects.requireNonNull(impactPreviewRef, "impactPreviewRef");
            if (impactPreviewRef.isEmpty()) throw new IllegalArgumentException("impactPreviewRef is empty");
            Objects.requireNonNull(resourceAuthorizationRefs, "resourceAuthorizationRefs");
            resourceAuthorizationRefs = List.copyOf(resourceAuthorizationRefs);
            for (var item : resourceAuthorizationRefs) { if (item.isEmpty()) throw new IllegalArgumentException("item is empty"); }
            Objects.requireNonNull(budgetReservationId, "budgetReservationId");
            if (budgetReservationId.isEmpty()) throw new IllegalArgumentException("budgetReservationId is empty");
        }
    }

    public record ApprovedOperation(
            String operationId,
            String operationHash,
            String systemId,
            String environmentId,
            String parameterHash,
            String targetSetHash,
            String preconditionHash,
            String planContentRef,
            String impactPreviewRef,
            List<String> resourceAuthorizationRefs,
            String budgetReservationId,
            String businessApprovalId,
            long approvalRevision,
            DecisionAuthority authority,
            String authorizationProofRef,
            String validUntil
    ) {
        public ApprovedOperation {
            Objects.requireNonNull(operationId, "operationId");
            if (operationId.isEmpty()) throw new IllegalArgumentException("operationId is empty");
            Objects.requireNonNull(operationHash, "operationHash");
            if (!operationHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("operationHash is not SHA-256");
            Objects.requireNonNull(systemId, "systemId");
            if (systemId.length()!=36 || !UUID.fromString(systemId).toString().equalsIgnoreCase(systemId)) throw new IllegalArgumentException("systemId is not UUID");
            Objects.requireNonNull(environmentId, "environmentId");
            if (environmentId.length()!=36 || !UUID.fromString(environmentId).toString().equalsIgnoreCase(environmentId)) throw new IllegalArgumentException("environmentId is not UUID");
            Objects.requireNonNull(parameterHash, "parameterHash");
            if (!parameterHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("parameterHash is not SHA-256");
            Objects.requireNonNull(targetSetHash, "targetSetHash");
            if (!targetSetHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("targetSetHash is not SHA-256");
            Objects.requireNonNull(preconditionHash, "preconditionHash");
            if (!preconditionHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("preconditionHash is not SHA-256");
            Objects.requireNonNull(planContentRef, "planContentRef");
            if (planContentRef.isEmpty()) throw new IllegalArgumentException("planContentRef is empty");
            Objects.requireNonNull(impactPreviewRef, "impactPreviewRef");
            if (impactPreviewRef.isEmpty()) throw new IllegalArgumentException("impactPreviewRef is empty");
            Objects.requireNonNull(resourceAuthorizationRefs, "resourceAuthorizationRefs");
            resourceAuthorizationRefs = List.copyOf(resourceAuthorizationRefs);
            for (var item : resourceAuthorizationRefs) { if (item.isEmpty()) throw new IllegalArgumentException("item is empty"); }
            Objects.requireNonNull(budgetReservationId, "budgetReservationId");
            if (budgetReservationId.isEmpty()) throw new IllegalArgumentException("budgetReservationId is empty");
            Objects.requireNonNull(businessApprovalId, "businessApprovalId");
            if (businessApprovalId.isEmpty()) throw new IllegalArgumentException("businessApprovalId is empty");
            ContractChecks.safeInteger(approvalRevision);
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(authorizationProofRef, "authorizationProofRef");
            if (authorizationProofRef.isEmpty()) throw new IllegalArgumentException("authorizationProofRef is empty");
            Objects.requireNonNull(validUntil, "validUntil");
            utc(validUntil);
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="kind", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=DecisionAuthorityHuman.class, name="human"),
        @JsonSubTypes.Type(value=DecisionAuthorityPolicy.class, name="policy")
    })
    public sealed interface DecisionAuthority permits DecisionAuthorityHuman, DecisionAuthorityPolicy {}

    public record DecisionAuthorityHuman(
            String kind,
            String subjectId,
            String qualificationRef
    ) implements DecisionAuthority {
        public DecisionAuthorityHuman {
            Objects.requireNonNull(kind, "kind");
            if (!Set.of("human").contains(kind)) throw new IllegalArgumentException("Invalid kind");
            Objects.requireNonNull(subjectId, "subjectId");
            if (subjectId.isEmpty()) throw new IllegalArgumentException("subjectId is empty");
            Objects.requireNonNull(qualificationRef, "qualificationRef");
            if (qualificationRef.isEmpty()) throw new IllegalArgumentException("qualificationRef is empty");
        }
    }

    public record DecisionAuthorityPolicy(
            String kind,
            String grantId,
            long grantRevision,
            String grantHash,
            String issuerId,
            String serviceIdentityRef,
            String evaluationProofRef
    ) implements DecisionAuthority {
        public DecisionAuthorityPolicy {
            Objects.requireNonNull(kind, "kind");
            if (!Set.of("policy").contains(kind)) throw new IllegalArgumentException("Invalid kind");
            Objects.requireNonNull(grantId, "grantId");
            if (grantId.isEmpty()) throw new IllegalArgumentException("grantId is empty");
            ContractChecks.safeInteger(grantRevision);
            Objects.requireNonNull(grantHash, "grantHash");
            if (!grantHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("grantHash is not SHA-256");
            Objects.requireNonNull(issuerId, "issuerId");
            if (issuerId.isEmpty()) throw new IllegalArgumentException("issuerId is empty");
            Objects.requireNonNull(serviceIdentityRef, "serviceIdentityRef");
            if (serviceIdentityRef.isEmpty()) throw new IllegalArgumentException("serviceIdentityRef is empty");
            Objects.requireNonNull(evaluationProofRef, "evaluationProofRef");
            if (evaluationProofRef.isEmpty()) throw new IllegalArgumentException("evaluationProofRef is empty");
        }
    }

    public record BusinessApprovalRecord(
            DecisionAuthority authority,
            String id,
            BindingRef binding,
            long planRevision,
            long revision,
            OperationPlan plan,
            String decisionState,
            String requestedBy,
            String decidedBy,
            String decidedAt,
            String approverQualificationRef,
            String decisionIdempotencyKey,
            String reason,
            ApprovedOperation approvedOperation,
            String priorApprovalId,
            String validUntil
    ) {
        public BusinessApprovalRecord {
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("id is empty");
            Objects.requireNonNull(binding, "binding");
            ContractChecks.safeInteger(planRevision);
            ContractChecks.safeInteger(revision);
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(decisionState, "decisionState");
            if (!Set.of("pending", "approved", "denied", "changes_requested", "expired", "revoked", "superseded").contains(decisionState)) throw new IllegalArgumentException("Invalid decisionState");
            Objects.requireNonNull(requestedBy, "requestedBy");
            if (requestedBy.isEmpty()) throw new IllegalArgumentException("requestedBy is empty");
            if (decidedBy != null) { if (decidedBy.isEmpty()) throw new IllegalArgumentException("decidedBy is empty"); }
            if (decidedAt != null) { utc(decidedAt); }
            if (approverQualificationRef != null) { if (approverQualificationRef.isEmpty()) throw new IllegalArgumentException("approverQualificationRef is empty"); }
            if (decisionIdempotencyKey != null) { if (decisionIdempotencyKey.isEmpty()) throw new IllegalArgumentException("decisionIdempotencyKey is empty"); }
            if (priorApprovalId != null) { if (priorApprovalId.isEmpty()) throw new IllegalArgumentException("priorApprovalId is empty"); }
            Objects.requireNonNull(validUntil, "validUntil");
            utc(validUntil);
        }
    }

    public record PermissionMapping(
            String businessApprovalId,
            BindingRef binding,
            ProviderPermissionRequest request,
            String deliveryState,
            String decisionCommandId,
            String providerReceiptRef,
            String actionState,
            String operationReceiptRef
    ) {
        public PermissionMapping {
            Objects.requireNonNull(businessApprovalId, "businessApprovalId");
            if (businessApprovalId.isEmpty()) throw new IllegalArgumentException("businessApprovalId is empty");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(deliveryState, "deliveryState");
            if (!Set.of("unmapped", "awaiting_request", "ready", "sending", "delivered", "delivery_unknown", "stale", "unsupported", "denied_by_provider").contains(deliveryState)) throw new IllegalArgumentException("Invalid deliveryState");
            if (decisionCommandId != null) { if (decisionCommandId.isEmpty()) throw new IllegalArgumentException("decisionCommandId is empty"); }
            if (providerReceiptRef != null) { if (providerReceiptRef.isEmpty()) throw new IllegalArgumentException("providerReceiptRef is empty"); }
            Objects.requireNonNull(actionState, "actionState");
            if (!Set.of("not_started", "dispatching", "running", "result_unknown", "verified", "failed", "cancelled").contains(actionState)) throw new IllegalArgumentException("Invalid actionState");
            if (operationReceiptRef != null) { if (operationReceiptRef.isEmpty()) throw new IllegalArgumentException("operationReceiptRef is empty"); }
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="decision", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=PermissionResponseAllow.class, name="allow"),
        @JsonSubTypes.Type(value=PermissionResponseDeny.class, name="deny")
    })
    public sealed interface PermissionResponse permits PermissionResponseAllow, PermissionResponseDeny {}

    public record PermissionResponseAllow(
            BindingRef binding,
            CommandRef command,
            String requestId,
            String requestContextHash,
            long mappingRevision,
            String optionId,
            String decision,
            ApprovedOperation approvedOperation
    ) implements PermissionResponse {
        public PermissionResponseAllow {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId is empty");
            Objects.requireNonNull(requestContextHash, "requestContextHash");
            if (!requestContextHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("requestContextHash is not SHA-256");
            ContractChecks.safeInteger(mappingRevision);
            Objects.requireNonNull(optionId, "optionId");
            if (optionId.isEmpty()) throw new IllegalArgumentException("optionId is empty");
            Objects.requireNonNull(decision, "decision");
            if (!Set.of("allow").contains(decision)) throw new IllegalArgumentException("Invalid decision");
            Objects.requireNonNull(approvedOperation, "approvedOperation");
        }
    }

    public record PermissionResponseDeny(
            BindingRef binding,
            CommandRef command,
            String requestId,
            String requestContextHash,
            long mappingRevision,
            String optionId,
            String decision,
            String businessApprovalId,
            String reason
    ) implements PermissionResponse {
        public PermissionResponseDeny {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId is empty");
            Objects.requireNonNull(requestContextHash, "requestContextHash");
            if (!requestContextHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("requestContextHash is not SHA-256");
            ContractChecks.safeInteger(mappingRevision);
            Objects.requireNonNull(optionId, "optionId");
            if (optionId.isEmpty()) throw new IllegalArgumentException("optionId is empty");
            Objects.requireNonNull(decision, "decision");
            if (!Set.of("deny").contains(decision)) throw new IllegalArgumentException("Invalid decision");
            Objects.requireNonNull(businessApprovalId, "businessApprovalId");
            if (businessApprovalId.isEmpty()) throw new IllegalArgumentException("businessApprovalId is empty");
            Objects.requireNonNull(reason, "reason");
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="status", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=PermissionReceiptDelivered.class, name="delivered"),
        @JsonSubTypes.Type(value=PermissionReceiptDeliveryUnknown.class, name="delivery_unknown"),
        @JsonSubTypes.Type(value=PermissionReceiptDeniedByProvider.class, name="denied_by_provider")
    })
    public sealed interface PermissionReceipt permits PermissionReceiptDelivered, PermissionReceiptDeliveryUnknown, PermissionReceiptDeniedByProvider {}

    public record PermissionReceiptDelivered(
            String status,
            String requestId,
            String commandId,
            String providerReceiptRef,
            String operationExecuted
    ) implements PermissionReceipt {
        public PermissionReceiptDelivered {
            Objects.requireNonNull(status, "status");
            if (!Set.of("delivered").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId is empty");
            Objects.requireNonNull(commandId, "commandId");
            if (commandId.isEmpty()) throw new IllegalArgumentException("commandId is empty");
            Objects.requireNonNull(providerReceiptRef, "providerReceiptRef");
            if (providerReceiptRef.isEmpty()) throw new IllegalArgumentException("providerReceiptRef is empty");
            Objects.requireNonNull(operationExecuted, "operationExecuted");
            if (!Set.of("unknown").contains(operationExecuted)) throw new IllegalArgumentException("Invalid operationExecuted");
        }
    }

    public record PermissionReceiptDeliveryUnknown(
            String status,
            String requestId,
            String commandId
    ) implements PermissionReceipt {
        public PermissionReceiptDeliveryUnknown {
            Objects.requireNonNull(status, "status");
            if (!Set.of("delivery_unknown").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId is empty");
            Objects.requireNonNull(commandId, "commandId");
            if (commandId.isEmpty()) throw new IllegalArgumentException("commandId is empty");
        }
    }

    public record PermissionReceiptDeniedByProvider(
            String status,
            String requestId,
            String commandId,
            String reason
    ) implements PermissionReceipt {
        public PermissionReceiptDeniedByProvider {
            Objects.requireNonNull(status, "status");
            if (!Set.of("denied_by_provider").contains(status)) throw new IllegalArgumentException("Invalid status");
            Objects.requireNonNull(requestId, "requestId");
            if (requestId.isEmpty()) throw new IllegalArgumentException("requestId is empty");
            Objects.requireNonNull(commandId, "commandId");
            if (commandId.isEmpty()) throw new IllegalArgumentException("commandId is empty");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record ArtifactRequest(
            BindingRef binding,
            String artifactId,
            String purpose
    ) {
        public ArtifactRequest {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(artifactId, "artifactId");
            if (artifactId.isEmpty()) throw new IllegalArgumentException("artifactId is empty");
            Objects.requireNonNull(purpose, "purpose");
            if (!Set.of("inspect", "import", "download").contains(purpose)) throw new IllegalArgumentException("Invalid purpose");
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="acceptance", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=ProviderErrorNotAccepted.class, name="not_accepted"),
        @JsonSubTypes.Type(value=ProviderErrorNotSentOrAcceptedOrUnknown.class, name="not_sent"),
        @JsonSubTypes.Type(value=ProviderErrorNotSentOrAcceptedOrUnknown.class, name="accepted"),
        @JsonSubTypes.Type(value=ProviderErrorNotSentOrAcceptedOrUnknown.class, name="unknown")
    })
    public sealed interface ProviderError permits ProviderErrorNotAccepted, ProviderErrorNotSentOrAcceptedOrUnknown {}

    public record ProviderErrorNotAcceptedNonAcceptanceProof(
            String admissionFence,
            String proofRef
    ) {
        public ProviderErrorNotAcceptedNonAcceptanceProof {
            Objects.requireNonNull(admissionFence, "admissionFence");
            if (admissionFence.isEmpty()) throw new IllegalArgumentException("admissionFence is empty");
            Objects.requireNonNull(proofRef, "proofRef");
            if (proofRef.isEmpty()) throw new IllegalArgumentException("proofRef is empty");
        }
    }

    public record ProviderErrorNotAccepted(
            String code,
            String message,
            String correlationId,
            String phase,
            String retryAdvice,
            Long currentRevision,
            String acceptance,
            ProviderErrorNotAcceptedNonAcceptanceProof nonAcceptanceProof
    ) implements ProviderError {
        public ProviderErrorNotAccepted {
            Objects.requireNonNull(code, "code");
            if (!Set.of("UNAUTHENTICATED", "FORBIDDEN", "VALIDATION_FAILED", "CAPABILITY_MISMATCH", "UNSUPPORTED", "REVISION_CONFLICT", "IDEMPOTENCY_CONFLICT", "PROVIDER_UNAVAILABLE", "DEADLINE_EXCEEDED", "SUBMISSION_CLOSED", "CURSOR_EXPIRED", "EVENT_GAP", "PERMISSION_STALE", "APPROVAL_EXPIRED", "PRECONDITION_CHANGED", "BUDGET_BLOCKED", "USAGE_UNKNOWN", "ARTIFACT_UNAVAILABLE", "INTEGRITY_MISMATCH", "RESULT_UNKNOWN").contains(code)) throw new IllegalArgumentException("Invalid code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(correlationId, "correlationId");
            if (correlationId.isEmpty()) throw new IllegalArgumentException("correlationId is empty");
            Objects.requireNonNull(phase, "phase");
            if (!Set.of("validation", "submission", "observation", "permission", "execution", "artifact").contains(phase)) throw new IllegalArgumentException("Invalid phase");
            Objects.requireNonNull(retryAdvice, "retryAdvice");
            if (!Set.of("read_only", "reconcile", "correct_input", "none").contains(retryAdvice)) throw new IllegalArgumentException("Invalid retryAdvice");
            if (currentRevision != null) { ContractChecks.safeInteger(currentRevision); }
            Objects.requireNonNull(acceptance, "acceptance");
            if (!Set.of("not_accepted").contains(acceptance)) throw new IllegalArgumentException("Invalid acceptance");
            Objects.requireNonNull(nonAcceptanceProof, "nonAcceptanceProof");
        }
    }

    public record ProviderErrorNotSentOrAcceptedOrUnknown(
            String code,
            String message,
            String correlationId,
            String phase,
            String retryAdvice,
            Long currentRevision,
            String acceptance,
            Void nonAcceptanceProof
    ) implements ProviderError {
        public ProviderErrorNotSentOrAcceptedOrUnknown {
            Objects.requireNonNull(code, "code");
            if (!Set.of("UNAUTHENTICATED", "FORBIDDEN", "VALIDATION_FAILED", "CAPABILITY_MISMATCH", "UNSUPPORTED", "REVISION_CONFLICT", "IDEMPOTENCY_CONFLICT", "PROVIDER_UNAVAILABLE", "DEADLINE_EXCEEDED", "SUBMISSION_CLOSED", "CURSOR_EXPIRED", "EVENT_GAP", "PERMISSION_STALE", "APPROVAL_EXPIRED", "PRECONDITION_CHANGED", "BUDGET_BLOCKED", "USAGE_UNKNOWN", "ARTIFACT_UNAVAILABLE", "INTEGRITY_MISMATCH", "RESULT_UNKNOWN").contains(code)) throw new IllegalArgumentException("Invalid code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(correlationId, "correlationId");
            if (correlationId.isEmpty()) throw new IllegalArgumentException("correlationId is empty");
            Objects.requireNonNull(phase, "phase");
            if (!Set.of("validation", "submission", "observation", "permission", "execution", "artifact").contains(phase)) throw new IllegalArgumentException("Invalid phase");
            Objects.requireNonNull(retryAdvice, "retryAdvice");
            if (!Set.of("read_only", "reconcile", "correct_input", "none").contains(retryAdvice)) throw new IllegalArgumentException("Invalid retryAdvice");
            if (currentRevision != null) { ContractChecks.safeInteger(currentRevision); }
            Objects.requireNonNull(acceptance, "acceptance");
            if (!Set.of("not_sent", "accepted", "unknown").contains(acceptance)) throw new IllegalArgumentException("Invalid acceptance");
            if (nonAcceptanceProof != null) throw new IllegalArgumentException("nonAcceptanceProof must be null");
        }
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="ok", visible=true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value=ResultTrue.class, name="true"),
        @JsonSubTypes.Type(value=ResultFalse.class, name="false")
    })
    public sealed interface Result<T> permits ResultTrue, ResultFalse {}

    public record ResultTrue<T>(
            boolean ok,
            T value
    ) implements Result<T> {
        public ResultTrue {
            if (ok != true) throw new IllegalArgumentException("Invalid ok");
            Objects.requireNonNull(value, "value");
        }
    }

    public record ResultFalse<T>(
            boolean ok,
            ProviderError error
    ) implements Result<T> {
        public ResultFalse {
            if (ok != false) throw new IllegalArgumentException("Invalid ok");
            Objects.requireNonNull(error, "error");
        }
    }
}
