-- Records local intent only. It is not evidence of admission, process start or final closure.
-- V1/V2 rows lack provider/node/type and cannot be safely mapped automatically.
-- Do not use this table for execution until old owners are reconciled and the legacy writer is fenced.
CREATE TABLE runner_command_intents (
 tenant_id BINARY(16) NOT NULL,
 provider_digest BINARY(32) NOT NULL,
 provider_id LONGBLOB NOT NULL,
 node_run_id BINARY(16) NOT NULL,
 command_digest BINARY(32) NOT NULL,
 command_type LONGBLOB NOT NULL,
 key_digest BINARY(32) NOT NULL,
 idempotency_key LONGBLOB NOT NULL,
 binding_id BINARY(16) NOT NULL,
 spec_hash BINARY(32) NOT NULL,
 request_hash BINARY(32) NOT NULL,
 ownership_epoch BIGINT NOT NULL CHECK (ownership_epoch BETWEEN 0 AND 9007199254740991),
 intent_id BINARY(16) NOT NULL,
 recorded_at DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id, provider_digest, node_run_id, command_digest, key_digest),
 UNIQUE KEY uq_runner_command_intent_id (tenant_id, intent_id)
) ENGINE=InnoDB;
