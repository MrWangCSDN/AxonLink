ALTER TABLE dii_replay_weekly_report_snapshot
    ADD CONSTRAINT uk_replay_weekly_report_end UNIQUE (end_batch_no);
