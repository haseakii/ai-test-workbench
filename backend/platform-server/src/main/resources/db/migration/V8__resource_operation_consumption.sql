-- One immutable consumption is also its audit fact; no resource side effect is performed here.
CREATE TABLE resource_operation_consumptions (
 tenant_id VARBINARY(128) NOT NULL,
 operation_id VARBINARY(128) NOT NULL,
 operation_hash BINARY(32) NOT NULL,
 principal_id VARBINARY(128) NOT NULL,
 binding_id VARBINARY(128) NOT NULL,
 environment_id VARBINARY(128) NOT NULL,
 resource_id VARBINARY(128) NOT NULL,
 tool_id VARBINARY(128) NOT NULL,
 parameters_hash BINARY(32) NOT NULL,
 ownership_epoch BIGINT NOT NULL CHECK (ownership_epoch BETWEEN 0 AND 9007199254740991),
 authorization_revision BIGINT NOT NULL CHECK (authorization_revision BETWEEN 0 AND 9007199254740991),
 consumed_at DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id, operation_id)
) ENGINE=InnoDB;
