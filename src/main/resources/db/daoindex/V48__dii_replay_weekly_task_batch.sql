CREATE TABLE IF NOT EXISTS dii_replay_weekly_task_batch (
    batch_name VARCHAR(128) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放问题清单当前本周任务批次配置';
