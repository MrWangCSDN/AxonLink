-- ─────────────────────────────────────────────────────────────────────────────
-- V16：SQL 白名单审批工作流
--
-- 背景：DBA 不再手工拨 is_whitelist，改走 申请 → L1 → L2 二审工作流。
--   - 新表 dii_whitelist_application 存审批记录 + 审计
--   - item / sql_pool 各加 whitelist_app_id / whitelist_status 用于列表快速渲染
--
-- 包含两种作用域：
--   - HASH      ：按 sql_hash 精确匹配（odb 来源 + nsql 不勾选包含模式）
--   - NAMED_SQL ：按 named_sql 匹配（nsql 勾选「包含 nsql 原始语句」）
--
-- 状态机：
--   PENDING_L1 → l1Approve → PENDING_L2 → l2Approve → APPROVED（写 is_whitelist=1）
--                l1Reject  → REJECTED_L1 → 申请人重新申请 / 取消
--                            l2Reject  → PENDING_L1（退回 L1）
--                cancel    → CANCELLED （仅 PENDING_L1 / REJECTED_L1 可取消）
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS dii_whitelist_application (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,

    -- ══════════════════════════════════════════════════════════════
    -- 目标匹配
    -- ══════════════════════════════════════════════════════════════
    target_type     VARCHAR(16)  NOT NULL                COMMENT 'HASH / NAMED_SQL',
    sql_hash        VARCHAR(64)                          COMMENT 'target_type=HASH 必填；NAMED_SQL 可空',
    named_sql       VARCHAR(500)                         COMMENT '两种类型都填便于审计；NAMED_SQL 必填',
    sql_kind_source VARCHAR(16)  NOT NULL                COMMENT '发起时 SQL 来源：odb / nsql',
    sql_text        TEXT                                 COMMENT '申请时刻 SQL 快照（不参与匹配）',
    project_name    VARCHAR(200),
    env             VARCHAR(16),

    -- ══════════════════════════════════════════════════════════════
    -- 工作流
    -- ══════════════════════════════════════════════════════════════
    status          VARCHAR(20)  NOT NULL                COMMENT 'PENDING_L1 / PENDING_L2 / APPROVED / REJECTED_L1 / CANCELLED',

    -- ══════════════════════════════════════════════════════════════
    -- 申请
    -- ══════════════════════════════════════════════════════════════
    applicant       VARCHAR(100) NOT NULL                COMMENT '申请人 username（来自 SecurityContextHolder）',
    apply_reason    VARCHAR(1000),
    apply_at        DATETIME     NOT NULL,

    -- ══════════════════════════════════════════════════════════════
    -- 一级审批
    -- ══════════════════════════════════════════════════════════════
    l1_approver     VARCHAR(100) NOT NULL                COMMENT '申请时指定的一级审批人',
    l1_opinion      VARCHAR(1000),
    l1_at           DATETIME,
    l1_decision     VARCHAR(10)                          COMMENT 'APPROVE / REJECT',

    -- ══════════════════════════════════════════════════════════════
    -- 二级审批
    -- ══════════════════════════════════════════════════════════════
    l2_approver     VARCHAR(100)                         COMMENT 'L1 通过时指定',
    l2_opinion      VARCHAR(1000),
    l2_at           DATETIME,
    l2_decision     VARCHAR(10),

    -- ══════════════════════════════════════════════════════════════
    -- 来源（审计）
    -- ══════════════════════════════════════════════════════════════
    source_table    VARCHAR(20)  NOT NULL                COMMENT 'item / sql_pool',
    source_id       BIGINT       NOT NULL                COMMENT '触发申请那一行的 id',

    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_status     (status),
    INDEX idx_sql_hash   (sql_hash, status),
    INDEX idx_named_sql  (named_sql, target_type, status),
    INDEX idx_l1         (l1_approver, status),
    INDEX idx_l2         (l2_approver, status),
    INDEX idx_applicant  (applicant, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 白名单审批工作流记录';

-- ── 在 item 表加 wl 冗余字段 ──
ALTER TABLE dii_analysis_item
    ADD COLUMN whitelist_app_id BIGINT      COMMENT '关联当前活跃 dii_whitelist_application.id',
    ADD COLUMN whitelist_status VARCHAR(20) COMMENT '冗余审批状态供列表快速渲染';

CREATE INDEX idx_dii_item_wl ON dii_analysis_item (whitelist_status);

-- ── 在 sql_pool 表加同样字段 ──
ALTER TABLE dii_sql_pool
    ADD COLUMN whitelist_app_id BIGINT      COMMENT '关联当前活跃 dii_whitelist_application.id',
    ADD COLUMN whitelist_status VARCHAR(20) COMMENT '冗余审批状态供列表快速渲染';

CREATE INDEX idx_dii_pool_wl ON dii_sql_pool (whitelist_status);
