-- REVIEW ONLY: empty database candidate; not wired, verified, or authorized for deployment.
CREATE TABLE tenants (
 id BINARY(16) PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 1 CHECK(revision BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB;
CREATE TABLE subjects (
 id BINARY(16) PRIMARY KEY, alias VARBINARY(80) NOT NULL UNIQUE
) ENGINE=InnoDB;
CREATE TABLE memberships (
 tenant_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL,
 role_name VARCHAR(20) NOT NULL, state VARCHAR(20) NOT NULL, revision BIGINT NOT NULL DEFAULT 1 CHECK(revision BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,subject_id), FOREIGN KEY(tenant_id) REFERENCES tenants(id), FOREIGN KEY(subject_id) REFERENCES subjects(id)
) ENGINE=InnoDB;
CREATE TABLE config_objects (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, kind VARCHAR(30) NOT NULL,
 name VARCHAR(160) NOT NULL, revision BIGINT NOT NULL DEFAULT 0 CHECK(revision BETWEEN 0 AND 9007199254740991),
 published_json JSON NULL, draft_json JSON NOT NULL, draft_base BIGINT NOT NULL DEFAULT 0,
 disabled BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE config_versions (
 tenant_id BINARY(16) NOT NULL, object_id BINARY(16) NOT NULL, revision BIGINT NOT NULL,
 content_json JSON NOT NULL, content_hash BINARY(32) NOT NULL, PRIMARY KEY(tenant_id,object_id,revision),
 FOREIGN KEY(tenant_id,object_id) REFERENCES config_objects(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE grants (
 tenant_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL, environment_id BINARY(16) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, revision BIGINT NOT NULL DEFAULT 1 CHECK(revision BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,subject_id,environment_id),
 FOREIGN KEY(tenant_id,subject_id) REFERENCES memberships(tenant_id,subject_id),
 FOREIGN KEY(tenant_id,environment_id) REFERENCES config_objects(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE commands (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL,
 kind VARBINARY(80) NOT NULL, idempotency_key VARBINARY(128) NOT NULL, request_hash BINARY(32) NOT NULL,
 response_json JSON NOT NULL, PRIMARY KEY(tenant_id,id),
 UNIQUE KEY uq_command(tenant_id,subject_id,kind,idempotency_key), FOREIGN KEY(tenant_id,subject_id) REFERENCES memberships(tenant_id,subject_id)
) ENGINE=InnoDB;
CREATE TABLE conversations (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL, environment_id BINARY(16) NOT NULL,
 title VARCHAR(160) NOT NULL, title_revision BIGINT NOT NULL DEFAULT 1 CHECK(title_revision BETWEEN 0 AND 9007199254740991),
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision BETWEEN 0 AND 9007199254740991),
 input_revision BIGINT NOT NULL DEFAULT 0 CHECK(input_revision BETWEEN 0 AND 9007199254740991),
 event_sequence BIGINT NOT NULL DEFAULT 0 CHECK(event_sequence BETWEEN 0 AND 9007199254740991),
 state VARCHAR(32) NOT NULL DEFAULT 'awaiting_user',
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,subject_id) REFERENCES memberships(tenant_id,subject_id),
 FOREIGN KEY(tenant_id,environment_id) REFERENCES config_objects(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE messages (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL,
 client_message_id VARBINARY(128) NOT NULL, text_content TEXT NOT NULL, input_revision BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uq_message(tenant_id,conversation_id,client_message_id),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE platform_events (
 tenant_id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL, sequence BIGINT NOT NULL CHECK(sequence BETWEEN 0 AND 9007199254740991),
 event_type VARCHAR(80) NOT NULL, payload JSON NOT NULL, occurred_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,conversation_id,sequence), FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE attachments (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL,
 file_name VARCHAR(180) NOT NULL, state VARCHAR(20) NOT NULL, sha256 BINARY(32) NOT NULL,
 bytes BIGINT NOT NULL CHECK(bytes BETWEEN 0 AND 20971520), source_active BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE node_runs (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL,
 state VARCHAR(32) NOT NULL, PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE execution_bindings (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, node_run_id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL,
 state VARCHAR(32) NOT NULL, spec_hash BINARY(32) NOT NULL, stopped BOOLEAN NOT NULL DEFAULT FALSE,
 accepted_at DATETIME(6) NULL, closed_at DATETIME(6) NULL, effect_status VARCHAR(20) NOT NULL DEFAULT 'none',
 open_guard TINYINT GENERATED ALWAYS AS (CASE WHEN closed_at IS NULL THEN 1 ELSE NULL END) STORED,
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uq_binding_open(tenant_id,node_run_id,open_guard),
 UNIQUE KEY uq_conversation_execution(tenant_id,conversation_id,open_guard),
 FOREIGN KEY(tenant_id,node_run_id) REFERENCES node_runs(tenant_id,id),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE approvals (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL, binding_id BINARY(16) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision BETWEEN 0 AND 9007199254740991), input_revision BIGINT NOT NULL,
 environment_revision BIGINT NOT NULL, authorization_revision BIGINT NOT NULL,
 operation_hash BINARY(32) NOT NULL, plan_json JSON NOT NULL, authority_json JSON NULL,
 state VARCHAR(30) NOT NULL DEFAULT 'pending', valid_until DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uq_approval_binding(tenant_id,binding_id),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id),
 FOREIGN KEY(tenant_id,binding_id) REFERENCES execution_bindings(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE quota_accounts (
 tenant_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL, account_key VARBINARY(128) NOT NULL, period_key VARCHAR(10) NOT NULL,
 turn_limit BIGINT NOT NULL CHECK(turn_limit BETWEEN 0 AND 9007199254740991),
 consumed BIGINT NOT NULL DEFAULT 0 CHECK(consumed BETWEEN 0 AND 9007199254740991),
 reserved BIGINT NOT NULL DEFAULT 0 CHECK(reserved BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,subject_id,account_key,period_key), FOREIGN KEY(tenant_id,subject_id) REFERENCES memberships(tenant_id,subject_id)
) ENGINE=InnoDB;
CREATE TABLE active_slots (
 tenant_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL, occupied INT NOT NULL DEFAULT 0 CHECK(occupied>=0),
 slot_limit INT NOT NULL DEFAULT 1 CHECK(slot_limit>=0), PRIMARY KEY(tenant_id,subject_id),
 FOREIGN KEY(tenant_id,subject_id) REFERENCES memberships(tenant_id,subject_id)
) ENGINE=InnoDB;
CREATE TABLE quota_reservations (
 tenant_id BINARY(16) NOT NULL, binding_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL,
 period_key VARCHAR(10) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'reserved',
 PRIMARY KEY(tenant_id,binding_id), FOREIGN KEY(tenant_id,binding_id) REFERENCES execution_bindings(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE outbox (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, binding_id BINARY(16) NOT NULL, state VARCHAR(24) NOT NULL,
 worker_id BINARY(16) NULL, lease_until DATETIME(6) NULL, attempt INT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uq_outbox_binding(tenant_id,binding_id), KEY ix_outbox(state,lease_until,id),
 FOREIGN KEY(tenant_id,binding_id) REFERENCES execution_bindings(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE admission_tickets (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, binding_id BINARY(16) NOT NULL,
 content_hash BINARY(32) NOT NULL, not_after DATETIME(6) NOT NULL, consumed_at DATETIME(6) NULL, state VARCHAR(24) NOT NULL DEFAULT 'ready',
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uq_ticket_binding(tenant_id,binding_id), FOREIGN KEY(tenant_id,binding_id) REFERENCES execution_bindings(tenant_id,id)
) ENGINE=InnoDB;
-- A durable in-process testing backend, not the D3 ACP Runner journal.
CREATE TABLE fixture_submissions (
 tenant_id BINARY(16) NOT NULL, binding_id BINARY(16) NOT NULL, spec_hash BINARY(32) NOT NULL,
 status VARCHAR(30) NOT NULL, accepted_at DATETIME(6) NULL, result_json JSON NULL, receipt_id BINARY(16) NOT NULL,
 PRIMARY KEY(tenant_id,binding_id)
) ENGINE=InnoDB;
CREATE TABLE provider_events (
 tenant_id BINARY(16) NOT NULL, binding_id BINARY(16) NOT NULL, event_id VARBINARY(128) NOT NULL, payload_hash BINARY(32) NOT NULL,
 PRIMARY KEY(tenant_id,binding_id,event_id), FOREIGN KEY(tenant_id,binding_id) REFERENCES execution_bindings(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE artifacts (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL, binding_id BINARY(16) NOT NULL,
 sha256 BINARY(32) NOT NULL, bytes BIGINT NOT NULL, state VARCHAR(20) NOT NULL,
 result_revision BIGINT NOT NULL, input_revision BIGINT NOT NULL, summary_json JSON NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uq_result_binding(tenant_id,binding_id),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE verifications (
 tenant_id BINARY(16) NOT NULL, artifact_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL,
 result_revision BIGINT NOT NULL, resolved BOOLEAN NOT NULL, opinion VARCHAR(1000) NOT NULL,
 PRIMARY KEY(tenant_id,artifact_id,result_revision),
 FOREIGN KEY(tenant_id,artifact_id) REFERENCES artifacts(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE audit_entries (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL,
 action_name VARCHAR(80) NOT NULL, object_id BINARY(16) NULL, detail_json JSON NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE content_leases (
 tenant_id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL, valid_until DATETIME(6) NOT NULL,
 purpose VARCHAR(1000) NOT NULL, PRIMARY KEY(tenant_id,conversation_id,subject_id),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB;
