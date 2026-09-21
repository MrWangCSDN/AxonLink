ALTER TABLE dii_replay_issue
    ADD COLUMN review_reason VARCHAR(500) NULL COMMENT '无需处理审核原因' AFTER reviewed_at;

ALTER TABLE dii_replay_issue_history
    ADD COLUMN review_reason VARCHAR(500) NULL COMMENT '无需处理审核原因快照' AFTER reviewed_at;
