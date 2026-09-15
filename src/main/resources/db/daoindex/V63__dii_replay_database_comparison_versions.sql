CREATE TABLE IF NOT EXISTS dii_replay_db_compare_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_no VARCHAR(32) NOT NULL,
    configuration_hash CHAR(64) NOT NULL,
    table_count INT NOT NULL,
    field_count INT NOT NULL,
    generated_by VARCHAR(64) NOT NULL,
    generated_name VARCHAR(128) NOT NULL,
    generated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_replay_db_compare_version_no UNIQUE (version_no),
    INDEX idx_replay_db_compare_version_time (generated_at, id)
);

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_version_table (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    source_registration_id BIGINT NOT NULL,
    source_registration_version BIGINT NOT NULL,
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
    CONSTRAINT uk_replay_db_compare_version_table UNIQUE (version_id, schema_name, table_name),
    INDEX idx_replay_db_compare_version_domain (version_id, domain_name),
    INDEX idx_replay_db_compare_version_reviser (version_id, reviser_emp_no),
    INDEX idx_replay_db_compare_version_owner (version_id, group_owner_emp_no)
);

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_version_field (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_table_id BIGINT NOT NULL,
    column_name VARCHAR(256) NOT NULL,
    column_comment VARCHAR(1000),
    ordinal_position INT NOT NULL,
    primary_key TINYINT(1) NOT NULL,
    comparison_order INT NOT NULL,
    CONSTRAINT uk_replay_db_compare_version_field UNIQUE (version_table_id, column_name),
    CONSTRAINT uk_replay_db_compare_version_field_order UNIQUE (version_table_id, comparison_order),
    INDEX idx_replay_db_compare_version_column (version_table_id, column_name)
);

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_generation_lock (
    id TINYINT PRIMARY KEY,
    locked TINYINT(1) NOT NULL DEFAULT 0,
    lock_token VARCHAR(64),
    locked_by VARCHAR(64),
    locked_name VARCHAR(128),
    locked_at DATETIME(3),
    lock_expires_at DATETIME(3)
);

INSERT INTO dii_replay_db_compare_generation_lock
    (id, locked, lock_token, locked_by, locked_name, locked_at, lock_expires_at)
VALUES (1, 0, NULL, NULL, NULL, NULL, NULL)
ON DUPLICATE KEY UPDATE id = VALUES(id);
