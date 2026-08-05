-- Normalize and de-duplicate legacy current rows before enforcing one projection row per issue key.
UPDATE dii_replay_issue
   SET issue_key = COALESCE(TRIM(issue_key), '');

-- Keep the oldest row (smallest generated id) for every normalized issue_key.
DELETE duplicate_row
  FROM dii_replay_issue duplicate_row
  JOIN dii_replay_issue retained_row
    ON duplicate_row.issue_key = retained_row.issue_key
   AND duplicate_row.id > retained_row.id;

ALTER TABLE dii_replay_issue
    ADD COLUMN issue_key_hash CHAR(64) GENERATED ALWAYS AS (SHA2(issue_key, 256)) STORED
        COMMENT 'issue_key SHA-256 uniqueness key',
    ADD UNIQUE INDEX uq_dii_replay_issue_key_hash (issue_key_hash);
