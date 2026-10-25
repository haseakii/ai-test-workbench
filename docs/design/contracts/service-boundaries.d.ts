/** Design-only boundary DTOs. No HTTP handler, model client, worker or production authorization. */
import type { Id, Instant, Sha256, EvidenceLevel } from './execution-provider';
export interface RevisionRef { id: Id; revision: number; sha256: Sha256 }
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
  codeSnapshot: { repositoryId: Id; codeCommit: string | null; deployedCommit: string | null; pathPolicyRef: Id };
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
