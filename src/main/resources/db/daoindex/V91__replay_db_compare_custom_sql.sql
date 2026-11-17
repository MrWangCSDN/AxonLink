ALTER TABLE dii_replay_db_compare_registration
    ADD COLUMN registration_type VARCHAR(20) NOT NULL DEFAULT 'TABLE';
ALTER TABLE dii_replay_db_compare_registration
    ADD COLUMN source_table_name VARCHAR(256);
ALTER TABLE dii_replay_db_compare_registration
    ADD COLUMN custom_sql TEXT;
ALTER TABLE dii_replay_db_compare_registration
    ADD COLUMN result_columns_json LONGTEXT;

ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN registration_type VARCHAR(20) NOT NULL DEFAULT 'TABLE';
ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN source_table_name VARCHAR(256);
ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN custom_sql TEXT;
ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN result_columns_json LONGTEXT;

ALTER TABLE dii_replay_db_compare_version
    ADD COLUMN custom_sql_count INT NOT NULL DEFAULT 0;
