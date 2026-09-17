ALTER TABLE dii_replay_db_compare_registration
    ADD COLUMN where_condition_json LONGTEXT;

ALTER TABLE dii_replay_db_compare_registration
    ADD COLUMN compare_limit BIGINT;

ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN where_condition_json LONGTEXT;

ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN where_sql LONGTEXT;

ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN compare_limit BIGINT;

ALTER TABLE dii_replay_db_compare_version_field
    ADD COLUMN primary_key_order INT;
