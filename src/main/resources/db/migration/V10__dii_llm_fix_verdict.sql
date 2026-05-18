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

-- 看板"整改分布/需整改"聚合按 task_id 过滤再 GROUP BY domain，
-- task_id 作前导列才能命中；llm_fix_verdict 跟随用于覆盖过滤条件。
CREATE INDEX idx_dii_task_fix_verdict ON dii_analysis_item (task_id, llm_fix_verdict);
