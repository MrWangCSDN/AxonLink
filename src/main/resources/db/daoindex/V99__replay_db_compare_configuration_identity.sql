ALTER TABLE dii_replay_db_compare_registration
    ADD COLUMN configuration_name VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN configuration_name VARCHAR(200) NOT NULL DEFAULT '';

-- table_name remains the immutable comparison code. TABLE registrations also retain their real source.
UPDATE dii_replay_db_compare_registration
SET source_table_name = table_name
WHERE registration_type = 'TABLE' AND (source_table_name IS NULL OR TRIM(source_table_name) = '');
UPDATE dii_replay_db_compare_version_table
SET source_table_name = table_name
WHERE registration_type = 'TABLE' AND (source_table_name IS NULL OR TRIM(source_table_name) = '');

CREATE TABLE IF NOT EXISTS dii_replay_db_compare_configuration_sequence (
    schema_name VARCHAR(128) NOT NULL,
    source_table_name VARCHAR(256) NOT NULL,
    last_suffix BIGINT NOT NULL DEFAULT 0,
    base_reserved INT NOT NULL DEFAULT 0,
    PRIMARY KEY (schema_name, source_table_name)
);
