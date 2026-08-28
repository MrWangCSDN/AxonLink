UPDATE dii_replay_issue
SET planned_completion_date = defect_repair_date
WHERE issue_status = '已修复'
  AND planned_completion_date IS NULL
  AND defect_repair_date IS NOT NULL;

UPDATE dii_replay_issue
SET defect_repair_date = DATE(reviewed_at)
WHERE issue_status = '无需处理'
  AND review_status = 'APPROVED'
  AND defect_repair_date IS NULL
  AND reviewed_at IS NOT NULL;

CREATE INDEX idx_replay_planned_completion_stats
    ON dii_replay_issue (planned_completion_date, group_name, issue_status);
