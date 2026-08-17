CREATE TABLE IF NOT EXISTS dii_replay_import_round (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_code VARCHAR(64) NOT NULL,
    imported_at DATETIME NOT NULL,
    operator_username VARCHAR(128),
    operator_real_name VARCHAR(128),
    input_rows INT NOT NULL DEFAULT 0,
    created_rows INT NOT NULL DEFAULT 0,
    updated_rows INT NOT NULL DEFAULT 0,
    ignored_rows INT NOT NULL DEFAULT 0,
    auto_repaired_rows INT NOT NULL DEFAULT 0,
    UNIQUE KEY uq_replay_import_round_code (round_code),
    INDEX idx_replay_import_round_time (imported_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放问题正式导入轮次';

CREATE TABLE IF NOT EXISTS dii_replay_issue_round (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_id BIGINT NOT NULL,
    replay_issue_id BIGINT NOT NULL,
    issue_key VARCHAR(1024) NOT NULL,
    issue_key_hash CHAR(64) GENERATED ALWAYS AS (SHA2(issue_key, 256)) STORED,
    appeared TINYINT(1) NOT NULL,
    status_before VARCHAR(32),
    status_after VARCHAR(32) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    source_sheet VARCHAR(64),
    source_row INT,
    recorded_at DATETIME NOT NULL,
    UNIQUE KEY uq_replay_issue_round_key (round_id, issue_key_hash),
    INDEX idx_replay_issue_round_issue (replay_issue_id, round_id),
    INDEX idx_replay_issue_round_membership (round_id, appeared, replay_issue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放问题每轮出现及状态结果';

ALTER TABLE dii_replay_issue_history
    ADD COLUMN context_round_id BIGINT DEFAULT NULL COMMENT '操作所属正式导入轮次' AFTER coverage_round,
    ADD INDEX idx_replay_history_context_round (context_round_id, replay_issue_id, operation_at, id);

INSERT IGNORE INTO dii_replay_import_round (round_code, imported_at)
SELECT coverage_round, MAX(imported_at)
  FROM dii_replay_issue
 WHERE coverage_round IS NOT NULL AND TRIM(coverage_round) <> ''
 GROUP BY coverage_round;

INSERT IGNORE INTO dii_replay_import_round (round_code, imported_at)
SELECT coverage_round, MAX(operation_at)
  FROM dii_replay_issue_history
 WHERE coverage_round IS NOT NULL AND TRIM(coverage_round) <> ''
 GROUP BY coverage_round;

INSERT IGNORE INTO dii_replay_issue_round (
    round_id, replay_issue_id, issue_key, appeared, status_before, status_after,
    action_type, source_sheet, source_row, recorded_at)
SELECT r.id, i.id, i.issue_key, 1, NULL, i.issue_status,
       '历史已知出现', i.source_sheet, i.row_order + 1, i.imported_at
  FROM dii_replay_issue i
  JOIN dii_replay_import_round r ON r.round_code = i.coverage_round
 WHERE i.coverage_round IS NOT NULL AND TRIM(i.coverage_round) <> '';

UPDATE dii_replay_issue_history h
JOIN dii_replay_import_round r ON r.round_code = h.coverage_round
   SET h.context_round_id = r.id
 WHERE h.coverage_round IS NOT NULL AND TRIM(h.coverage_round) <> '';
