-- ─────────────────────────────────────────────────────────────────────────────
-- V23：慢SQL 采集过滤名单
--
-- 导入慢SQL时，抽象SQL 以本表任一前缀开头（大小写不敏感、trim 后比对）→ 不纳入采集。
-- 配置增删受导入口令（X-DII-Trigger-Token）保护；预置 EXPLAIN / SET 两条（可删）。
-- 注意：与审批白名单（dii_whitelist_application）无关——这是"采集排除"规则。
--
-- 本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS dii_slow_sql_collect_filter (
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    prefix     VARCHAR(64) NOT NULL COMMENT '抽象SQL前缀（大小写不敏感匹配），如 EXPLAIN / SET',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_slow_filter_prefix (prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢SQL采集过滤名单：以这些前缀开头的抽象SQL不纳入采集';

-- 预置（幂等）：运维慢SQL导出里混入的 EXPLAIN 自身与 SET 会话语句，默认不采集
INSERT IGNORE INTO dii_slow_sql_collect_filter (prefix) VALUES ('EXPLAIN'), ('SET');
