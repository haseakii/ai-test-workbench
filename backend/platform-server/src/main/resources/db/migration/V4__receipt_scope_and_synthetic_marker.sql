ALTER TABLE commands ADD access_kind VARCHAR(20) NULL, ADD access_id BINARY(16) NULL;
-- Old receipts have no trustworthy authorization descriptor. Direct lookups fail closed;
-- a replay may attach a descriptor only after request-hash equality and current authorization.
CREATE TABLE synthetic_boundary (
 id INT PRIMARY KEY CHECK(id=1), mode_name VARCHAR(40) NOT NULL CHECK(mode_name='isolated_synthetic_only')
);
INSERT INTO synthetic_boundary VALUES(1,'isolated_synthetic_only');
UPDATE artifacts a JOIN conversations c ON c.tenant_id=a.tenant_id AND c.id=a.conversation_id
SET a.state='superseded' WHERE a.state='ready' AND a.input_revision<>c.input_revision;
