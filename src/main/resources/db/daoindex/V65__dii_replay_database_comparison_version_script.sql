CREATE TABLE IF NOT EXISTS dii_replay_db_compare_version_script (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    file_name VARCHAR(256) NOT NULL,
    compression VARCHAR(16) NOT NULL,
    script_content LONGBLOB NOT NULL,
    script_sha256 CHAR(64) NOT NULL,
    script_size BIGINT NOT NULL,
    compressed_size BIGINT NOT NULL,
    generated_by VARCHAR(64) NOT NULL,
    generated_name VARCHAR(128) NOT NULL,
    generated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_replay_db_compare_version_script_version UNIQUE (version_id)
);
