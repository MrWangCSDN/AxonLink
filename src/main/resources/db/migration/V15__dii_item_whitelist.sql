-- ─────────────────────────────────────────────────────────────────────────────
-- V15：dii_analysis_item 加白名单字段 is_whitelist
--
-- 背景：与 V14 的 dii_sql_pool.is_whitelist 配套——DBA 标记"故意不命中索引
--   但可接受"的 SQL（如统计场景全表扫描可接受）；看板 / LLM 触发可按此过滤，
--   避免反复刷出已确认的可接受项噪声。
--
-- 设计要点：
--   - 仅一列 TINYINT(1)，不引入 ENUM
--   - 索引 (env, is_whitelist) 兜底看板/聚合按 env 维度过滤白名单
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE dii_analysis_item
    ADD COLUMN is_whitelist TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'DBA 标记的白名单 SQL（"故意不命中索引但可接受"），看板/聚合可过滤';

CREATE INDEX idx_dii_item_whitelist ON dii_analysis_item (env, is_whitelist);
