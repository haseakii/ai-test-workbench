-- Account creation and login routes remain disabled. No default account is created.
CREATE TABLE local_auth_accounts (
 tenant_id BINARY(16) NOT NULL,
 subject_id BINARY(16) NOT NULL,
 login_name VARBINARY(64) NOT NULL,
 password_hash VARBINARY(191) NOT NULL,
 password_revision BIGINT NOT NULL CHECK(password_revision BETWEEN 0 AND 9007199254740991),
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY (tenant_id, login_name),
 UNIQUE KEY uq_local_account_subject (tenant_id, subject_id),
 FOREIGN KEY (tenant_id, subject_id) REFERENCES memberships(tenant_id, subject_id)
) ENGINE=InnoDB;

-- Fixed-size hash buckets bound storage to 8192 rows per tenant; collisions throttle conservatively.
CREATE TABLE local_login_buckets (
 tenant_id BINARY(16) NOT NULL,
 bucket_kind TINYINT NOT NULL CHECK(bucket_kind IN (0,1)),
 slot SMALLINT UNSIGNED NOT NULL CHECK(slot < 4096),
 attempts INT UNSIGNED NOT NULL,
 window_until DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id, bucket_kind, slot),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
