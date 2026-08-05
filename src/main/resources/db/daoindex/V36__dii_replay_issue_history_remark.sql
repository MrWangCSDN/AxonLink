ALTER TABLE dii_replay_issue_history
    ADD COLUMN remark MEDIUMTEXT DEFAULT NULL COMMENT '人工编辑备注' AFTER cooperation_person_real_name;
