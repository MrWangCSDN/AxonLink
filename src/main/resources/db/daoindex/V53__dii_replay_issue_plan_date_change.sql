CREATE TABLE IF NOT EXISTS dii_replay_issue_plan_date_change (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    replay_issue_id          BIGINT NOT NULL,
    issue_key                VARCHAR(1024) NOT NULL,
    planned_completion_date  DATE DEFAULT NULL,
    operator_username        VARCHAR(128),
    operator_real_name       VARCHAR(128),
    changed_at               DATETIME NOT NULL,
    INDEX idx_replay_plan_date_change_issue_time (replay_issue_id, changed_at, id),
    INDEX idx_replay_plan_date_change_key_time (issue_key(191), changed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='并行回放问题计划验证日期修改历史';
