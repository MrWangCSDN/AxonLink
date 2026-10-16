CREATE TABLE IF NOT EXISTS dii_replay_database_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_id VARCHAR(600) NOT NULL,
    table_name VARCHAR(256) NOT NULL,
    table_comment VARCHAR(1000),
    domain_name VARCHAR(128) NOT NULL,
    owner_display VARCHAR(256),
    issue_level VARCHAR(32) NOT NULL,
    field_name VARCHAR(256),
    difference_count BIGINT NOT NULL,
    batch_no VARCHAR(128) NOT NULL,
    batch_date DATE NOT NULL,
    issue_status VARCHAR(32) NOT NULL,
    issue_type VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    data_snapshot LONGTEXT NOT NULL,
    UNIQUE KEY uk_replay_database_issue (issue_id),
    INDEX idx_replay_database_issue_domain_status (domain_name,issue_status),
    INDEX idx_replay_database_issue_table (table_name),
    INDEX idx_replay_database_issue_batch (batch_no)
);
CREATE TABLE IF NOT EXISTS dii_replay_database_issue_batch (
    batch_no VARCHAR(128) PRIMARY KEY,
    batch_date DATE NOT NULL,
    filename VARCHAR(512),
    imported_at DATETIME(3) NOT NULL,
    operator_username VARCHAR(128),
    operator_name VARCHAR(128),
    input_rows INT NOT NULL,
    created_rows INT NOT NULL,
    updated_rows INT NOT NULL,
    auto_repaired_rows INT NOT NULL
);
CREATE TABLE IF NOT EXISTS dii_replay_database_issue_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    batch_no VARCHAR(128) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    operation_at DATETIME(3) NOT NULL,
    operator_username VARCHAR(128),
    operator_name VARCHAR(128),
    import_event TINYINT(1) NOT NULL DEFAULT 0,
    before_snapshot LONGTEXT,
    after_snapshot LONGTEXT NOT NULL,
    incoming_snapshot LONGTEXT,
    INDEX idx_replay_database_issue_history (issue_id,batch_no,operation_at,id)
);
CREATE TABLE IF NOT EXISTS dii_replay_database_issue_write_lock (id INT PRIMARY KEY);
INSERT INTO dii_replay_database_issue_write_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM dii_replay_database_issue_write_lock WHERE id=1);
CREATE TABLE IF NOT EXISTS dii_replay_database_issue_table_state (
    table_name VARCHAR(256) PRIMARY KEY,
    batch_date DATE NOT NULL,
    batch_no VARCHAR(128) NOT NULL
);
