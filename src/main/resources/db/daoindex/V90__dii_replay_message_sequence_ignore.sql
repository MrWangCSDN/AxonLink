CREATE TABLE IF NOT EXISTS dii_replay_message_sequence_ignore (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_code VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    category VARCHAR(16) NOT NULL,
    message_sequence VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    review_status TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_replay_message_sequence_category (category, message_sequence),
    KEY idx_replay_message_sequence_review (category, review_status, created_at, id),
    KEY idx_replay_message_sequence_service (service_code)
);

CREATE TABLE IF NOT EXISTS dii_replay_message_sequence_ignore_operation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_id BIGINT NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    service_code VARCHAR(192) NULL,
    category VARCHAR(16) NULL,
    message_sequence VARCHAR(512) NULL,
    ignore_reason VARCHAR(512) NULL,
    review_status TINYINT NULL,
    new_service_code VARCHAR(192) NULL,
    new_category VARCHAR(16) NULL,
    new_message_sequence VARCHAR(512) NULL,
    new_ignore_reason VARCHAR(512) NULL,
    new_review_status TINYINT NULL,
    operator_username VARCHAR(128) NULL,
    operator_real_name VARCHAR(128) NULL,
    operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_replay_message_sequence_operation (config_id, created_at, id)
);
