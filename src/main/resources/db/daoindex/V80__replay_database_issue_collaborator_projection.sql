ALTER TABLE dii_replay_database_issue
    ADD COLUMN cooperation_person_display VARCHAR(256) NOT NULL DEFAULT '';

-- Keep the original collaborator display text; do not rebuild identities or rewrite snapshots.
UPDATE dii_replay_database_issue
SET cooperation_person_display = CASE WHEN JSON_VALID(data_snapshot) = 1 THEN
        CASE WHEN JSON_TYPE(JSON_EXTRACT(data_snapshot, '$.cooperationPerson')) = 'NULL' THEN ''
            ELSE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_snapshot, '$.cooperationPerson')), '') END
        ELSE '' END;
