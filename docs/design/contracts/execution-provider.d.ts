/** Design-only declarations. No transport, credentials, adapter or production implementation. */
export type ContractVersion = 'initial-draft';
export type Id = string;
export type Instant = string; // RFC3339 UTC; never infer unknown timestamps.
export type Sha256 = string; // 64 lowercase hex characters.
export type Decimal = string; // finite non-negative decimal, no floating-point currency.
export type KnownBoolean = boolean | 'unknown';
export type EffectStatus = 'none' | 'verified' | 'unknown';
export type EvidenceLevel = 'advertised' | 'adapter_supported' | 'verified' | 'unknown' | 'unsupported';

export interface CallContext {
  contractVersion: ContractVersion;
  correlationId: Id;
  tenantId: Id;
  subjectContext: { subjectId: Id; authorizationRef: Id }; // authenticated server context
  deadline: Instant;
}
export interface ConnectionRef { providerId: Id; connectionId: Id; connectionRevision: number }
export interface BindingRef { bindingId: Id; nodeRunId: Id; providerId: Id }
export interface SubmissionRef extends BindingRef {
  tenantId: Id;
  idempotencyKey: Id;
  specHash: Sha256;
}
export interface CommandRef {
  commandId: Id;
  idempotencyKey: Id;
  requestHash: Sha256;
  expectedRevision: number;
}
export interface CapabilitySnapshot {
  connection: ConnectionRef;
  adapterVersion: string;
  backendVersion: string | null;
  agentVersion: string | null;
  negotiatedProtocol: string | null;
  configurationHash: Sha256;
  observedAt: Instant;
  validUntil: Instant;
  capabilities: Array<{
    name: 'image_input' | 'resume_checkpoint' | 'permission_bridge' | 'binary_artifact'
      | 'durable_events' | 'idempotent_start' | 'submission_fence' | 'hard_call_limit';
    level: EvidenceLevel;
    evidenceRef: Id | null;
  }>;
  modelOptions: Array<{ modelId: Id; confirmed: boolean }>;
}
export interface ExecutionSpec {
  contractVersion: ContractVersion;
  tenantId: Id;
  conversationId: Id;
  workflowRunId: Id;
  workflowVersion: Id;
  nodeRunId: Id;
  bindingId: Id;
  ownershipEpoch: number;
  connection: ConnectionRef;
  capabilitySnapshotHash: Sha256;
  agentId: Id;
  systemId: Id;
  environmentId: Id;
  primaryRepositoryId: Id | null;
  deployedCommit: string | null;
  codeCommit: string | null;
  objective: string;
  preparation: {
    inputRevision: number;
    configurationSnapshotRevision: number;
    admissionPolicyRevision: number;
    integrationSnapshotRef: Id;
    integrationSnapshotHash: Sha256;
    environmentResourcesRef: Id;
    environmentResourcesHash: Sha256;
    executionPolicyDecisionRef: Id;
    effectiveWorkflowRef: Id;
    effectiveWorkflowHash: Sha256;
    runtimeManifestRef: Id;
    runtimeManifestHash: Sha256;
    contextManifestRef: Id;
    contextManifestHash: Sha256;
    authorizationRevision: number;
  };
  inputs: Array<{ attachmentId: Id; sha256: Sha256; authorizationRef: Id }>;
  executionIdentityRef: Id;
  workspaceAuthorizationRef: Id;
  resourceAuthorizationRefs: Id[];
  modelRequest: { modelId: Id; presetId: Id; presetRevision: number };
  skills: Array<{ skillId: Id; revision: number; sha256: Sha256 }>;
  budget: {
    modelQuotaSnapshotRef: Id;
    modelQuotaSnapshotHash: Sha256; // binds subject / connection / agent / canonical model / policy / period
    reservationId: Id;
    scopeId: Id; // descendants share this scope; no parent/child double billing
    maxNewCalls: number;
    maxConcurrency: number;
    estimatedAmount: Decimal | null;
    currency: string | null;
    unknownUsagePolicy: 'block_new_billable_actions';
    callLimitEnforcement: 'verified' | 'adapter_only' | 'unknown';
  };
  deadline: Instant;
  outputContract: { schemaRef: Id; artifactTypes: string[]; evidenceRequired: boolean };
}
export interface ValidationReport {
  specHash: Sha256;
  checkedAt: Instant;
  validUntil: Instant;
  canStart: boolean;
  blockers: Array<{ code: ErrorCode; field: string; detail: string }>;
  warnings: string[];
}
export interface StartRequest {
  submission: SubmissionRef;
  command: CommandRef;
  spec: ExecutionSpec;
}
export interface ProviderRunRef {
  providerRunId: Id;
  taskId: Id | null;
  sessionId: Id | null;
  attemptId: Id | null; // owner-reported fact, not a platform retry command
}
export type StartReceipt = {
  status: 'accepted';
  binding: BindingRef;
  run: ProviderRunRef;
  acceptedAt: Instant;
  receiptId: Id;
  specHash: Sha256;
  reused: boolean;
};
export type SubmissionLookup =
  | { status: 'found'; receipt: StartReceipt }
  | { status: 'not_found'; observedAt: Instant; authoritativeNonAcceptance: false }
  | { status: 'unknown'; reason: string; observedAt: Instant };
export interface CloseSubmissionRequest { submission: SubmissionRef; command: CommandRef }
export type SubmissionClosure =
  | { status: 'found'; receipt: StartReceipt }
  | { status: 'not_accepted_final'; admissionFence: Id; closedAt: Instant; proofRef: Id }
  | { status: 'unknown'; reason: string };

export type BindingState = 'prepared' | 'submitting' | 'acceptance_unknown' | 'accepted'
  | 'active' | 'waiting_permission' | 'stopping' | 'reconciling' | 'terminal' | 'rejected';
export interface ProviderPermissionRequest {
  requestId: Id;
  requestContextHash: Sha256;
  operationId: Id;
  operationHash: Sha256;
  mappingRevision: number;
  validity: 'live' | 'stale' | 'unknown';
  observedAt: Instant;
  expiresAt: Instant | null; // null is unknown/no supplied expiry, never perpetual validity
  options: Array<{ optionId: Id; kind: 'allow_once' | 'reject_once' }>;
}
export interface ExecutionSnapshot {
  binding: BindingRef;
  run: ProviderRunRef | null;
  state: BindingState;
  snapshotRevision: number;
  eventWatermark: number;
  observedAt: Instant;
  executionActive: KnownBoolean;
  outcome: 'succeeded' | 'failed' | 'cancelled' | null;
  effectStatus: EffectStatus;
  effectReceiptRefs: Id[];
  modelConfirmed: Id | null;
  permissions: ProviderPermissionRequest[];
  permissionDeliveries: Array<{
    requestId: Id;
    decisionCommandId: Id;
    status: 'not_sent' | 'delivered' | 'unknown' | 'denied_by_provider';
    providerReceiptRef: Id | null;
    observedAt: Instant;
  }>;
  operationFacts: Array<{
    operationId: Id;
    operationHash: Sha256;
    state: 'not_started' | 'running' | 'succeeded' | 'failed' | 'cancelled' | 'unknown';
    receiptRef: Id | null;
    observedAt: Instant;
  }>;
  checkpointRef: Id | null;
}
export type EventPayload =
  | { type: 'execution.accepted'; receipt: StartReceipt }
  | { type: 'execution.updated'; snapshot: ExecutionSnapshot }
  | { type: 'permission.requested'; request: ProviderPermissionRequest }
  | { type: 'permission.delivery'; requestId: Id; decisionCommandId: Id; status: 'delivered' | 'denied_by_provider' }
  | { type: 'input.adopted'; inputId: Id; adoptedAtBoundary: Id }
  | { type: 'usage.reported'; usage: UsageFact }
  | { type: 'artifact.ready'; artifact: ArtifactDescriptor }
  | { type: 'diagnostic'; code: string; summary: string };
export interface ExecutionEvent {
  providerId: Id;
  providerRunId: Id;
  providerEventId: Id;
  bindingId: Id;
  sequence: number;
  sourceSequence: number | null;
  occurredAt: Instant;
  receivedAt: Instant;
  payloadVersion: 1;
  visibility: 'business' | 'operator';
  payload: EventPayload;
}
export interface EventReadRequest { binding: BindingRef; cursor: string | null; limit: number }
export interface EventPage {
  events: ExecutionEvent[];
  cursor: string | null;
  hasMore: boolean;
  earliestRetainedSequence: number;
  snapshotRevision: number;
}
export interface InputRequest {
  binding: BindingRef;
  command: CommandRef;
  inputId: Id;
  clientMessageId: Id;
  kind: 'supplement' | 'resume_checkpoint';
  text: string;
  attachmentRefs: Id[];
  checkpointRef: Id | null;
}
export type InputReceipt =
  | { status: 'accepted_pending'; inputId: Id; queuePosition: number | null }
  | { status: 'adopted'; inputId: Id; adoptedAtBoundary: Id }
  | { status: 'rejected'; inputId: Id; reason: string };
export interface CancelRequest { binding: BindingRef; command: CommandRef; reason: string }
export type CancelReceipt =
  | { status: 'requested' | 'stopping'; commandId: Id; receivedAt: Instant }
  | { status: 'confirmed'; snapshot: ExecutionSnapshot; proofRef: Id }
  | { status: 'unknown'; commandId: Id; reason: string };
export interface ResumePreparation {
  binding: BindingRef;
  command: CommandRef;
  checkpointRef: Id;
  priorRequestId: Id;
  operationHash: Sha256;
  noEffectProofRef: Id;
}
export type ResumeReceipt =
  | { status: 'ready'; newRequest: ProviderPermissionRequest; operationExecuted: false }
  | { status: 'pending'; preparationId: Id; operationExecuted: false };
export interface OperationPlan {
  operationId: Id;
  operationHash: Sha256;
  systemId: Id;
  environmentId: Id;
  parameterHash: Sha256;
  targetSetHash: Sha256;
  preconditionHash: Sha256;
  planContentRef: Id;
  impactPreviewRef: Id;
  resourceAuthorizationRefs: Id[];
  budgetReservationId: Id;
}
export interface ApprovedOperation extends OperationPlan {
  businessApprovalId: Id;
  approvalRevision: number;
  approverSubjectId: Id;
  authorizationProofRef: Id;
  validUntil: Instant;
}
export type DecisionAuthority =
  | { kind: 'human'; subjectId: Id; qualificationRef: Id }
  | { kind: 'policy'; grantId: Id; grantRevision: number; grantHash: Sha256; issuerId: Id; serviceIdentityRef: Id; evaluationProofRef: Id };
export interface BusinessApprovalRecord {
  authority: DecisionAuthority;
  id: Id;
  binding: BindingRef;
  planRevision: number;
  revision: number;
  plan: OperationPlan;
  decisionState: 'pending' | 'approved' | 'denied' | 'changes_requested' | 'expired' | 'revoked' | 'superseded';
  requestedBy: Id;
  decidedBy: Id | null;
  decidedAt: Instant | null;
  approverQualificationRef: Id | null;
  decisionIdempotencyKey: Id | null;
  reason: string | null;
  approvedOperation: ApprovedOperation | null;
  priorApprovalId: Id | null;
  validUntil: Instant;
}
export interface PermissionMapping {
  businessApprovalId: Id;
  binding: BindingRef;
  request: ProviderPermissionRequest;
  deliveryState: 'unmapped' | 'awaiting_request' | 'ready' | 'sending' | 'delivered'
    | 'delivery_unknown' | 'stale' | 'unsupported' | 'denied_by_provider';
  decisionCommandId: Id | null;
  providerReceiptRef: Id | null;
  actionState: 'not_started' | 'dispatching' | 'running' | 'result_unknown' | 'verified' | 'failed' | 'cancelled';
  operationReceiptRef: Id | null;
}
export type PermissionResponse = {
  binding: BindingRef;
  command: CommandRef;
  requestId: Id;
  requestContextHash: Sha256;
  mappingRevision: number;
  optionId: Id;
} & (
  | { decision: 'allow'; approvedOperation: ApprovedOperation }
  | { decision: 'deny'; businessApprovalId: Id; reason: string }
);
export type PermissionReceipt =
  | { status: 'delivered'; requestId: Id; commandId: Id; providerReceiptRef: Id; operationExecuted: 'unknown' }
  | { status: 'delivery_unknown'; requestId: Id; commandId: Id }
  | { status: 'denied_by_provider'; requestId: Id; commandId: Id; reason: string };
export interface ArtifactRequest { binding: BindingRef; artifactId: Id; purpose: 'inspect' | 'import' | 'download' }
export interface ArtifactDescriptor {
  artifactId: Id;
  bindingId: Id;
  mimeType: string;
  bytes: number;
  sha256: Sha256;
  sourceRef: Id;
  importState: 'metadata_only' | 'importing' | 'verified' | 'failed';
  authorizationRef: Id;
  access: { ticketRef: Id; expiresAt: Instant; purpose: 'inspect' | 'import' | 'download' } | null;
}
export type UsageFact = {
  scopeId: Id;
  sourceId: Id;
  meter: string;
  aggregation: 'delta' | 'cumulative';
  coverage: 'run_total' | 'child_only';
  observedAt: Instant;
} & (
  | { quality: 'actual' | 'estimate'; amount: Decimal; unit: string; currency: string | null }
  | { quality: 'unreported'; amount: null; unit: string | null; currency: string | null }
);
export type ErrorCode = 'UNAUTHENTICATED' | 'FORBIDDEN' | 'VALIDATION_FAILED'
  | 'CAPABILITY_MISMATCH' | 'UNSUPPORTED' | 'REVISION_CONFLICT' | 'IDEMPOTENCY_CONFLICT'
  | 'PROVIDER_UNAVAILABLE' | 'DEADLINE_EXCEEDED' | 'SUBMISSION_CLOSED' | 'CURSOR_EXPIRED'
  | 'EVENT_GAP' | 'PERMISSION_STALE' | 'APPROVAL_EXPIRED' | 'PRECONDITION_CHANGED'
  | 'BUDGET_BLOCKED' | 'USAGE_UNKNOWN' | 'ARTIFACT_UNAVAILABLE' | 'INTEGRITY_MISMATCH' | 'RESULT_UNKNOWN';
export type ProviderError = {
  code: ErrorCode;
  message: string;
  correlationId: Id;
  phase: 'validation' | 'submission' | 'observation' | 'permission' | 'execution' | 'artifact';
  retryAdvice: 'read_only' | 'reconcile' | 'correct_input' | 'none';
  currentRevision: number | null;
} & (
  | { acceptance: 'not_accepted'; nonAcceptanceProof: { admissionFence: Id; proofRef: Id } }
  | { acceptance: 'not_sent' | 'accepted' | 'unknown'; nonAcceptanceProof: null }
);
export type Result<T> = { ok: true; value: T } | { ok: false; error: ProviderError };
export interface ExecutionProvider {
  inspectCapabilities(context: CallContext, connection: ConnectionRef): Promise<Result<CapabilitySnapshot>>;
  validateBinding(context: CallContext, spec: ExecutionSpec): Promise<Result<ValidationReport>>;
  start(context: CallContext, request: StartRequest): Promise<Result<StartReceipt>>;
  lookupSubmission(context: CallContext, request: SubmissionRef): Promise<Result<SubmissionLookup>>;
  closeSubmission(context: CallContext, request: CloseSubmissionRequest): Promise<Result<SubmissionClosure>>;
  getSnapshot(context: CallContext, binding: BindingRef): Promise<Result<ExecutionSnapshot>>;
  readEvents(context: CallContext, request: EventReadRequest): Promise<Result<EventPage>>;
  submitInput(context: CallContext, request: InputRequest): Promise<Result<InputReceipt>>;
  cancel(context: CallContext, request: CancelRequest): Promise<Result<CancelReceipt>>;
  preparePermissionResume(context: CallContext, request: ResumePreparation): Promise<Result<ResumeReceipt>>;
  respondPermission(context: CallContext, request: PermissionResponse): Promise<Result<PermissionReceipt>>;
  getArtifact(context: CallContext, request: ArtifactRequest): Promise<Result<ArtifactDescriptor>>;
}
