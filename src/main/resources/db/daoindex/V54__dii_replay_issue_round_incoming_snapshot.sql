ALTER TABLE dii_replay_issue_round
    ADD COLUMN incoming_snapshot MEDIUMTEXT DEFAULT NULL COMMENT '本批次导入的原始问题数据' AFTER recorded_at,
    ADD COLUMN batch_name VARCHAR(128) DEFAULT NULL COMMENT 'Excel 业务批次号' AFTER incoming_snapshot;
