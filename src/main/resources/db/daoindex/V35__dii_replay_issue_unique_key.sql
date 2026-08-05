-- Intentionally fail the migration when legacy rows contain NULL or duplicate keys.
-- Those rows require explicit business reconciliation; silently deleting one would lose issue data.
ALTER TABLE dii_replay_issue
    MODIFY COLUMN issue_key VARCHAR(1024) NOT NULL,
    ADD COLUMN issue_key_hash CHAR(64) GENERATED ALWAYS AS (SHA2(issue_key, 256)) STORED
        COMMENT 'issue_key SHA-256 uniqueness key',
    ADD UNIQUE INDEX uq_dii_replay_issue_key_hash (issue_key_hash);
