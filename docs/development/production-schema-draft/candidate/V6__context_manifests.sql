-- REVIEW ONLY: empty database candidate; not wired, verified, or authorized for deployment.
-- Original plans and records remain untouched; no context is invented for legacy rows.
CREATE TABLE context_manifests (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL,
 subject_id BINARY(16) NOT NULL, purpose VARCHAR(20) NOT NULL CHECK(purpose IN ('understanding','investigation')),
 input_revision BIGINT NOT NULL CHECK(input_revision BETWEEN 0 AND 9007199254740991),
 authorization_revision BIGINT NOT NULL CHECK(authorization_revision BETWEEN 0 AND 9007199254740991),
 manifest_hash BINARY(32) NOT NULL, manifest_json JSON NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'active' CHECK(state IN ('active','invalidated')),
 invalidation_reason VARCHAR(40) NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uq_context_content(tenant_id,conversation_id,purpose,manifest_hash),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id),
 FOREIGN KEY(tenant_id,subject_id) REFERENCES memberships(tenant_id,subject_id)
) ENGINE=InnoDB;
CREATE TABLE understanding_runs (
 tenant_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL, conversation_id BINARY(16) NOT NULL,
 context_manifest_id BINARY(16) NOT NULL, input_revision BIGINT NOT NULL,
 purpose VARCHAR(20) NOT NULL CHECK(purpose='understanding'),
 tools_json JSON NOT NULL CHECK(JSON_TYPE(tools_json)='ARRAY' AND JSON_LENGTH(tools_json)=0), model_calls INT NOT NULL DEFAULT 0 CHECK(model_calls=0),
 state VARCHAR(20) NOT NULL CHECK(state IN ('ready','needs_input','stale')),
 output_json JSON NOT NULL, output_hash BINARY(32) NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,id), KEY ix_understanding_input(tenant_id,conversation_id,input_revision),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id),
 FOREIGN KEY(tenant_id,context_manifest_id) REFERENCES context_manifests(tenant_id,id)
) ENGINE=InnoDB;
ALTER TABLE approvals ADD context_manifest_id BINARY(16) NULL,
 ADD CONSTRAINT fk_approval_context FOREIGN KEY(tenant_id,context_manifest_id) REFERENCES context_manifests(tenant_id,id);
ALTER TABLE execution_bindings ADD execution_spec_json JSON NULL;
