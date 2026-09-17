-- 回放配置管理：审核功能
--
-- 应用方式：本项目无自动 Flyway，本脚本需在 V68 之后人工对结果库 MySQL 执行。
-- 说明：
--   1) 四张业务表新增 review_status（0=未审核，1=已审核，默认 0）。
--   2) 四张操作表新增列式审计列 review_status / new_review_status。
--   3) 审核动作 operation_type 记为 'REVIEW'，只记录 review_status 的前后值。

-- ═══════════ 无条件忽略 ═══════════
ALTER TABLE dii_replay_unconditional_ignore
    ADD COLUMN review_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未审核 1已审核' AFTER enable_flag;

ALTER TABLE dii_replay_unconditional_ignore_operation
    ADD COLUMN review_status TINYINT NULL COMMENT '审核状态旧值',
    ADD COLUMN new_review_status TINYINT NULL COMMENT '审核状态新值';

-- ═══════════ 有条件忽略 ═══════════
ALTER TABLE dii_replay_conditional_rmove
    ADD COLUMN review_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未审核 1已审核' AFTER field_fiel_state;

ALTER TABLE dii_replay_conditional_rmove_operation
    ADD COLUMN review_status TINYINT NULL COMMENT '审核状态旧值',
    ADD COLUMN new_review_status TINYINT NULL COMMENT '审核状态新值';

-- ═══════════ 错误码忽略 ═══════════
ALTER TABLE dii_replay_error_code_ignore_config
    ADD COLUMN review_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未审核 1已审核' AFTER enabled;

ALTER TABLE dii_replay_error_code_ignore_config_operation
    ADD COLUMN review_status TINYINT NULL COMMENT '审核状态旧值',
    ADD COLUMN new_review_status TINYINT NULL COMMENT '审核状态新值';

-- ═══════════ 排序字段 ═══════════
ALTER TABLE dii_replay_sort_field
    ADD COLUMN review_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未审核 1已审核' AFTER tran_mode;

ALTER TABLE dii_replay_sort_field_operation
    ADD COLUMN review_status TINYINT NULL COMMENT '审核状态旧值',
    ADD COLUMN new_review_status TINYINT NULL COMMENT '审核状态新值';
