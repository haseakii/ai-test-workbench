-- REVIEW ONLY: empty database candidate; not wired, verified, or authorized for deployment.
ALTER TABLE conversations ADD COLUMN title_source VARCHAR(20) NOT NULL DEFAULT 'placeholder';
ALTER TABLE admission_tickets ADD COLUMN not_before DATETIME(6) NULL;
CREATE TABLE quota_policies (
 tenant_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL,
 turn_limit BIGINT NOT NULL DEFAULT 100 CHECK(turn_limit BETWEEN 0 AND 9007199254740991),
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,subject_id), FOREIGN KEY(tenant_id,subject_id) REFERENCES memberships(tenant_id,subject_id)
) ENGINE=InnoDB;
