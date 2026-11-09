-- Manual migration: apply once before deploying code that reads or writes these columns.
-- Historical both_success values remain unchanged; unknown split counts remain NULL.
ALTER TABLE dii_replay_interface_comparison
    ADD COLUMN both_success_no_field_difference BIGINT NULL COMMENT '二者均成功且无字段差异';

ALTER TABLE dii_replay_interface_comparison
    ADD COLUMN both_success_with_field_difference BIGINT NULL COMMENT '二者均成功且有字段差异';
