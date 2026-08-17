CREATE TABLE IF NOT EXISTS dii_replay_issue_mail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    replay_issue_id BIGINT NOT NULL,
    issue_key VARCHAR(1024) NOT NULL,
    recipient_username VARCHAR(128),
    recipient_email VARCHAR(320) NOT NULL,
    sender_email VARCHAR(320) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sent_at DATETIME DEFAULT NULL,
    failure_message VARCHAR(1000) DEFAULT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_replay_issue_mail_dedupe (replay_issue_id, recipient_email, sender_email, content_hash),
    INDEX idx_replay_issue_mail_issue (replay_issue_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放问题协同邮件发送记录';
