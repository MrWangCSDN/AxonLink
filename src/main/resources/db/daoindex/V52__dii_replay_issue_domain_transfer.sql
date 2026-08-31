ALTER TABLE dii_replay_issue
    ADD COLUMN issue_domain VARCHAR(32) DEFAULT NULL COMMENT '问题当前所属领域' AFTER group_name;

UPDATE dii_replay_issue
   SET issue_domain = group_name
 WHERE issue_domain IS NULL OR TRIM(issue_domain) = '';

CREATE TABLE IF NOT EXISTS dii_replay_issue_domain_transfer (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    replay_issue_id       BIGINT NOT NULL,
    issue_key             VARCHAR(1024) NOT NULL,
    from_domain           VARCHAR(32) NOT NULL,
    to_domain             VARCHAR(32) NOT NULL,
    operator_username     VARCHAR(128),
    operator_real_name    VARCHAR(128),
    transferred_at        DATETIME NOT NULL,
    INDEX idx_replay_domain_transfer_issue_time (replay_issue_id, transferred_at, id),
    INDEX idx_replay_domain_transfer_key_time (issue_key(191), transferred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='并行回放问题转组审计';
