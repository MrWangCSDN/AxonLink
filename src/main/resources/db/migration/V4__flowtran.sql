-- ─────────────────────────────────────────────────────────────────────────────
-- V4：从 flowtran 表驱动业务领域交易
-- 与 benchmarkdb.flowtran 结构保持一致（便于后续数据同步/视图）
-- ─────────────────────────────────────────────────────────────────────────────

-- 交易信息表（权威数据源，结构对齐 sunline-benchmark flowtran）
CREATE TABLE IF NOT EXISTS flowtran (
    id           VARCHAR(100) NOT NULL    COMMENT '交易ID，格式 pbf_{交易编码}_{领域缩写}，如 pbf_TD0101_dept',
    longname     VARCHAR(500)             COMMENT '交易名称（中文）',
    package_path VARCHAR(500)             COMMENT '包路径，含领域信息，如 cn.sunline.ltts.busi.dept.aps.*',
    txn_mode     VARCHAR(50)              COMMENT '事务模式，如 online / batch',
    from_jar     VARCHAR(100)             COMMENT '来源jar包/子工程名称',
    domain_key   VARCHAR(20)              COMMENT '推断出的领域标识：dept/loan/sett/comm/unvr/aggr/inbu/medu/stmt/ap',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP     COMMENT '创建时间',
    update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_domain_key (domain_key),
    INDEX idx_from_jar   (from_jar)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易信息表（来源：benchmarkdb flowtran）';

-- 流程步骤表（交易 → 服务/构件调用链路）
CREATE TABLE IF NOT EXISTS flow_step (
    id            BIGINT AUTO_INCREMENT    COMMENT '主键',
    flow_id       VARCHAR(100) NOT NULL    COMMENT '所属交易ID，关联 flowtran.id',
    node_name     VARCHAR(500)             COMMENT '节点名称（服务/构件编码）',
    node_type     VARCHAR(50)              COMMENT '节点类型：service / method / component',
    step          INT                      COMMENT '步骤顺序（升序展示）',
    node_longname VARCHAR(500)             COMMENT '节点中文名称',
    file_name     VARCHAR(500)             COMMENT '匹配的源文件名（service类型时）',
    file_path     VARCHAR(1000)            COMMENT '匹配的源文件绝对路径',
    file_jar_name VARCHAR(100)             COMMENT '来源jar包',
    incorrect_calls VARCHAR(1000)          COMMENT '违规调用列表，多个逗号分隔（如 pbcb,comm）',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flow_node (flow_id, node_name),
    INDEX idx_flow_id (flow_id),
    INDEX idx_step    (flow_id, step)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易流程步骤表（来源：benchmarkdb flow_step）';
