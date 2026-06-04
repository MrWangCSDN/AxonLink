-- ─────────────────────────────────────────────────────────────────────────────
-- V21：ER 图「当前绘制」元信息
--
-- 记录每个 env 的 ER 图当前是怎么画出来的：
--   - 重算（REBUILD）：从目标库扫描推断
--   - 导入（IMPORT） ：用人工修改过的导出 Excel 整库替换
-- 前端据此展示「绘制时间描述」：当前绘制：Excel导入 · 2026-06-04 09:43 · 128 条关系
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS dii_er_meta (
    env            VARCHAR(16)  NOT NULL PRIMARY KEY COMMENT '环境（空串=default-env）',
    last_source    VARCHAR(16)  NOT NULL            COMMENT 'REBUILD / IMPORT',
    last_built_at  DATETIME     NOT NULL            COMMENT '本次绘制（重算/导入）完成时间',
    relation_count INT          NOT NULL DEFAULT 0  COMMENT '当前关系条数',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ER 图当前绘制来源与时间';
