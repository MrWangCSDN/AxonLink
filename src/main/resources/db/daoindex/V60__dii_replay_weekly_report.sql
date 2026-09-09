CREATE TABLE IF NOT EXISTS dii_replay_weekly_report_snapshot (
    start_batch_no VARCHAR(128) NOT NULL,
    end_batch_no VARCHAR(128) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_content LONGBLOB NOT NULL,
    file_size BIGINT NOT NULL,
    generated_at DATETIME NOT NULL,
    PRIMARY KEY (start_batch_no, end_batch_no),
    INDEX idx_replay_weekly_report_end (end_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放周报永久Excel快照';

CREATE TABLE IF NOT EXISTS dii_replay_weekly_report_mail (
    start_batch_no VARCHAR(128) NOT NULL,
    end_batch_no VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    sender_email VARCHAR(320) NOT NULL,
    to_emails TEXT NOT NULL,
    cc_emails TEXT NULL,
    sent_at DATETIME NULL,
    failure_message VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (start_batch_no, end_batch_no),
    CONSTRAINT fk_replay_weekly_report_mail_snapshot
        FOREIGN KEY (start_batch_no, end_batch_no)
        REFERENCES dii_replay_weekly_report_snapshot(start_batch_no, end_batch_no)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放周报邮件最后发送状态';
