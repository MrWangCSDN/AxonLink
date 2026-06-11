-- ─────────────────────────────────────────────────────────────────────────────
-- V22：慢SQL维度分析 v2 —— 重建 dii_slow_sql 为「按 (轮次, 微服务, 抽象SQL哈希) 聚合行」
--
-- 与 V20 逐行语义不兼容（导入时聚合：组内最大耗时 + 出现次数 + 重复出现轮次），
-- 旧逐行数据丢弃；用新 5 列 CSV 重新导入即可恢复。白名单申请表不动，
-- 重导后按 abstract_hash 自动继承。
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

DROP TABLE IF EXISTS dii_slow_sql;

CREATE TABLE dii_slow_sql (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    service_name      VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'A列 微服务应用名，如 ccbs-dept-online',
    domain            VARCHAR(16)  NOT NULL DEFAULT '其他' COMMENT 'E列解析领域：存款/贷款/公共/结算/全领域/平台/其他',
    biz_type          VARCHAR(16)  NOT NULL DEFAULT '其他' COMMENT '应用名后缀派生：联机/批量/热点账户/其他',
    abstract_sql      MEDIUMTEXT   NOT NULL            COMMENT 'B列 抽象SQL（带 ? 占位）',
    abstract_hash     CHAR(64)     NOT NULL            COMMENT 'SHA-256(trim(abstract_sql))，匹配白名单+聚合键',
    max_time_cost_ms  BIGINT       NOT NULL DEFAULT 0  COMMENT '组内最大执行耗时(ms)',
    max_time_cost_raw VARCHAR(32)                      COMMENT '最大耗时原始文本，如 15,538ms',
    exec_params       TEXT                             COMMENT '最大耗时那行的 C列 执行参数',
    source_location   VARCHAR(512)                     COMMENT '最大耗时那行的 E列 来源文件',
    exec_count        INT          NOT NULL DEFAULT 1  COMMENT '本轮该 (微服务+抽象SQL) 出现次数',
    round             VARCHAR(20)  NOT NULL            COMMENT '导入时手输轮次，如 20260103-20260107',
    repeat_rounds     TEXT                             COMMENT '历史出现轮次清单（逗号分隔升序），空=首次出现',
    whitelist_app_id  BIGINT                           COMMENT '关联 dii_whitelist_application.id',
    whitelist_status  VARCHAR(20)                      COMMENT '冗余审批状态供列表渲染',
    is_whitelist      TINYINT      NOT NULL DEFAULT 0  COMMENT 'APPROVED→1',
    imported_at       DATETIME     NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_round_svc_hash (round, service_name, abstract_hash),
    INDEX idx_slow_hash   (abstract_hash),
    INDEX idx_slow_round  (round),
    INDEX idx_slow_domain (domain),
    INDEX idx_slow_biztype(biz_type),
    INDEX idx_slow_wl     (whitelist_app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢SQL v2：按(轮次,微服务,抽象SQL哈希)聚合行';
