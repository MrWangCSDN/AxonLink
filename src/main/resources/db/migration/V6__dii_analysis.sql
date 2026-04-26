-- ─────────────────────────────────────────────────────────────────────────────
-- V6：DAO 索引巡检模块结果表（dii = dao-index-inspection）
-- dii_analysis_task：每次巡检任务主记录
-- dii_analysis_item：每条 SQL 的巡检明细
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS dii_analysis_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_no         VARCHAR(64)   NOT NULL                        COMMENT '任务编号，格式 DII-yyyyMMddHHmmss-{env}',
    env             VARCHAR(16)   NOT NULL                        COMMENT '巡检目标环境：dev / sit / uat',
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING'      COMMENT '任务状态：PENDING / RUNNING / DONE / FAILED',
    total_sqls      INT           NOT NULL DEFAULT 0              COMMENT '本次任务扫描到的 SQL 总数',
    analyzed_sqls   INT           NOT NULL DEFAULT 0              COMMENT '已完成分析的 SQL 数',
    failed_sqls     INT           NOT NULL DEFAULT 0              COMMENT '分析失败的 SQL 数',
    skipped_sqls    INT           NOT NULL DEFAULT 0              COMMENT '因幂等窗口跳过的 SQL 数',
    trigger_type    VARCHAR(16)   NOT NULL DEFAULT 'MANUAL'       COMMENT '触发方式：MANUAL / SCHEDULED',
    error_msg       TEXT                                          COMMENT '任务级失败原因（任务本身异常时填写）',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_env_status (env, status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DAO 索引巡检任务表';


CREATE TABLE IF NOT EXISTS dii_analysis_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT        NOT NULL                        COMMENT '所属任务 ID，关联 dii_analysis_task.id',
    sql_hash        VARCHAR(64)   NOT NULL                        COMMENT 'SQL 文本的 SHA-256 哈希（用于幂等去重）',
    sql_text        TEXT          NOT NULL                        COMMENT '原始 SQL 文本',
    env             VARCHAR(16)   NOT NULL                        COMMENT '执行环境：dev / sit / uat',
    class_fqn       VARCHAR(500)                                  COMMENT 'SQL 来源类全名（@Statement 所在类）',
    method_name     VARCHAR(200)                                  COMMENT 'SQL 来源方法名',
    source_file     VARCHAR(1000)                                 COMMENT 'SQL 来源源文件路径',
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING'      COMMENT '明细状态：PENDING / OK / FAILED / SKIPPED',
    explain_plan    MEDIUMTEXT                                    COMMENT 'EXPLAIN 原始输出（JSON）',
    llm_result      MEDIUMTEXT                                    COMMENT 'LLM 解读结果（JSON）',
    risk_level      VARCHAR(8)                                    COMMENT '风险级别：HIGH / MEDIUM / LOW / INFO',
    error_msg       VARCHAR(1000)                                 COMMENT '明细级失败原因',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_task_id   (task_id),
    INDEX idx_sql_hash  (sql_hash, env),
    INDEX idx_status    (status),
    INDEX idx_risk      (risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DAO 索引巡检 SQL 明细表';
