CREATE TABLE IF NOT EXISTS dii_replay_db_compare_registration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schema_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(256) NOT NULL,
    table_comment VARCHAR(1000),
    domain_name VARCHAR(128) NOT NULL,
    owner_emp_no VARCHAR(64) NOT NULL,
    owner_name VARCHAR(128) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    registered_date DATE NOT NULL,
    remark VARCHAR(2000),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    deleted_reason VARCHAR(1000),
    deleted_by VARCHAR(64),
    deleted_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    created_name VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    updated_name VARCHAR(128) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_replay_db_compare_schema_table UNIQUE (schema_name, table_name),
    INDEX idx_replay_db_compare_active_domain (deleted, domain_name),
    INDEX idx_replay_db_compare_active_group (deleted, group_name),
    INDEX idx_replay_db_compare_owner (deleted, owner_emp_no),
    INDEX idx_replay_db_compare_date (deleted, registered_date)
);

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_field (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    registration_id BIGINT NOT NULL,
    column_name VARCHAR(256) NOT NULL,
    column_comment VARCHAR(1000),
    ordinal_position INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_replay_db_compare_field UNIQUE (registration_id, column_name),
    INDEX idx_replay_db_compare_column (column_name)
);

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    registration_id BIGINT NOT NULL,
    operation VARCHAR(32) NOT NULL,
    before_snapshot LONGTEXT,
    after_snapshot LONGTEXT,
    reason VARCHAR(1000),
    operator_emp_no VARCHAR(64) NOT NULL,
    operator_name VARCHAR(128) NOT NULL,
    operated_at DATETIME(3) NOT NULL,
    INDEX idx_replay_db_compare_history_registration (registration_id, operated_at, id)
);
