-- -1 keeps every difference detail; existing values and the V76 default are retained.
ALTER TABLE dii_replay_db_compare_registration DROP CHECK ck_replay_db_compare_sample_limit;
ALTER TABLE dii_replay_db_compare_registration
    ADD CONSTRAINT ck_replay_db_compare_sample_limit CHECK (sample_limit = -1 OR sample_limit BETWEEN 0 AND 10000);
ALTER TABLE dii_replay_db_compare_version_table DROP CHECK ck_replay_db_compare_version_sample_limit;
ALTER TABLE dii_replay_db_compare_version_table
    ADD CONSTRAINT ck_replay_db_compare_version_sample_limit CHECK (sample_limit = -1 OR sample_limit BETWEEN 0 AND 10000);
