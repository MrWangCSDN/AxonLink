-- ─────────────────────────────────────────────────────────────────────────────
-- V14：新建 SQL 池表 dii_sql_pool
--
-- 背景：现有巡检 SQL 源完全靠源码扫描（@Statement 抽取）。本次新增「外部导入」
--   通路——内网运维侧的 IndexWarnLog WARN 日志 Excel（行模式
--   `->[ClassName.methodName] [select ...] failed to hit the index`），按
--   命名 SQL 解析后入池，作为巡检对象。
--
-- 与 dii_analysis_item 的关系：
--   - item 表 = 一次巡检任务 (task_id) 下的「明细」（每跑一次产一行）
--   - pool 表 = 待巡检的「SQL 仓库」（每条命名 SQL 一行，可被多次巡检引用）
--   - 两表关注点不同，独立设计，不做强外键
--
-- 唯一键：(named_sql, sql_hash) — 同命名 SQL 出现轻微差异（多空格等）也算两行
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS dii_sql_pool (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,

    -- ══════════════════════════════════════════════════════════════
    -- 身份与来源
    -- ══════════════════════════════════════════════════════════════
    named_sql       VARCHAR(500) NOT NULL                     COMMENT '命名 SQL，如 DpCbQryAcctCount.sel_kdpb_cb_acct_count_temp_sum',
    sql_text        TEXT         NOT NULL                     COMMENT 'SQL 原文',
    sql_hash        VARCHAR(64)  NOT NULL                     COMMENT 'SHA-256(sql_text); 与 named_sql 共同构成 unique key',
    project_name    VARCHAR(200)                              COMMENT '工程名（导入时由调用方填写）',
    source          VARCHAR(16)  NOT NULL DEFAULT 'EXCEL'     COMMENT '来源：EXCEL/SCAN/MANUAL',
    env             VARCHAR(16)                               COMMENT '环境标记（dev/uat/...）；导入时可选',

    -- ══════════════════════════════════════════════════════════════
    -- 白名单（DBA 标记"故意不命中索引但可接受"）
    -- ══════════════════════════════════════════════════════════════
    is_whitelist    TINYINT(1)   NOT NULL DEFAULT 0           COMMENT '1=白名单（看板/聚合可过滤），0=正常',

    -- ══════════════════════════════════════════════════════════════
    -- EXPLAIN 派生结果（镜像 dii_analysis_item 同名字段，巡检产出直接落本表）
    -- ══════════════════════════════════════════════════════════════
    overall_rating       VARCHAR(16)                          COMMENT 'POOR/EXCELLENT/NOT_APPLICABLE/null',
    rating_label         VARCHAR(16)                          COMMENT '待整改/无需整改/不适用',
    explain_plan         MEDIUMTEXT                           COMMENT 'EXPLAIN JSON',
    explain_top_cost     DOUBLE                               COMMENT 'EXPLAIN 顶层 cost',
    explain_est_rows     BIGINT                               COMMENT 'EXPLAIN 估算行数',
    explain_has_seq_scan TINYINT(1)                           COMMENT '1=存在 Seq Scan',
    explain_elapsed_ms   INT                                  COMMENT 'EXPLAIN 耗时 ms',
    explain_error        VARCHAR(1000)                        COMMENT 'EXPLAIN 失败原因',
    involved_tables      VARCHAR(2000)                        COMMENT '涉及的表名，逗号分隔',

    -- ══════════════════════════════════════════════════════════════
    -- LLM 结果（镜像 dii_analysis_item 同名字段）
    -- ══════════════════════════════════════════════════════════════
    llm_status           VARCHAR(16)                          COMMENT 'PENDING/DONE/FAILED/SKIPPED',
    llm_summary          VARCHAR(500)                         COMMENT 'LLM 一句话摘要',
    llm_findings_json    MEDIUMTEXT                           COMMENT 'LLM 发现 JSON 数组',
    llm_suggestions_json MEDIUMTEXT                           COMMENT 'LLM 建议 JSON 数组',
    llm_confidence       VARCHAR(16)                          COMMENT 'HIGH/MEDIUM/LOW',
    llm_fix_verdict      VARCHAR(16)                          COMMENT 'NEED_FIX(待整改)/NO_NEED(无需整改)',
    llm_model            VARCHAR(64)                          COMMENT '使用模型名',
    llm_prompt_version   VARCHAR(32)                          COMMENT 'Prompt 版本号',
    llm_elapsed_ms       INT                                  COMMENT 'LLM 调用耗时 ms',
    llm_error            VARCHAR(1000)                        COMMENT 'LLM 调用失败原因',
    llm_called_at        DATETIME                             COMMENT 'LLM 首次调用时间',

    -- ══════════════════════════════════════════════════════════════
    -- 时间戳
    -- ══════════════════════════════════════════════════════════════
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- ══════════════════════════════════════════════════════════════
    -- 索引
    -- ══════════════════════════════════════════════════════════════
    UNIQUE KEY uk_named_hash (named_sql, sql_hash),
    INDEX idx_project   (project_name),
    INDEX idx_whitelist (is_whitelist),
    INDEX idx_rating    (overall_rating),
    INDEX idx_created   (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DAO 索引巡检 SQL 池表（Excel 导入 / 外部来源）';
