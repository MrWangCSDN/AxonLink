-- ═══════════════════════════════════════════════════════════════════════════
-- DAO 索引巡检模块 —— 结果库完整建表脚本（替换现有表）
--
-- 合并自 V6 / V7 / V8 / V9 migration。
-- 内网数据库环境：MySQL 结果库 (benchmarkdb)
--
-- ⚠️ 执行前会 DROP 现有表，旧数据会丢失。如需保留请先备份：
--    CREATE TABLE dii_analysis_item_backup AS SELECT * FROM dii_analysis_item;
--    CREATE TABLE dii_analysis_task_backup AS SELECT * FROM dii_analysis_task;
--
-- 执行方式：
--    mysql -u benchmark -p benchmarkdb < dii-schema-full.sql
-- ═══════════════════════════════════════════════════════════════════════════


-- ═══════════════════════════════════════════════════════════════════════════
-- 1. dii_analysis_task —— 批量巡检任务表
-- ═══════════════════════════════════════════════════════════════════════════

DROP TABLE IF EXISTS dii_analysis_task;

CREATE TABLE dii_analysis_task (
    id                  BIGINT        AUTO_INCREMENT PRIMARY KEY,

    -- 任务基础
    task_no             VARCHAR(64)   NOT NULL                COMMENT '任务编号 DII-yyyyMMddHHmmss-{env}',
    env                 VARCHAR(16)   NOT NULL                COMMENT '巡检目标环境：dev/sit/uat',
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                                                              COMMENT 'PENDING / RUNNING / DONE / FAILED',
    trigger_type        VARCHAR(16)   NOT NULL DEFAULT 'MANUAL'
                                                              COMMENT 'MANUAL / SCHEDULED',
    owner               VARCHAR(64)                           COMMENT '发起用户或服务',

    -- 计数
    total_sqls          INT           NOT NULL DEFAULT 0      COMMENT '扫描到的 SQL 总数（已按 hash 去重）',
    analyzed_sqls       INT           NOT NULL DEFAULT 0      COMMENT '成功分析的 SQL 数',
    failed_sqls         INT           NOT NULL DEFAULT 0      COMMENT '分析失败的 SQL 数',
    skipped_sqls        INT           NOT NULL DEFAULT 0      COMMENT '命中幂等窗口跳过的数',

    -- 版本回溯（便于历史任务追查当时用的 prompt / 引擎版本）
    rule_engine_version VARCHAR(32)                           COMMENT '规则引擎版本号',
    llm_prompt_version  VARCHAR(32)                           COMMENT 'LLM Prompt 模板版本号',

    -- 错误
    error_msg           TEXT                                  COMMENT '任务级失败原因',

    -- 时间戳
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- 索引
    INDEX idx_env_status  (env, status),
    INDEX idx_created_at  (created_at),
    INDEX idx_trigger     (trigger_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='DAO 索引巡检任务表';


-- ═══════════════════════════════════════════════════════════════════════════
-- 2. dii_analysis_item —— SQL 明细表（规则 + EXPLAIN + LLM 完整版）
-- ═══════════════════════════════════════════════════════════════════════════

DROP TABLE IF EXISTS dii_analysis_item;

CREATE TABLE dii_analysis_item (
    id                     BIGINT       AUTO_INCREMENT PRIMARY KEY,

    -- ─────────── SQL 身份 ───────────
    sql_hash               VARCHAR(64)  NOT NULL               COMMENT 'SHA-256(规范化SQL)，幂等判重 key 主成分',
    sql_text               TEXT         NOT NULL               COMMENT '原始 SQL（保留 #xxx# 占位符）',
    sql_kind               VARCHAR(16)  NOT NULL               COMMENT 'SELECT / UPDATE / DELETE / INSERT_VALUES / INSERT_SELECT / UNKNOWN',
    env                    VARCHAR(16)  NOT NULL               COMMENT '目标库环境：dev / sit / uat',

    -- ─────────── 批量任务关联 ───────────
    task_id                BIGINT                              COMMENT '批量任务ID，单条分析为 NULL',

    -- ─────────── SQL 来源（batch 扫描时填充，单条分析可空）───────────
    project_name           VARCHAR(200)                        COMMENT '工程名，如 ccbs-dept-bcc',
    class_fqn              VARCHAR(500)                        COMMENT 'SQL 所在 Java 类全名',
    method_name            VARCHAR(200)                        COMMENT 'SQL 所在方法名',
    source_file            VARCHAR(1000)                       COMMENT 'SQL 来源源文件路径',

    -- ─────────── 规则引擎结果 ───────────
    overall_rating         VARCHAR(16)                         COMMENT 'POOR / GOOD / EXCELLENT / NOT_APPLICABLE',
    rating_label           VARCHAR(16)                         COMMENT '差 / 良 / 优 / 不适用（前端直接展示）',
    involved_tables        VARCHAR(2000)                       COMMENT '涉及的表名，逗号分隔（便于按表筛选）',
    table_ratings_json     MEDIUMTEXT                          COMMENT '每表评级详情 JSON',
    rule_engine_elapsed_ms INT                                 COMMENT '规则引擎耗时 ms',

    -- ─────────── EXPLAIN 结果 ───────────
    explain_plan           MEDIUMTEXT                          COMMENT 'EXPLAIN (FORMAT JSON, COSTS) 原始输出',
    explain_top_cost       DOUBLE                              COMMENT 'EXPLAIN 顶层 total cost',
    explain_est_rows       BIGINT                              COMMENT 'EXPLAIN 估算返回行数',
    explain_has_seq_scan   TINYINT(1)                          COMMENT '1=存在 Seq Scan',
    explain_elapsed_ms     INT                                 COMMENT 'EXPLAIN 执行耗时 ms',
    explain_error          VARCHAR(1000)                       COMMENT 'EXPLAIN 失败原因（可含 [SCHEMA_DRIFT] 前缀）',

    -- ─────────── 运行时评级（由 EXPLAIN 派生）───────────
    runtime_rating         VARCHAR(16)                         COMMENT 'POOR / GOOD / EXCELLENT（反映优化器实际选择）',
    runtime_rating_label   VARCHAR(16)                         COMMENT '差 / 良 / 优',
    disagreement           TINYINT(1)   NOT NULL DEFAULT 0     COMMENT '1=规则与 runtime 评级不一致（DBA 重点关注）',
    disagreement_reason    VARCHAR(500)                        COMMENT '不一致原因人话说明',

    -- ─────────── 表元数据（喂 LLM 的语料）───────────
    table_stats_json       MEDIUMTEXT                          COMMENT '{table:{liveTuples,sizeBytes,sizeBucket,lastAnalyze}}',
    column_stats_json      MEDIUMTEXT                          COMMENT '{table.col:{distinct,nullFrac,skew}}（来自 pg_stats）',
    table_ddl_json         MEDIUMTEXT                          COMMENT '表字段定义 + 业务注释 JSON',

    -- ─────────── LLM 调度 ───────────
    llm_pending            TINYINT(1)   NOT NULL DEFAULT 0     COMMENT '1=待 LLM 分析（rating≤GOOD 或 disagreement 或 schema drift）',
    llm_status             VARCHAR(16)                         COMMENT 'PENDING / DONE / FAILED / SKIPPED',

    -- ─────────── LLM 产出 ───────────
    llm_summary            VARCHAR(500)                        COMMENT 'LLM 一句话摘要',
    llm_findings_json      MEDIUMTEXT                          COMMENT '发现问题列表 JSON：[{type,severity,description,evidence}]',
    llm_suggestions_json   MEDIUMTEXT                          COMMENT '建议列表 JSON：[{scope(TABLE/SQL),type,ddl|newSql,reason,priority,...}]',
    llm_confidence         VARCHAR(16)                         COMMENT 'HIGH / MEDIUM / LOW',

    -- ─────────── LLM 调用元数据 ───────────
    llm_model              VARCHAR(64)                         COMMENT '使用的模型名，如 glm-47',
    llm_prompt_version     VARCHAR(32)                         COMMENT 'Prompt 模板版本号（便于 A/B 测试）',
    llm_elapsed_ms         INT                                 COMMENT 'LLM 调用耗时 ms',
    llm_error              VARCHAR(1000)                       COMMENT 'LLM 调用失败原因',
    llm_called_at          DATETIME                            COMMENT 'LLM 调用时间（失败重试会被覆盖）',

    -- ─────────── 总体状态 + 时间戳 ───────────
    status                 VARCHAR(16)  NOT NULL DEFAULT 'DONE'
                                                              COMMENT 'PENDING / RUNNING / DONE / FAILED / SKIPPED',
    error_msg              VARCHAR(1000)                       COMMENT '规则/落库阶段失败原因',
    warnings_json          VARCHAR(2000)                       COMMENT '警告列表 JSON',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- ═══ 索引设计 ═══
    --
    -- 5 分钟并发复用窗口查询 + 幂等校验核心索引
    INDEX idx_hash_env_time         (sql_hash, env, created_at),
    -- 按任务查
    INDEX idx_task                  (task_id),
    -- 按评级筛：POOR / GOOD 清单
    INDEX idx_rating_env            (overall_rating, env),
    -- 按时间查
    INDEX idx_env_created           (env, created_at),
    -- 按状态查：找失败 / 待处理
    INDEX idx_status                (status),
    -- 按工程查
    INDEX idx_project               (project_name),
    -- DBA 重点关注：规则 vs runtime 分歧
    INDEX idx_disagreement          (disagreement, env),
    -- LLM 批量调度核心索引：拉 pending 清单
    INDEX idx_llm_pending           (llm_pending, llm_status, env),
    -- 按任务 + LLM 状态查：重跑失败用
    INDEX idx_task_llm_status       (task_id, llm_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='DAO 索引巡检 SQL 明细表（规则 + EXPLAIN + LLM 完整版）';


-- ═══════════════════════════════════════════════════════════════════════════
-- 3. 如果你们用 Flyway 管 schema，同步更新 flyway_schema_history 基线
--    （让 Flyway 不再尝试跑 V6~V9 migration）
-- ═══════════════════════════════════════════════════════════════════════════

-- 只在第一次从头建时执行（如果 flyway_schema_history 里已经有 V6~V9 记录请跳过）
-- INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
-- VALUES
--   (100, '9.0.1', 'dii-schema-full (合并 V6~V9)', 'SQL', 'dii-schema-full.sql', NULL, 'manual', NOW(), 0, 1);
