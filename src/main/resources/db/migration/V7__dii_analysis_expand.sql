-- ─────────────────────────────────────────────────────────────────────────────
-- V7：DAO 索引巡检明细表扩展完整版
--
-- 背景：V6 的 dii_analysis_item 是 Phase 1 的最小占位版，只够 health 检查用。
-- 本次扩展为完整版，覆盖：
--   - 规则引擎评级结果（Phase 2a）
--   - EXPLAIN 执行结果（Phase 2b 预留）
--   - LLM 解释与优化建议（Phase 2c 预留）
--
-- 幂等窗口机制：
--   idx_hash_env_time 专门服务于"5 分钟内同 sql_hash+env 直接复用结果"的查询。
--   应用层逻辑：先 SELECT ... WHERE sql_hash=? AND env=? AND status='DONE'
--              AND created_at > NOW() - INTERVAL 5 MINUTE LIMIT 1，命中即返回旧结论。
--
-- 任务级去重：
--   同一个 task_id 内，由应用层按 sql_hash 去重后再分发，避免并发下重复分析。
-- ─────────────────────────────────────────────────────────────────────────────

DROP TABLE IF EXISTS dii_analysis_item;

CREATE TABLE dii_analysis_item (
    id                     BIGINT       AUTO_INCREMENT PRIMARY KEY,

    -- ══════════════════════════════════════════════════════════════
    -- SQL 身份（幂等判重的核心字段组合：sql_hash + env）
    -- ══════════════════════════════════════════════════════════════
    sql_hash               VARCHAR(64)  NOT NULL                     COMMENT 'SHA-256(规范化SQL)；5分钟幂等窗口 key 的主成分',
    sql_text               TEXT         NOT NULL                     COMMENT '原始 SQL（保留 #xxx# 等占位符）',
    sql_kind               VARCHAR(16)  NOT NULL                     COMMENT 'SELECT/UPDATE/DELETE/INSERT_VALUES/INSERT_SELECT/UNKNOWN',
    env                    VARCHAR(16)  NOT NULL                     COMMENT '目标库环境：dev/sit/uat',

    -- ══════════════════════════════════════════════════════════════
    -- 批量任务关联
    -- ══════════════════════════════════════════════════════════════
    task_id                BIGINT                                    COMMENT '批量任务ID（单条分析为 NULL）',

    -- ══════════════════════════════════════════════════════════════
    -- SQL 来源（batch 扫描阶段填充，单条分析可空）
    -- ══════════════════════════════════════════════════════════════
    project_name           VARCHAR(200)                              COMMENT '工程名，如 xxx-bcc',
    class_fqn              VARCHAR(500)                              COMMENT 'SQL 所在 Java 类全名',
    method_name            VARCHAR(200)                              COMMENT 'SQL 所在方法名',
    source_file            VARCHAR(1000)                             COMMENT 'SQL 源文件绝对路径',

    -- ══════════════════════════════════════════════════════════════
    -- 规则引擎结果（Phase 2a，已完成）
    -- ══════════════════════════════════════════════════════════════
    overall_rating         VARCHAR(16)                               COMMENT 'POOR/GOOD/EXCELLENT/NOT_APPLICABLE',
    rating_label           VARCHAR(16)                               COMMENT '差/良/优/不适用（前端直接展示）',
    involved_tables        VARCHAR(2000)                             COMMENT '涉及的表名，逗号分隔（便于按表检索）',
    table_ratings_json     MEDIUMTEXT                                COMMENT '每表评级详情完整 JSON',
    rule_engine_elapsed_ms INT                                       COMMENT '规则引擎耗时 ms',

    -- ══════════════════════════════════════════════════════════════
    -- EXPLAIN 结果（Phase 2b 预留，暂时留空）
    -- ══════════════════════════════════════════════════════════════
    explain_plan           MEDIUMTEXT                                COMMENT 'EXPLAIN (GENERIC_PLAN, FORMAT JSON) 原始输出',
    explain_top_cost       DOUBLE                                    COMMENT 'EXPLAIN 顶层 total cost',
    explain_est_rows       BIGINT                                    COMMENT 'EXPLAIN 估算行数',
    explain_has_seq_scan   TINYINT(1)                                COMMENT '1=存在 Seq Scan，0=无',
    explain_elapsed_ms     INT                                       COMMENT 'EXPLAIN 执行耗时 ms',

    -- ══════════════════════════════════════════════════════════════
    -- LLM 结果（Phase 2c 预留，暂时留空）
    -- ══════════════════════════════════════════════════════════════
    llm_model              VARCHAR(64)                               COMMENT '使用模型名，如 glm-47',
    llm_prompt_version     VARCHAR(32)                               COMMENT 'Prompt 版本号，便于回溯',
    llm_explain            MEDIUMTEXT                                COMMENT 'LLM 自然语言解释',
    llm_suggestions        MEDIUMTEXT                                COMMENT 'LLM 建议 JSON 数组：[{type,ddl,reason}]',
    llm_elapsed_ms         INT                                       COMMENT 'LLM 调用耗时 ms',
    llm_error              VARCHAR(1000)                             COMMENT 'LLM 调用失败原因',

    -- ══════════════════════════════════════════════════════════════
    -- 状态与错误
    -- ══════════════════════════════════════════════════════════════
    status                 VARCHAR(16)  NOT NULL DEFAULT 'DONE'      COMMENT 'PENDING/RUNNING/DONE/FAILED/SKIPPED',
    error_msg              VARCHAR(1000)                             COMMENT '失败原因概述',
    warnings_json          VARCHAR(2000)                             COMMENT '规则引擎/解析阶段的 warnings 列表 JSON',

    -- ══════════════════════════════════════════════════════════════
    -- 时间戳
    -- ══════════════════════════════════════════════════════════════
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- ══════════════════════════════════════════════════════════════
    -- 索引设计
    -- ══════════════════════════════════════════════════════════════
    -- 【核心】5 分钟幂等窗口查询的唯一主力索引：
    --   SELECT ... WHERE sql_hash=? AND env=? AND status='DONE'
    --              AND created_at > NOW() - INTERVAL 5 MINUTE
    --              ORDER BY created_at DESC LIMIT 1
    INDEX idx_hash_env_time (sql_hash, env, created_at),
    INDEX idx_task          (task_id),
    INDEX idx_rating_env    (overall_rating, env),
    INDEX idx_env_created   (env, created_at),
    INDEX idx_status        (status),
    INDEX idx_project       (project_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='DAO 索引巡检 SQL 明细表（Phase 2a 扩展完整版）';


-- ─────────────────────────────────────────────────────────────────────────────
-- 对齐 task 表，新增 owner / prompt_version / rule_engine_version
-- Flyway 已有版本控制，不需要 IF NOT EXISTS；每个 V 脚本只运行一次。
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE dii_analysis_task
    ADD COLUMN owner               VARCHAR(64)  COMMENT '发起用户/服务标识' AFTER trigger_type,
    ADD COLUMN llm_prompt_version  VARCHAR(32)  COMMENT '本次任务使用的 LLM prompt 版本',
    ADD COLUMN rule_engine_version VARCHAR(32)  COMMENT '本次任务使用的规则引擎版本（便于回溯）';
