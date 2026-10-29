-- REVIEW ONLY: empty database candidate; not wired, verified, or authorized for deployment.
-- No row, token or deployment is created by this schema candidate.
-- A separate trusted local provisioner and eligibility verification do not yet exist.
-- The consumed guard never changes production_boundary or permits service startup.
CREATE TABLE production_bootstrap_guard (
 id INT NOT NULL PRIMARY KEY CHECK (id = 1),
 deployment_id BINARY(16) NOT NULL,
 token_hash BINARY(32) NULL,
 expires_at DATETIME(6) NOT NULL,
 state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BINARY(16) NULL,
 CONSTRAINT ck_production_bootstrap_state CHECK (
   (state = 'provisioned' AND token_hash IS NOT NULL AND tenant_id IS NULL)
   OR (state = 'consumed' AND token_hash IS NULL AND tenant_id IS NOT NULL
       AND tenant_id = UNHEX('00000000000040008000000000000001'))
 )
) ENGINE=InnoDB;
