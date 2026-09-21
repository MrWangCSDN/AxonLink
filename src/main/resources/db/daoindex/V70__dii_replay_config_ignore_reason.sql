-- 回放配置管理：忽略原因（写入时必填）
--
-- 应用方式：本项目无自动 Flyway，本脚本需在 V69 之后人工对结果库 MySQL 执行。
-- 说明：
--   1) 四张业务表新增 ignore_reason（忽略原因），列允许 NULL：
--      - 存量历史数据保持为空（NULL），不伪造占位值；
--      - 新增、修改由应用层强制必填，因此被修改过的存量记录必须补登记原因。
--   2) 四张操作表新增列式审计列 ignore_reason / new_ignore_reason。

-- ═══════════ 无条件忽略 ═══════════
ALTER TABLE dii_replay_unconditional_ignore
    ADD COLUMN ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
        NULL COMMENT '忽略原因' AFTER field_name;

ALTER TABLE dii_replay_unconditional_ignore_operation
    ADD COLUMN ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略原因旧值',
    ADD COLUMN new_ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略原因新值';

-- ═══════════ 有条件忽略 ═══════════
ALTER TABLE dii_replay_conditional_rmove
    ADD COLUMN ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
        NULL COMMENT '忽略原因' AFTER dest_field_cond;

ALTER TABLE dii_replay_conditional_rmove_operation
    ADD COLUMN ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略原因旧值',
    ADD COLUMN new_ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略原因新值';

-- ═══════════ 错误码忽略 ═══════════
ALTER TABLE dii_replay_error_code_ignore_config
    ADD COLUMN ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
        NULL COMMENT '忽略原因' AFTER new_resp_code;

ALTER TABLE dii_replay_error_code_ignore_config_operation
    ADD COLUMN ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略原因旧值',
    ADD COLUMN new_ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略原因新值';

-- ═══════════ 排序字段 ═══════════
ALTER TABLE dii_replay_sort_field
    ADD COLUMN ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
        NULL COMMENT '忽略原因' AFTER orig_field_name;

ALTER TABLE dii_replay_sort_field_operation
    ADD COLUMN ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略原因旧值',
    ADD COLUMN new_ignore_reason VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略原因新值';
