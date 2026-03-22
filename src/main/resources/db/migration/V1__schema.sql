-- 交易链路分析平台 数据库建表 SQL
-- MySQL 8.x
-- ─────────────────────────────────────────────
-- 领域表
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_domain (
    id          BIGINT       NOT NULL COMMENT '主键',
    domain_key  VARCHAR(32)  NOT NULL COMMENT '领域标识：public/loan/deposit/settlement',
    name        VARCHAR(64)  NOT NULL COMMENT '领域名称',
    icon        VARCHAR(32)  COMMENT '图标标识',
    sort_order  INT          DEFAULT 0 COMMENT '排序号',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    deleted     TINYINT(1)   DEFAULT 0 COMMENT '逻辑删除 0正常 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_domain_key (domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务领域';

-- ─────────────────────────────────────────────
-- 交易表
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_transaction (
    id          BIGINT       NOT NULL COMMENT '主键',
    tx_code     VARCHAR(16)  NOT NULL COMMENT '交易编码，如 TD0101',
    name        VARCHAR(64)  NOT NULL COMMENT '交易名称',
    domain_id   BIGINT       NOT NULL COMMENT '所属领域 id',
    layers      INT          DEFAULT 4 COMMENT '链路层数',
    sort_order  INT          DEFAULT 0 COMMENT '排序号',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    deleted     TINYINT(1)   DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tx_code (tx_code),
    KEY idx_domain_id (domain_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易';

-- ─────────────────────────────────────────────
-- 服务节点表
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_service_node (
    id           BIGINT      NOT NULL,
    tx_id        BIGINT      NOT NULL COMMENT '所属交易 id',
    prefix       VARCHAR(8)  NOT NULL COMMENT '服务类型：pbs/pcs',
    service_code VARCHAR(32) NOT NULL COMMENT '服务编码，如 SVC_AUTH_001',
    name         VARCHAR(64) NOT NULL COMMENT '服务名称',
    cross_domain VARCHAR(32) COMMENT '跨领域来源',
    sort_order   INT         DEFAULT 0,
    create_time  DATETIME    NOT NULL,
    deleted      TINYINT(1)  DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tx_id (tx_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务节点';

-- ─────────────────────────────────────────────
-- 构件节点表
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_component_node (
    id             BIGINT      NOT NULL,
    tx_id          BIGINT      NOT NULL COMMENT '所属交易 id',
    prefix         VARCHAR(8)  NOT NULL COMMENT '构件类型：pbcc/pbct/pbcb/pbcp',
    component_code VARCHAR(32) NOT NULL COMMENT '构件编码，如 COMP_AUTH_001',
    name           VARCHAR(64) NOT NULL COMMENT '构件名称',
    cross_domain   VARCHAR(32) COMMENT '跨领域来源',
    sort_order     INT         DEFAULT 0,
    create_time    DATETIME    NOT NULL,
    deleted        TINYINT(1)  DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tx_id (tx_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='构件节点';

-- ─────────────────────────────────────────────
-- 数据表节点
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_data_table_node (
    id          BIGINT      NOT NULL,
    tx_id       BIGINT      NOT NULL COMMENT '所属交易 id',
    table_code  VARCHAR(32) NOT NULL COMMENT '表名，如 USR_INFO',
    name        VARCHAR(64) NOT NULL COMMENT '显示名',
    sort_order  INT         DEFAULT 0,
    create_time DATETIME    NOT NULL,
    deleted     TINYINT(1)  DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tx_id (tx_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据表节点';

-- ─────────────────────────────────────────────
-- 通用关联关系表
-- relation_type 枚举值：
--   SERVICE_TO_SERVICE      pcs → pbs
--   SERVICE_TO_COMPONENT    pbs → 构件
--   COMPONENT_TO_COMPONENT  pbcb/pbcp → pbcc/pbct
--   COMPONENT_TO_DATA       构件 → 数据表
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_relation (
    id            BIGINT      NOT NULL,
    tx_id         BIGINT      NOT NULL COMMENT '所属交易 id',
    relation_type VARCHAR(32) NOT NULL COMMENT '关系类型',
    from_code     VARCHAR(32) NOT NULL COMMENT '来源编码',
    to_code       VARCHAR(32) NOT NULL COMMENT '目标编码',
    create_time   DATETIME    NOT NULL,
    deleted       TINYINT(1)  DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tx_id_type (tx_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='层间关联关系';
