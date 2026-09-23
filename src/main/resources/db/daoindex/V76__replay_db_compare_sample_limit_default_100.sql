-- Only future rows use 100. Existing registrations and immutable version snapshots retain their values.
ALTER TABLE dii_replay_db_compare_registration ALTER COLUMN sample_limit SET DEFAULT 100;
ALTER TABLE dii_replay_db_compare_version_table ALTER COLUMN sample_limit SET DEFAULT 100;
