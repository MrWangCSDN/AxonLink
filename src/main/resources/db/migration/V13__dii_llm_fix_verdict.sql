-- ─────────────────────────────────────────────────────────────────────────────
-- V13：新增 LLM 整改判定字段 llm_fix_verdict
--
-- 历史：原本命名为 V10__dii_llm_fix_verdict.sql（2026-05-19 创建于 refactor/dii-explain-first 分支），
-- 后 main 分支 2026-05-21 也合入了一个 V10__code_commit_stat.sql（GitLab 源码提交分析 Phase 8）。
-- 合并 main 进 refactor 时发现两 V10 同号冲突，按"已发布提交不改 history"原则把本文件改名 V13。
-- 已发出的部署包（0519-1058 / 0518-1722 / 0519-1420 / 0520-1103-ldap / 0521-1617-spdb）里仍是 V10__；
-- 运维若用旧包已执行过 V10__dii_llm_fix_verdict.sql 的环境，无需重跑（DDL 内容字面未变，仅文件名变）。
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
