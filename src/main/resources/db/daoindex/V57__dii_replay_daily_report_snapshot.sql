CREATE TABLE IF NOT EXISTS dii_replay_daily_report_snapshot (
    batch_no VARCHAR(128) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_content LONGBLOB NOT NULL,
    file_size BIGINT NOT NULL,
    generated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放日报当前有效Excel快照';
