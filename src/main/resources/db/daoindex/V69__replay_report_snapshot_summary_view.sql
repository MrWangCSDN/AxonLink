ALTER TABLE dii_replay_daily_report_snapshot
    ADD COLUMN summary_view_json MEDIUMTEXT NULL AFTER file_size;

ALTER TABLE dii_replay_weekly_report_snapshot
    ADD COLUMN summary_view_json MEDIUMTEXT NULL AFTER file_size;
