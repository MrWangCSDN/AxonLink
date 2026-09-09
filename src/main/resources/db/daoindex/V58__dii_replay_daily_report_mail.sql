CREATE TABLE IF NOT EXISTS dii_replay_daily_report_mail (
    batch_no VARCHAR(128) PRIMARY KEY,
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
    CONSTRAINT fk_replay_daily_report_mail_snapshot
        FOREIGN KEY (batch_no) REFERENCES dii_replay_daily_report_snapshot(batch_no) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放日报邮件最后发送状态';
