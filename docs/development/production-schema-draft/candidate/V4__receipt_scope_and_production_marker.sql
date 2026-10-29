-- REVIEW ONLY: empty database candidate; not wired, verified, or authorized for deployment.
ALTER TABLE commands ADD access_kind VARCHAR(20) NULL, ADD access_id BINARY(16) NULL;
-- Old receipts have no trustworthy authorization descriptor. Direct lookups fail closed;
-- a replay may attach a descriptor only after request-hash equality and current authorization.
-- Inert sentinel only. No deployment UUID or tenant is allocated by this draft.
-- A future audited bootstrap migration must replace these constraints before initialization.
CREATE TABLE production_boundary (
 id INT NOT NULL PRIMARY KEY CHECK (id = 1),
 mode_name VARCHAR(40) NOT NULL CHECK (mode_name = 'production_uninitialized'),
 deployment_id BINARY(16) NULL CHECK (deployment_id IS NULL),
 tenant_id BINARY(16) NULL CHECK (tenant_id IS NULL)
) ENGINE=InnoDB;
INSERT INTO production_boundary (id, mode_name, deployment_id, tenant_id)
 VALUES (1, 'production_uninitialized', NULL, NULL);
