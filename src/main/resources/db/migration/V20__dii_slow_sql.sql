-- ─────────────────────────────────────────────────────────────────────────────
-- V20：慢SQL维度分析 明细表
--
-- 运维慢SQL导出(serviceName / 耗时 / 抽象SQL / 执行参数)导入；逐行保留(不去重)，
-- 因为导出要统计"出现次数 / 每轮次出现次数"。轮次 = 导入日期 yyyyMMdd-N。
--
-- 白名单：复用 dii_whitelist_application，target_type='SLOW_SQL'(VARCHAR(16) 容得下)，
-- 无需 ALTER 白名单表。本表冗余 whitelist_app_id / whitelist_status / is_whitelist。
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS dii_slow_sql (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    service_name     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '第0列 serviceName 原值留底，如 ccbs-public-batch',
    domain           VARCHAR(16)  NOT NULL DEFAULT '其他' COMMENT '由 serviceName 中段派生的中文领域：存款/贷款/公共/结算/全领域/其他',
    biz_type         VARCHAR(16)  NOT NULL DEFAULT '其他' COMMENT '由 serviceName 后缀派生的类型：联机/热点账户/批量/其他',
    time_cost_ms     BIGINT       NOT NULL DEFAULT 0  COMMENT '第1列解析出的毫秒数，取max/排序用',
    time_cost_raw    VARCHAR(32)                      COMMENT '原始耗时文本，如 20838ms',
    abstract_sql     MEDIUMTEXT   NOT NULL            COMMENT '第2列 抽象SQL（带 ? 占位）',
    abstract_hash    CHAR(64)     NOT NULL            COMMENT 'SHA-256(trim(abstract_sql))，精确匹配+索引',
    exec_params      TEXT                             COMMENT '第3列 执行参数',
    round            VARCHAR(20)  NOT NULL            COMMENT '轮次 yyyyMMdd-N',
    whitelist_app_id BIGINT                           COMMENT '关联 dii_whitelist_application.id',
    whitelist_status VARCHAR(20)                      COMMENT '冗余审批状态供列表渲染',
    is_whitelist     TINYINT      NOT NULL DEFAULT 0  COMMENT 'APPROVED→1',
    imported_at      DATETIME     NOT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_slow_hash   (abstract_hash),
    INDEX idx_slow_round  (round),
    INDEX idx_slow_domain (domain),
    INDEX idx_slow_biztype(biz_type),
    INDEX idx_slow_wl     (whitelist_app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢SQL维度分析明细（逐行，不去重）';

-- 若已用旧版 V20 建过表，补列（已有列会报错可忽略）：
-- ALTER TABLE dii_slow_sql
--   ADD COLUMN service_name VARCHAR(128) NOT NULL DEFAULT '' AFTER id,
--   ADD COLUMN biz_type     VARCHAR(16)  NOT NULL DEFAULT '其他' AFTER domain,
--   ADD INDEX idx_slow_biztype (biz_type);
