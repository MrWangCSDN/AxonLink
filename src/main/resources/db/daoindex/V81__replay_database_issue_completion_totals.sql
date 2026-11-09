ALTER TABLE dii_replay_database_issue ADD COLUMN total_528 BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dii_replay_database_issue ADD COLUMN total_ccbs BIGINT NOT NULL DEFAULT 0;

UPDATE dii_replay_database_issue
SET total_528 = CASE WHEN JSON_VALID(data_snapshot) = 1
        THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_snapshot, '$.total528')), '0') ELSE 0 END,
    total_ccbs = CASE WHEN JSON_VALID(data_snapshot) = 1
        THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_snapshot, '$.totalCcbs')), '0') ELSE 0 END;
