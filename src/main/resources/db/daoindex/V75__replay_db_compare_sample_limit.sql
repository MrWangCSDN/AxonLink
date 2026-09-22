-- Per table, per difference category; existing registrations/snapshots default to 1000.
ALTER TABLE dii_replay_db_compare_registration ADD COLUMN sample_limit INTEGER NOT NULL DEFAULT 1000;
ALTER TABLE dii_replay_db_compare_version_table ADD COLUMN sample_limit INTEGER NOT NULL DEFAULT 1000;
ALTER TABLE dii_replay_db_compare_registration ADD CONSTRAINT ck_replay_db_compare_sample_limit CHECK (sample_limit BETWEEN 0 AND 10000);
ALTER TABLE dii_replay_db_compare_version_table ADD CONSTRAINT ck_replay_db_compare_version_sample_limit CHECK (sample_limit BETWEEN 0 AND 10000);
