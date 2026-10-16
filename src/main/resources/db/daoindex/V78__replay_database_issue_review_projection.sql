ALTER TABLE dii_replay_database_issue
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE dii_replay_database_issue
    ADD COLUMN review_reason VARCHAR(500) NOT NULL DEFAULT '';

-- Keep historical approvals (including empty reasons); registration identities are not changed.
UPDATE dii_replay_database_issue
SET review_status = CASE WHEN JSON_VALID(data_snapshot) = 1 THEN
        CASE JSON_UNQUOTE(JSON_EXTRACT(data_snapshot, '$.reviewStatus'))
            WHEN '待审核' THEN '待审核'
            WHEN '已审核' THEN '已审核'
            ELSE '' END
        ELSE '' END,
    review_reason = CASE WHEN JSON_VALID(data_snapshot) = 1 THEN
        CASE WHEN JSON_TYPE(JSON_EXTRACT(data_snapshot, '$.reviewReason')) = 'NULL' THEN ''
            ELSE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_snapshot, '$.reviewReason')), '') END
        ELSE '' END;
