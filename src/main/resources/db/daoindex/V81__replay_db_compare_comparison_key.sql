-- NULL retains physical-primary-key behavior for existing registrations and immutable versions.
ALTER TABLE dii_replay_db_compare_registration ADD COLUMN comparison_key_json LONGTEXT NULL;
ALTER TABLE dii_replay_db_compare_version_table ADD COLUMN comparison_key_json LONGTEXT NULL;
