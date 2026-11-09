-- Existing occurrence rows deliberately stay unknown; current issue values cannot recreate past states.
ALTER TABLE dii_replay_issue_occurrence_batch
    ADD COLUMN statistics_snapshot JSON NULL COMMENT '该出现批次的问题统计完整快照';

ALTER TABLE dii_replay_issue_occurrence_batch
    ADD COLUMN statistics_recorded_at DATETIME NULL COMMENT '该批次统计快照最后记录时间';

CREATE TABLE IF NOT EXISTS dii_replay_issue_statistics_batch (
    batch_no VARCHAR(128) NOT NULL PRIMARY KEY,
    family VARCHAR(8) NOT NULL COMMENT 'RPT或DZ',
    status VARCHAR(8) NOT NULL COMMENT 'OPEN或FROZEN',
    active_family VARCHAR(8) NULL COMMENT 'OPEN时为family，FROZEN时为空',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uq_replay_statistics_active_family (active_family),
    INDEX idx_replay_statistics_batch_family (family, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放批次历史统计快照状态';
