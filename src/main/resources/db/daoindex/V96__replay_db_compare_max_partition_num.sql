-- Maximum HASH partition caps are independent of reading partitions and sample limits.
-- Existing registrations and immutable snapshots use the historical default cap of 64.
ALTER TABLE dii_replay_db_compare_registration ADD COLUMN max_partition_num INTEGER NOT NULL DEFAULT 64;
ALTER TABLE dii_replay_db_compare_version_table ADD COLUMN max_partition_num INTEGER NOT NULL DEFAULT 64;
ALTER TABLE dii_replay_db_compare_registration ADD CONSTRAINT ck_replay_db_compare_max_partition_num CHECK (max_partition_num BETWEEN 1 AND 256);
ALTER TABLE dii_replay_db_compare_version_table ADD CONSTRAINT ck_replay_db_compare_version_max_partition_num CHECK (max_partition_num BETWEEN 1 AND 256);
