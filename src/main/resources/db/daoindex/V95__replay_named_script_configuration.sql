CREATE TABLE IF NOT EXISTS dii_replay_db_compare_script_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    name_key VARCHAR(100) NOT NULL,
    revision BIGINT NOT NULL,
    config_json LONGTEXT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    script_content_gzip LONGBLOB NOT NULL,
    script_sha256 CHAR(64) NOT NULL,
    script_size BIGINT NOT NULL,
    table_count INT NOT NULL,
    field_count INT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uq_replay_script_config_name (name_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Independent named table comparison SQL configurations';
