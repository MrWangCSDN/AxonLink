-- ─────────────────────────────────────────────────────────────────────────────
-- V10：新增 LLM 整改判定字段 llm_fix_verdict
--
-- 设计原则：
--   - 看板"整改分布"按此字段过滤统计（替代原"评级分布"四档口径）
--   - 取值 NEED_FIX(待整改) / NO_NEED(无需整改)；NULL=未判定 / 历史行
--   - 仅当 LLM 给出可落地优化（加索引 / 改写 SQL）才判 NEED_FIX，否则 NO_NEED
--
-- 注意：本项目不挂自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE dii_analysis_item
    ADD COLUMN llm_fix_verdict VARCHAR(16)
        COMMENT 'LLM 整改判定: NEED_FIX(待整改)/NO_NEED(无需整改); NULL=未判定';

-- 看板"整改分布"按 (llm_fix_verdict, env) 过滤聚合，建联合索引加速
CREATE INDEX idx_dii_fix_verdict ON dii_analysis_item (llm_fix_verdict, env);
