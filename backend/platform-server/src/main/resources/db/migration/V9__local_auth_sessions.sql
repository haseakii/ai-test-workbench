-- Opaque session secrets are never stored, only their SHA-256 digests.
CREATE TABLE local_auth_sessions (
 token_hash BINARY(32) NOT NULL PRIMARY KEY,
 tenant_id BINARY(16) NOT NULL,
 subject_id BINARY(16) NOT NULL,
 authorization_revision BIGINT NOT NULL CHECK(authorization_revision BETWEEN 0 AND 9007199254740991),
 expires_at DATETIME(6) NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY (tenant_id, subject_id) REFERENCES memberships(tenant_id, subject_id),
 INDEX idx_local_session_subject (tenant_id, subject_id)
) ENGINE=InnoDB;
