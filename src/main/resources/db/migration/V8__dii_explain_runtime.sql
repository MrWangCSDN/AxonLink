-- ─────────────────────────────────────────────────────────────────────────────
-- V8：EXPLAIN 集成 + 表元数据收集 字段扩展
--
-- 三级评估：规则引擎（V7 已落库）+ EXPLAIN 派生（本次）+ LLM 解释（B.3 落库）
--
-- 本次新增：
--   1. runtime_rating  — 基于 EXPLAIN 真实计划派生的评级（独立于规则评级）
--   2. disagreement    — 规则与 runtime 评级是否分歧（DBA 重点关注的高价值信号）
--   3. table_stats_json/column_stats_json — 喂 LLM 的"语料"，含表行数、字段基数、NULL 比例等
--   4. table_ddl_json  — 涉及表的字段类型 + 业务注释，让 LLM 能识别隐式类型转换
--   5. explain_error   — EXPLAIN 失败原因（独立于 explain_plan，便于排查）
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE dii_analysis_item
    ADD COLUMN runtime_rating       VARCHAR(16)
        COMMENT 'EXPLAIN 派生评级：POOR/GOOD/EXCELLENT/UNKNOWN（独立于规则评级，反映优化器实际选择）',
    ADD COLUMN runtime_rating_label VARCHAR(16)
        COMMENT '运行时评级中文：差/良/优/未知（前端直接展示）',
    ADD COLUMN disagreement         TINYINT(1) DEFAULT 0
        COMMENT '1=规则与 runtime 评级不一致（DBA 重点关注），0=一致',
    ADD COLUMN disagreement_reason  VARCHAR(500)
        COMMENT '不一致原因人话说明，便于审计',
    ADD COLUMN table_stats_json     MEDIUMTEXT
        COMMENT '涉及表的统计信息 JSON：{table:{liveTuples,sizeBytes,...}}',
    ADD COLUMN column_stats_json    MEDIUMTEXT
        COMMENT '涉及列的统计信息 JSON：{table.col:{distinct,nullFrac,...}}（来自 pg_stats）',
    ADD COLUMN table_ddl_json       MEDIUMTEXT
        COMMENT '涉及表的字段定义 + 业务注释 JSON（喂 LLM 用，识别隐式类型转换）',
    ADD COLUMN explain_error        VARCHAR(1000)
        COMMENT 'EXPLAIN 执行失败原因（独立字段，便于过滤排查）';

-- 高价值索引：按 disagreement 筛选 DBA 重点关注的"分歧"项
CREATE INDEX idx_dii_disagreement ON dii_analysis_item (disagreement, env);
