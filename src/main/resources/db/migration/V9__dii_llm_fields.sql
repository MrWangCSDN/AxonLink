-- ─────────────────────────────────────────────────────────────────────────────
-- V9：LLM 分析字段扩展
--
-- 设计原则：
--   - 不新增"表级建议"表，所有 LLM 分析都以"单条 SQL"为单位
--   - 但 LLM 在分析单条 SQL 时会结合"整张表的上下文"（所有索引 + 同表其他 SQL 访问模式）
--   - 建议列表里用 scope 字段区分：TABLE（DDL 动作，同表其他 SQL 也会受益）/ SQL（仅本 SQL 相关）
--   - DBA 想看"表维度"时，通过查询聚合 dii_analysis_item 的 suggestions_json 实现
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE dii_analysis_item
    -- ── LLM 调度标志 ──
    ADD COLUMN llm_pending TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '1=待 LLM 分析（rating<=GOOD 或 disagreement=true 或 schema drift 触发），0=已完成/无需',
    ADD COLUMN llm_status VARCHAR(16)
        COMMENT 'PENDING / DONE / FAILED / SKIPPED',

    -- ── LLM 产出 ──
    ADD COLUMN llm_summary VARCHAR(500)
        COMMENT 'LLM 给出的一句话摘要，供列表页展示',
    ADD COLUMN llm_findings_json MEDIUMTEXT
        COMMENT 'LLM 发现的问题列表 JSON：[{type,severity,description,evidence}]',
    ADD COLUMN llm_suggestions_json MEDIUMTEXT
        COMMENT 'LLM 建议列表 JSON：[{scope(TABLE/SQL),type,ddl|newSql,reason,priority}]',
    ADD COLUMN llm_confidence VARCHAR(16)
        COMMENT 'HIGH / MEDIUM / LOW（LLM 对本次建议的置信度）',

    -- ── LLM 调用元数据 ──
    -- 注：llm_prompt_version 已在 V7 里定义过了，这里不重复 ADD（避免 "Duplicate column name" 报错）
    ADD COLUMN llm_called_at DATETIME
        COMMENT 'LLM 首次调用时间（失败重试会被覆盖）';

-- ── 索引 ──
-- 拉取待 LLM 分析清单的核心查询
CREATE INDEX idx_dii_llm_pending ON dii_analysis_item (llm_pending, llm_status, env);
-- 按 task + 状态查：重跑 FAILED / 看进度
CREATE INDEX idx_dii_task_llm_status ON dii_analysis_item (task_id, llm_status);
