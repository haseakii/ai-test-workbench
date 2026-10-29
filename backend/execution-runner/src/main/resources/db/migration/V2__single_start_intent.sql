-- A durable intent is never reset for another launch, even after a crash or lease expiry.
-- Existing ACCEPTED rows may predate launch tracking: fence them conservatively.
ALTER TABLE submission_journal ADD COLUMN start_intent BINARY(32) NULL;
UPDATE submission_journal SET start_intent=UNHEX(SHA2(CONCAT(HEX(tenant_id),HEX(binding_id)),256)) WHERE state='ACCEPTED';
