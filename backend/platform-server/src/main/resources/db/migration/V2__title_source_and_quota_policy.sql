ALTER TABLE conversations ADD COLUMN title_source VARCHAR(20) NOT NULL DEFAULT 'placeholder';
ALTER TABLE admission_tickets ADD COLUMN not_before DATETIME(6) NULL;
UPDATE conversations SET title_source='automatic' WHERE title<>'新问题';
UPDATE conversations c SET title_source='manual'
 WHERE EXISTS(SELECT 1 FROM audit_entries a WHERE a.tenant_id=c.tenant_id AND a.object_id=c.id AND a.action_name='conversation.title');
CREATE TABLE quota_policies (
 tenant_id BINARY(16) NOT NULL, subject_id BINARY(16) NOT NULL,
 turn_limit BIGINT NOT NULL DEFAULT 100 CHECK(turn_limit BETWEEN 0 AND 9007199254740991),
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,subject_id), FOREIGN KEY(tenant_id,subject_id) REFERENCES memberships(tenant_id,subject_id)
) ENGINE=InnoDB;
INSERT INTO quota_policies(tenant_id,subject_id) SELECT tenant_id,subject_id FROM memberships;
