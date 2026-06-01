-- ─────────────────────────────────────────────────────────────────────────────
-- V19：表关系 ER 推断结果表 dii_er_relation
--
-- 背景：目标库多数表无物理外键约束，但存在隐式关联（A 的主键/唯一键作为 B 的普通
--   列出现）。按「键包含」启发式推断这些隐式外键，落库缓存供 ER 图呈现 + 人工修正。
--
-- 推断规则（用户定义）：
--   一张表的 PK/UK（单列或联合）的全部列，若作为普通字段出现在另一张表中，
--   且这些列不是另一张表自己的 PK/UK → 推断关系（前者=被引用 1 端，后者=引用 N 端）。
--
-- 置信度护栏（防单列通用键爆假阳性）：
--   联合键(≥2列)全命中 = HIGH；单列+独特列名(出现表数 ≤ 阈值) = MEDIUM；
--   单列+通用列名(出现表数 > 阈值 或 黑名单) = LOW（前端默认隐藏）。
--
-- 人工修正：status = AUTO/CONFIRMED/IGNORED；重算时保留 CONFIRMED/IGNORED 决策。
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS dii_er_relation (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    env             VARCHAR(16)  NOT NULL                COMMENT 'dev/sit/uat',
    from_table      VARCHAR(128) NOT NULL                COMMENT '被引用表（键拥有者，1 端）',
    to_table        VARCHAR(128) NOT NULL                COMMENT '引用表（N 端）',
    join_columns    VARCHAR(500) NOT NULL                COMMENT '关联列，逗号分隔（= from 表的键列）',
    key_type        VARCHAR(8)   NOT NULL                COMMENT 'PK / UNIQUE',
    key_col_count   INT          NOT NULL DEFAULT 1      COMMENT '键列数：1=单列，≥2=联合',
    confidence      VARCHAR(8)   NOT NULL                COMMENT 'HIGH / MEDIUM / LOW',
    status          VARCHAR(12)  NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO / CONFIRMED / IGNORED',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_er (env, from_table, to_table, join_columns),
    INDEX idx_env_from (env, from_table),
    INDEX idx_env_to   (env, to_table),
    INDEX idx_env_conf (env, confidence, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表关系 ER 推断结果（隐式外键）';
