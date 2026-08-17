ALTER TABLE dii_replay_issue
    ADD COLUMN coverage_round VARCHAR(64) DEFAULT NULL COMMENT '最近一次 Excel 导入覆盖轮次' AFTER imported_at,
    ADD INDEX idx_replay_issue_coverage_round (coverage_round);

ALTER TABLE dii_replay_issue_history
    ADD COLUMN coverage_round VARCHAR(64) DEFAULT NULL COMMENT 'Excel 导入覆盖轮次' AFTER import_date,
    ADD INDEX idx_replay_history_coverage_round (coverage_round, operation_at, id);
