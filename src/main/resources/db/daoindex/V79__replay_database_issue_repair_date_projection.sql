ALTER TABLE dii_replay_database_issue
    ADD COLUMN defect_repair_date VARCHAR(32) NOT NULL DEFAULT '';

-- Project the original date without rewriting snapshots or conflating JSON null with the text "null".
UPDATE dii_replay_database_issue
SET defect_repair_date = CASE WHEN JSON_VALID(data_snapshot) = 1 THEN
        CASE WHEN JSON_TYPE(JSON_EXTRACT(data_snapshot, '$.defectRepairDate')) = 'NULL' THEN ''
            ELSE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_snapshot, '$.defectRepairDate')), '') END
        ELSE '' END;
