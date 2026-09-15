ALTER TABLE dii_replay_db_compare_audit_event
    ADD COLUMN operator_username VARCHAR(128);

ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN group_owner_username VARCHAR(128);
