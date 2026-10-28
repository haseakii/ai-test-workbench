-- D0 migration contract only. No Agent process or ACP capability is implemented.
CREATE TABLE submission_journal (
 tenant_id BINARY(16) NOT NULL, binding_id BINARY(16) NOT NULL,
 idempotency_key VARBINARY(128) NOT NULL, spec_hash BINARY(32) NOT NULL,
 state VARCHAR(32) NOT NULL, epoch BIGINT NOT NULL CHECK(epoch BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,binding_id), UNIQUE KEY uq_submission(tenant_id,idempotency_key)
) ENGINE=InnoDB;
