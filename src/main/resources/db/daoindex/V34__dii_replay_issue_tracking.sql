-- Current projection additions. Legacy Excel columns remain nullable for source compatibility.
ALTER TABLE dii_replay_issue
    ADD COLUMN issue_status VARCHAR(32) NOT NULL DEFAULT '打开' COMMENT '问题状态',
    ADD COLUMN import_date DATE DEFAULT NULL COMMENT '当前记录导入日期',
    ADD COLUMN defect_repair_date DATE DEFAULT NULL COMMENT '缺陷修复日期',
    ADD COLUMN cooperation_person_username VARCHAR(128) DEFAULT NULL COMMENT '需协同人账号',
    ADD COLUMN cooperation_person_real_name VARCHAR(128) DEFAULT NULL COMMENT '需协同人姓名快照',
    ADD INDEX idx_replay_issue_key_lookup (issue_key(191));

UPDATE dii_replay_issue
   SET import_date = DATE(imported_at)
 WHERE import_date IS NULL;

CREATE TABLE IF NOT EXISTS dii_replay_issue_history (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    replay_issue_id       BIGINT,
    issue_key             VARCHAR(1024) NOT NULL,
    operation_type        VARCHAR(64) NOT NULL,
    operation_at          DATETIME NOT NULL,
    operator_username     VARCHAR(128),
    operator_real_name    VARCHAR(128),
    import_date           DATE,
    source_sheet          VARCHAR(64),
    source_row            INT,
    before_snapshot       MEDIUMTEXT,
    after_snapshot        MEDIUMTEXT,
    incoming_snapshot     MEDIUMTEXT,
    INDEX idx_replay_history_key_time (issue_key(191), operation_at, id),
    INDEX idx_replay_history_issue_time (replay_issue_id, operation_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='并行回放问题跟踪完整快照';
