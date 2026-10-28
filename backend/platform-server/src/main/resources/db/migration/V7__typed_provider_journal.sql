-- Persistent local synthetic Provider facts. Existing historical rows are not replayed/backfilled.
ALTER TABLE execution_bindings ADD quota_snapshot_json JSON NULL, ADD capability_snapshot_json JSON NULL;
ALTER TABLE fixture_submissions
 ADD executor_scope BINARY(32) NULL,
 ADD snapshot_revision BIGINT NOT NULL DEFAULT 0 CHECK(snapshot_revision BETWEEN 0 AND 9007199254740991),
 ADD event_sequence BIGINT NOT NULL DEFAULT 0 CHECK(event_sequence BETWEEN 0 AND 9007199254740991),
 ADD execution_attempts BIGINT NOT NULL DEFAULT 0 CHECK(execution_attempts BETWEEN 0 AND 9007199254740991),
 ADD execution_phase VARCHAR(20) NOT NULL DEFAULT 'accepted' CHECK(execution_phase IN ('accepted','computing','writing','terminal')),
 ADD owner_pid BIGINT NULL,
 ADD owner_started_at VARCHAR(40) NULL,
 ADD owner_boot_hash BINARY(32) NULL,
 ADD cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 ADD execution_fence BIGINT NOT NULL DEFAULT 0 CHECK(execution_fence BETWEEN 0 AND 9007199254740991),
 ADD closed_at DATETIME(6) NULL;
CREATE TABLE fixture_events (
 tenant_id BINARY(16) NOT NULL,binding_id BINARY(16) NOT NULL,sequence BIGINT NOT NULL CHECK(sequence BETWEEN 1 AND 9007199254740991),
 event_id BINARY(16) NOT NULL,event_json JSON NOT NULL,
 PRIMARY KEY(tenant_id,binding_id,sequence),UNIQUE KEY uq_fixture_event(tenant_id,event_id),
 FOREIGN KEY(tenant_id,binding_id) REFERENCES fixture_submissions(tenant_id,binding_id)
) ENGINE=InnoDB;
CREATE TABLE fixture_artifacts (
 tenant_id BINARY(16) NOT NULL,binding_id BINARY(16) NOT NULL,artifact_id BINARY(16) NOT NULL,
 descriptor_json JSON NOT NULL,
 PRIMARY KEY(tenant_id,binding_id,artifact_id),
 FOREIGN KEY(tenant_id,binding_id) REFERENCES fixture_submissions(tenant_id,binding_id)
) ENGINE=InnoDB;
CREATE TABLE fixture_commands (
 tenant_id BINARY(16) NOT NULL,binding_id BINARY(16) NOT NULL,method VARBINARY(32) NOT NULL,idempotency_key VARBINARY(128) NOT NULL,
 command_id BINARY(16) NOT NULL,request_hash BINARY(32) NOT NULL,response_json JSON NOT NULL,
 PRIMARY KEY(tenant_id,binding_id,method,idempotency_key),
 FOREIGN KEY(tenant_id,binding_id) REFERENCES execution_bindings(tenant_id,id)
) ENGINE=InnoDB;
