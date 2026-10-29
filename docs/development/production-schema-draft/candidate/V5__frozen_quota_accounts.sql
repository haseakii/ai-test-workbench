-- REVIEW ONLY: empty database candidate; not wired, verified, or authorized for deployment.
-- New reservations always persist the exact account pair and UTC period.
-- NULL pairs retain legacy records whose original debit scope cannot be reconstructed.
-- No balance, state, period or active slot is changed by this migration.
ALTER TABLE quota_reservations
 ADD COLUMN total_account_key VARBINARY(128) NULL,
 ADD COLUMN model_account_key VARBINARY(128) NULL;

-- The only legacy model was fixture-v1. Backfill only when the ledger contains
-- exactly that pair; a third model makes the old batch-update scope ambiguous.

ALTER TABLE quota_reservations
 ADD CONSTRAINT ck_reservation_account_pair CHECK(
   (total_account_key IS NULL AND model_account_key IS NULL)
   OR (total_account_key IS NOT NULL AND total_account_key=_binary'total'
       AND model_account_key IS NOT NULL AND LEFT(model_account_key,6)=_binary'model:'
       AND OCTET_LENGTH(model_account_key)>6)),
 ADD CONSTRAINT fk_reservation_total_account FOREIGN KEY
   (tenant_id,subject_id,total_account_key,period_key)
   REFERENCES quota_accounts(tenant_id,subject_id,account_key,period_key),
 ADD CONSTRAINT fk_reservation_model_account FOREIGN KEY
   (tenant_id,subject_id,model_account_key,period_key)
   REFERENCES quota_accounts(tenant_id,subject_id,account_key,period_key);
