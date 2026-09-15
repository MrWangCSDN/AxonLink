CREATE TABLE IF NOT EXISTS dii_replay_db_compare_registration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schema_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(256) NOT NULL,
    table_comment VARCHAR(1000),
    domain_name VARCHAR(128) NOT NULL,
    reviser_emp_no VARCHAR(64),
    reviser_username VARCHAR(128),
    reviser_name VARCHAR(128),
    group_owner_emp_no VARCHAR(64),
    group_owner_name VARCHAR(128),
    registered_date DATE NOT NULL,
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
    INDEX idx_replay_db_compare_group_owner (deleted, group_owner_emp_no),
    INDEX idx_replay_db_compare_reviser (deleted, reviser_emp_no),
    INDEX idx_replay_db_compare_date (deleted, registered_date)
);

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_field (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    registration_id BIGINT NOT NULL,
    column_name VARCHAR(256) NOT NULL,
    column_comment VARCHAR(1000),
    ordinal_position INT NOT NULL,
    primary_key TINYINT(1) NOT NULL DEFAULT 0,
    comparison_order INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_replay_db_compare_field UNIQUE (registration_id, column_name),
    CONSTRAINT uk_replay_db_compare_field_order UNIQUE (registration_id, comparison_order),
    INDEX idx_replay_db_compare_column (column_name)
);

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_audit_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    registration_id BIGINT NOT NULL,
    schema_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(256) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    registration_version BIGINT NOT NULL,
    change_count INT NOT NULL,
    reason VARCHAR(1000),
    operator_emp_no VARCHAR(64) NOT NULL,
    operator_name VARCHAR(128) NOT NULL,
    operated_at DATETIME(3) NOT NULL,
    INDEX idx_replay_db_compare_audit_registration (registration_id, operated_at, id),
    INDEX idx_replay_db_compare_audit_business (schema_name, table_name, operated_at, id),
    INDEX idx_replay_db_compare_audit_operator (operator_emp_no, operated_at)
);

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_audit_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    audit_event_id BIGINT NOT NULL,
    detail_order INT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    field_code VARCHAR(512) NOT NULL,
    field_label VARCHAR(256) NOT NULL,
    before_value LONGTEXT,
    after_value LONGTEXT,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_replay_db_compare_audit_detail_order UNIQUE (audit_event_id, detail_order),
    INDEX idx_replay_db_compare_audit_detail_field (field_code)
);
