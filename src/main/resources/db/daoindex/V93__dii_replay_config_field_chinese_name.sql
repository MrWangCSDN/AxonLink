-- 忽略清单字段中文名称：历史数据允许为空，应用层新增/修改时必填。

ALTER TABLE dii_replay_unconditional_ignore
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略字段中文名称' AFTER field_name;

ALTER TABLE dii_replay_unconditional_ignore_operation
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '原忽略字段中文名称' AFTER field_name,
    ADD COLUMN new_field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '新忽略字段中文名称' AFTER new_field_name;

ALTER TABLE dii_replay_conditional_rmove
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略字段中文名称' AFTER field_rmove_name;

ALTER TABLE dii_replay_conditional_rmove_operation
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '原忽略字段中文名称' AFTER field_rmove_name,
    ADD COLUMN new_field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '新忽略字段中文名称' AFTER new_field_rmove_name;

ALTER TABLE dii_replay_sort_field
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '排序字段中文名称' AFTER orig_field_name;

ALTER TABLE dii_replay_sort_field_operation
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '原排序字段中文名称' AFTER orig_field_name,
    ADD COLUMN new_field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '新排序字段中文名称' AFTER new_orig_field_name;
