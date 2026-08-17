CREATE TABLE IF NOT EXISTS dii_replay_issue_occurrence_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    replay_issue_id BIGINT NOT NULL,
    issue_key VARCHAR(1024) NOT NULL,
    batch_name VARCHAR(128) NOT NULL,
    first_occurred_at DATETIME NOT NULL,
    last_occurred_at DATETIME NOT NULL,
    last_status VARCHAR(32),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uq_replay_issue_occurrence_batch (replay_issue_id, batch_name),
    INDEX idx_replay_occurrence_batch_name (batch_name),
    INDEX idx_replay_occurrence_batch_issue (replay_issue_id, last_occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放问题出现批次关系';

ALTER TABLE dii_replay_issue_history
    ADD COLUMN occurrence_batch_name VARCHAR(128) DEFAULT NULL COMMENT '历史操作所属出现批次' AFTER context_round_id,
    ADD INDEX idx_replay_history_occurrence_batch (replay_issue_id, occurrence_batch_name, operation_at, id);

INSERT IGNORE INTO dii_replay_issue_occurrence_batch
    (replay_issue_id, issue_key, batch_name, first_occurred_at, last_occurred_at, last_status, created_at, updated_at)
SELECT i.id, i.issue_key, TRIM(i.batch_no), MIN(i.imported_at), MAX(i.imported_at),
       SUBSTRING_INDEX(GROUP_CONCAT(i.issue_status ORDER BY i.imported_at DESC, i.id DESC), ',', 1),
       MIN(i.imported_at), MAX(i.imported_at)
  FROM dii_replay_issue i
 WHERE TRIM(COALESCE(i.batch_no, '')) <> ''
 GROUP BY i.id, i.issue_key, TRIM(i.batch_no);

UPDATE dii_replay_issue_history h
JOIN dii_replay_issue i ON i.id = h.replay_issue_id
   SET h.occurrence_batch_name = NULLIF(TRIM(i.batch_no), '')
 WHERE h.occurrence_batch_name IS NULL
   AND TRIM(COALESCE(i.batch_no, '')) <> '';
