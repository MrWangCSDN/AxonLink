-- Existing registrations and immutable snapshots retain single-partition behavior.
ALTER TABLE dii_replay_db_compare_registration ADD COLUMN partition_num INTEGER NOT NULL DEFAULT 1;
ALTER TABLE dii_replay_db_compare_version_table ADD COLUMN partition_num INTEGER NOT NULL DEFAULT 1;
ALTER TABLE dii_replay_db_compare_registration ADD CONSTRAINT ck_replay_db_compare_partition_num CHECK (partition_num BETWEEN 1 AND 256);
ALTER TABLE dii_replay_db_compare_version_table ADD CONSTRAINT ck_replay_db_compare_version_partition_num CHECK (partition_num BETWEEN 1 AND 256);
