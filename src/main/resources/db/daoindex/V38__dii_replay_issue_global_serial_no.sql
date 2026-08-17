ALTER TABLE dii_replay_issue
    ADD COLUMN global_serial_no VARCHAR(512) NULL COMMENT '全局流水号' AFTER serial_no;
