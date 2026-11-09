ALTER TABLE dii_replay_database_issue_batch
    ADD COLUMN registration_version_id BIGINT DEFAULT NULL COMMENT '本批数据库比对登记历史版本ID';

CREATE INDEX idx_replay_db_issue_batch_registration_version
    ON dii_replay_database_issue_batch (registration_version_id);
