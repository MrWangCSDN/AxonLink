ALTER TABLE dii_replay_daily_report_mail
    ADD COLUMN attachment_manifest MEDIUMTEXT NULL AFTER cc_emails;
UPDATE dii_replay_daily_report_mail
   SET attachment_manifest = '[]'
 WHERE attachment_manifest IS NULL OR attachment_manifest = '';
ALTER TABLE dii_replay_daily_report_mail
    MODIFY COLUMN attachment_manifest MEDIUMTEXT NOT NULL;

ALTER TABLE dii_replay_weekly_report_mail
    ADD COLUMN attachment_manifest MEDIUMTEXT NULL AFTER cc_emails;
UPDATE dii_replay_weekly_report_mail
   SET attachment_manifest = '[]'
 WHERE attachment_manifest IS NULL OR attachment_manifest = '';
ALTER TABLE dii_replay_weekly_report_mail
    MODIFY COLUMN attachment_manifest MEDIUMTEXT NOT NULL;
