-- New reservations always persist the exact account pair and UTC period.
-- NULL pairs retain legacy records whose original debit scope cannot be reconstructed.
-- No balance, state, period or active slot is changed by this migration.
ALTER TABLE quota_reservations
 ADD COLUMN total_account_key VARBINARY(128) NULL,
 ADD COLUMN model_account_key VARBINARY(128) NULL;

-- The only legacy model was fixture-v1. Backfill only when the ledger contains
-- exactly that pair; a third model makes the old batch-update scope ambiguous.
UPDATE quota_reservations r
 JOIN approvals p ON p.tenant_id=r.tenant_id AND p.binding_id=r.binding_id
 JOIN quota_accounts t ON t.tenant_id=r.tenant_id AND t.subject_id=r.subject_id
  AND t.period_key=r.period_key AND t.account_key=_binary'total'
 JOIN quota_accounts m ON m.tenant_id=r.tenant_id AND m.subject_id=r.subject_id
  AND m.period_key=r.period_key AND m.account_key=_binary'model:fixture-v1'
 SET r.total_account_key=_binary'total',r.model_account_key=_binary'model:fixture-v1'
 WHERE JSON_UNQUOTE(JSON_EXTRACT(p.plan_json,'$.model'))='fixture-v1'
 AND (SELECT COUNT(*) FROM quota_accounts a WHERE a.tenant_id=r.tenant_id
      AND a.subject_id=r.subject_id AND a.period_key=r.period_key)=2;

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
