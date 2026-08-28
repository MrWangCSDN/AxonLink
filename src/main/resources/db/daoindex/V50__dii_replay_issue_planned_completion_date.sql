ALTER TABLE dii_replay_issue
    ADD COLUMN planned_completion_date DATE DEFAULT NULL
    COMMENT '计划完成日期（领域授权人员维护）'
    AFTER issue_description;
