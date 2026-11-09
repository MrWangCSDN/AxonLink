-- 数据库问题计划验证日期；与回放问题共用维护权限和日期规则。
ALTER TABLE dii_replay_database_issue ADD COLUMN planned_completion_date DATE NULL;

CREATE TABLE IF NOT EXISTS dii_replay_database_issue_plan_date_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    replay_issue_id BIGINT NOT NULL,
    planned_completion_date DATE NULL,
    operator_username VARCHAR(128),
    operator_real_name VARCHAR(128),
    changed_at DATETIME(3) NOT NULL,
    INDEX idx_database_issue_plan_date_change (replay_issue_id, id)
);
