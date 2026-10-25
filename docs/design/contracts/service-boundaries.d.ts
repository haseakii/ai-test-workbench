/** Design-only boundary DTOs. No HTTP handler, model client, worker or production authorization. */
import type { Id, Instant, Sha256, EvidenceLevel } from './execution-provider';
export interface RevisionRef { id: Id; revision: number; sha256: Sha256 }
export type CodeSource = {
  kind: 'git';
  provider: 'gitlab' | 'generic';
  repositoryAddressRef: Id;
  requestedRef: string;
  authentication: { mode: 'none' } | {
    mode: 'access_token' | 'ssh_key' | 'secret_reference';
    credentialRef: Id;
    credentialRevision: number;
    usernameRef: Id | null;
    // Never put a token/private key into CodeSource or an execution/context manifest.
  };
} | {
  kind: 'local_directory';
  executionHostRef: Id;
  registeredRootRef: Id;
  directoryRef: Id;
};
export interface CodeSourceSnapshot {
  project: RevisionRef;
  source: CodeSource;
  pathPolicyRef: Id;
  snapshotRef: Id | null;
  contentManifestHash: Sha256 | null;
  actualCommit: string | null;
  dirty: boolean | null;
  deployedCommit: string | null;
  capturedAt: Instant | null;
  verified: boolean;
}
/** Display metadata only. Never part of an operation hash or execution identity. */
export interface ConversationTitle {
  conversationId: Id;
  title: string;
  titleRevision: number;
  source: 'placeholder' | 'automatic' | 'manual';
  refinementUsed: boolean;
}
export interface RenameConversationCommand {
  title: string;
  expectedTitleRevision: number;
  idempotencyKey: Id;
  // Subject/ACL and normalization are resolved server-side. Names need not be unique.
}
export interface AutomaticTitleCandidate {
  conversationId: Id;
  title: string;
  sourceInputRevision: number;
  expectedTitleRevision: number;
  generatorRef: RevisionRef;
  // Accept only while non-manual, input applicable and refinement still allowed.
}
/** Server-derived identity. Email/display name is never a stable authorization key. */
export interface VerifiedIdentity {
  issuer: string;
  subject: Id;
  tenantId: Id;
  verifiedAt: Instant;
  sessionRef: Id;
}
export interface MembershipRequestCommand {
  idempotencyKey: Id;
  purpose: string;
  // Identity and organization come from the verified server session, not this body.
}
export interface MembershipRequest {
  id: Id;
  tenantId: Id;
  subjectId: Id;
  revision: number;
  status: 'pending' | 'approved' | 'rejected' | 'withdrawn';
  purpose: string;
  createdAt: Instant;
  decidedAt: Instant | null;
  decisionActorRef: Id | null;
}
export type MembershipDecisionCommand = {
  idempotencyKey: Id;
  requestId: Id;
  expectedRequestRevision: number;
  expectedAuthorizationRevision: number;
  reason: string;
} & ({
  decision: 'approve';
  roleRef: RevisionRef;
  environmentIds: Id[];
  executionMode: 'confirm' | 'approval' | 'collect_only' | 'auto_readonly';
} | { decision: 'reject' });
/** Membership, grants, request decision, audit and notification outbox commit together. */
export interface MembershipDecisionReceipt {
  request: MembershipRequest;
  membershipRef: Id | null;
  authorizationRevision: number;
  notification: 'queued' | 'delivered' | 'retrying';
  // Never implies an execution approval or an external action has started.
}
export interface SourceRef {
  kind: 'message' | 'attachment' | 'code' | 'query' | 'log' | 'memory' | 'summary';
  object: RevisionRef;
  sourceAuthorizationRef: Id;
  systemId: Id;
  environmentId: Id;
  observedAt: Instant | null;
  location: string;
  truncated: boolean;
  trust: 'user_statement' | 'observed_evidence' | 'derived_summary' | 'untrusted_content';
}
export interface ContextManifest {
  tenantId: Id;
  subjectId: Id;
  conversationId: Id;
  nodeRunId: Id;
  configuration: RevisionRef;
  authorizationRevision: number;
  systemId: Id;
  environmentId: Id;
  modelObservation: RevisionRef;
  configuredBackground: ConfiguredExecutionBackground;
  sources: SourceRef[];
  excluded: Array<{ sourceRef: Id; reason: string }>;
  tokenBudget: { window: number | null; outputReserved: number; inputMaximum: number; method: 'tokenizer' | 'estimate' | 'unknown'; tokenizerRef: string | null };
  sha256: Sha256;
}
export interface ModelAccessProfile {
  id: Id;
  revision: number;
  mode: 'executor_managed' | 'host_secret_reference';
  endpointRef: Id | null;
  secretRef: Id | null;
  allowedRegionPolicyRef: Id;
  ownerSubjectId: Id;
  authentication: 'unknown' | 'valid' | 'needs_auth' | 'revoked';
}
export interface AgentConnection {
  id: Id;
  revision: number;
  provider: 'control' | 'direct_acp';
  agentArtifact: RevisionRef;
  negotiatedProtocol: string | null;
  runtimeProfileRef: Id;
  authProfileRef: Id;
  capabilities: Array<{ name: string; level: EvidenceLevel; evidenceRef: Id | null }>;
  observationExpiresAt: Instant | null;
}
export interface McpConnection {
  id: Id;
  revision: number;
  transport: 'stdio' | 'streamable_http';
  negotiatedProtocol: string | null;
  endpointOrLaunchTemplateRef: Id;
  authProfileRef: Id;
  tools: Array<{ name: string; schemaHash: Sha256; policyRef: Id; risk: 'read' | 'write' | 'external_transfer' | 'unknown'; level: EvidenceLevel }>;
}
export interface RuntimeManifest {
  profile: RevisionRef;
  agentArtifactHash: Sha256;
  executionIdentityRef: Id;
  workspaceAuthorizationRef: Id;
  codeSnapshots: CodeSourceSnapshot[];
  networkPolicyRef: Id;
  resourcePolicyRefs: Id[];
  isolationEvidenceRef: Id | null;
  limits: { calls: number; concurrency: number; wallTimeSeconds: number; outputBytes: number };
}
export interface PreparationManifest {
  integrationSnapshot: RevisionRef;
  runtimeManifest: RevisionRef;
  contextManifest: RevisionRef;
  authorizationRevision: number;
  modelQuota: ModelQuotaSnapshot;
  executionSetup: ExecutionSetupSnapshot;
}

/** Discovery is an observation, not publication or permission to execute. */
export interface AgentAdmissionObservation {
  connection: RevisionRef;
  configurationHash: Sha256;
  observedAt: Instant;
  expiresAt: Instant;
  status: 'needs_auth' | 'observed' | 'verification_required' | 'eligible' | 'stale' | 'unsupported';
  models: Array<{ canonicalModelId: Id; capabilities: string[]; evidenceRef: Id | null }>;
  runtimeEvidenceRef: Id | null;
  synthetic: boolean; // synthetic=true can never satisfy production admission.
}
export interface PublishedModelPreset {
  preset: RevisionRef;
  displayName: string;
  connection: RevisionRef;
  agentId: Id;
  canonicalModelId: Id;
  observation: RevisionRef;
  enabled: boolean;
}
export interface PublishedContextPolicy {
  policy: RevisionRef;
  maximumInputTokens: number;
  outputReservedTokens: number;
  recentMessageLimit: number;
  memoryMode: 'off' | 'personal_reviewed' | 'environment_reviewed';
  // Actual model window, source ACL, subject ownership and evidence remain separate gates.
}
export interface EnvironmentExecutionConfiguration {
  environment: RevisionRef;
  systemId: Id;
  allowedModelPresets: RevisionRef[];
  defaultModelPreset: RevisionRef;
  contextPolicy: RevisionRef;
  resources: RevisionRef;
}
export interface ExecutionSetupSnapshot {
  environmentConfiguration: RevisionRef;
  modelPreset: RevisionRef;
  connection: RevisionRef;
  agentId: Id;
  runtimeProfile: RevisionRef;
  contextPolicy: RevisionRef;
  capabilityObservation: RevisionRef;
  actualModelId: Id | null;
  // Plan approval binds this snapshot. New configuration cannot rewrite an active Binding.
}

/** Description text is background data, never an instruction or authorization. */
export interface ConfiguredExecutionBackground {
  system: RevisionRef;
  environment: RevisionRef;
  projects: RevisionRef[];
  descriptions: Array<{
    source: RevisionRef;
    kind: 'system' | 'environment' | 'project';
    sanitizedText: string;
    trust: 'configured_background';
    sha256: Sha256;
  }>;
  environmentFacts: {
    timezone: string;
    expectedDataDelayMinutes: number | null;
    configuredDeploymentRef: string | null;
    // Configured values require independent observation before evidence claims.
  };
  resourceManifest: RevisionRef;
  sha256: Sha256;
}
export interface ModelQuotaKey {
  tenantId: Id;
  subjectId: Id;
  connectionId: Id;
  agentId: Id;
  canonicalModelId: Id;
}
export interface ModelQuotaLimits {
  dailyTurns: number; // Platform-dispatched model-purpose requests; not internal model calls.
  dailyCalls: number;
  dailyTokens: number;
  maxCallsPerTurn: number;
  maxConcurrentExecutions: number;
  // Nonnegative integers; zero denies new calls. Null is not unlimited.
}
export interface ModelQuotaSnapshot {
  key: ModelQuotaKey;
  policy: RevisionRef;
  globalDefault: RevisionRef;
  userOverride: RevisionRef | null;
  inherited: boolean;
  effectiveLimits: ModelQuotaLimits;
  period: { timezone: string; start: Instant; end: Instant };
  reservationId: Id | null;
  usageState: 'known' | 'partially_reported' | 'unknown';
  enforcement: 'verified' | 'unsupported' | 'unknown'; // Derived for the required metrics, never from ACP alone.
  requireHardModelLimits: boolean;
  userTotalAccountRef: Id;
  usageCapability: 'unreported' | 'reported' | 'enforced';
  metricEvidence: Array<{
    metric: 'model_calls' | 'tokens' | 'cost';
    reporting: 'verified' | 'unsupported' | 'unknown';
    enforcement: 'verified' | 'unsupported' | 'unknown';
    evidenceRef: Id | null;
  }>;
  unknownUsagePolicy: 'block_new_billable_actions' | 'allow_with_platform_limits';
  riskAcceptanceRef: Id | null;
  // Current spend/reservations are not reset by changing a limit or display name.
}
export interface ConversationCommand {
  clientMessageId: Id;
  idempotencyKey: Id;
  expectedRevision: number;
  text: string;
  attachmentRefs: Id[];
  // No client-supplied tenant, grants, role, execution identity or raw provider credentials.
}
export interface PlatformEvent {
  id: Id;
  conversationId: Id;
  nodeRunId: Id | null;
  sequence: number;
  source: 'platform' | 'provider' | 'resource_service' | 'user';
  occurredAt: Instant | null;
  receivedAt: Instant;
  causationId: Id;
  visibility: 'owner' | 'collaborators' | 'support_metadata' | 'restricted';
  payloadRef: Id;
}
export interface GrantChange {
  targetSubjectId: Id;
  expectedAuthorizationRevision: number;
  roleIds: Id[];
  environmentIds: Id[];
  expiresAt: Instant | null;
  reason: string;
  // actor/grantableScope derived server-side; reject self elevation and out-of-scope grants.
}
export interface ContentLease {
  id: Id;
  subjectId: Id;
  conversationId: Id;
  fieldScopes: Array<'messages' | 'evidence' | 'events'>;
  purpose: string;
  authorizationRevision: number;
  validUntil: Instant;
  auditReceiptRef: Id;
  // Attachment/Artifact download needs separate authorization; lease is not a bearer token.
}
export interface MemoryItem {
  id: Id;
  revision: number;
  scope: { kind: 'personal' | 'environment'; ownerId: Id; environmentId: Id | null };
  state: 'candidate' | 'reviewed' | 'active' | 'rejected' | 'expired' | 'revoked' | 'deletion_pending' | 'deleted';
  sourceRefs: SourceRef[];
  authorizationRef: Id;
  reviewedBy: Id | null;
  expiresAt: Instant;
  supersedes: RevisionRef | null;
  deletionReceipts: Array<{ target: string; status: 'pending' | 'confirmed' | 'unsupported' }>;
}

/** A plan is valid only for this input, frozen configuration, current admission and exact dependencies. */
export interface PlanDependencyBinding {
  conversationId: Id;
  inputRevision: number;
  configurationSnapshotRevision: number;
  admissionPolicyRevision: number;
  authorizationRevision: number;
  preparation: PreparationManifest;
  requestedModelId: Id;
  actualModelObservationRef: Id | null;
  mcpSchemaHash: Sha256;
  memoryRefs: RevisionRef[];
  operationHash: Sha256;
  validUntil: Instant;
}
export interface ResultDelivery {
  resultId: Id;
  resultRevision: number;
  inputRevision: number;
  contextManifest: RevisionRef;
  evidenceRefs: SourceRef[];
  artifacts: Array<{ artifactId: Id; status: 'importing' | 'ready' | 'failed'; sha256: Sha256 | null }>;
  verification: { subjectId: Id; resultRevision: number; inputRevision: number; verifiedAt: Instant } | null;
  // Ready requires a verified non-null digest; each read/download checks current source ACL.
  // An old verification remains historical when a newer input/result supersedes it.
}

export type ExecutionMode = 'auto_readonly' | 'approval' | 'confirm' | 'collect_only';
export interface ExecutionPolicyDecision {
  requestedMode: ExecutionMode;
  effectiveMode: ExecutionMode | 'blocked';
  subjectAuthorizationRevision: number;
  environmentPolicyRevision: number;
  policyRef: RevisionRef;
  reasonCodes: string[];
  realAdmission: 'unknown' | 'verified' | 'blocked';
}
export interface EnvironmentResourceSnapshot {
  environmentId: Id;
  configurationRevision: number;
  projects: CodeSourceSnapshot[];
  mcps: Array<{ connection: RevisionRef; toolPolicyRef: Id; observedSchemaHash: Sha256 | null }>;
  skills: Array<{ skill: RevisionRef; sourceRef: Id; verifiedContentHash: Sha256 | null; requiredMcpIds: Id[] }>;
  executionPolicy: ExecutionPolicyDecision;
}
export interface AutoExecutionGrant {
  id: Id;
  revision: number;
  issuerSubjectId: Id;
  effectiveSubjectId: Id;
  environmentId: Id;
  allowedOperationTemplateRefs: Id[];
  resourceSnapshot: RevisionRef;
  limits: { maxCalls: number; maxConcurrency: number; maxOutputBytes: number };
  validUntil: Instant;
  authorizationProofRef: Id;
}

/** Server-resolved, immutable plan limits; configured candidates are not execution admission. */
export interface EffectiveWorkflowManifest {
  workflow: RevisionRef;
  environmentResources: RevisionRef;
  subjectAuthorizationRevision: number;
  modelPreset: RevisionRef;
  connection: RevisionRef;
  tools: Array<{ connection: RevisionRef; toolName: string; schemaHash: Sha256 | null; resourcePolicyRef: Id }>;
  skills: Array<{ skill: RevisionRef; verifiedContentHash: Sha256 | null }>;
  codeProjects: CodeSourceSnapshot[];
  reasonCodes: string[];
  admission: 'unknown' | 'blocked' | 'eligible';
  observationExpiresAt: Instant | null;
  // Eligible requires current verified model/tool/runtime evidence and current resource authority.
}
