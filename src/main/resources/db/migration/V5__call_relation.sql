-- ─────────────────────────────────────────────────────────────────────────────
-- V5：调用关系表（call_relation）
-- 类型值域：pbf(交易) / pcs / pbs / pbcb / pbcp / pbcc / pbct / bcc(表DAO)
-- 与 sunline-benchmark 表结构完全一致
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS call_relation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,

    caller_id           VARCHAR(150) NOT NULL    COMMENT '调用方ID（service/component/flowtran 的 id）',
    caller_type         VARCHAR(20)  NOT NULL    COMMENT '调用方类型：pbf/pcs/pbs/pbcb/pbcp/pbcc/pbct',
    caller_method       VARCHAR(150)             COMMENT '调用方骨架方法名（Java 方法英文名）',
    caller_longname     VARCHAR(500)             COMMENT '调用方中文名称（来自 service/component/flowtran.longname）',
    caller_domain       VARCHAR(20)              COMMENT '调用方领域：comm/dept/sett/loan',
    caller_service_id   VARCHAR(200)             COMMENT '调用方方法级 service_id（来自 service_detail/component_detail）',

    callee_id           VARCHAR(150) NOT NULL    COMMENT '被调用方ID（getInstance 类名或 KxxxDao 名称）',
    callee_type         VARCHAR(20)  NOT NULL    COMMENT '被调用方类型：pcs/pbs/pbcb/pbcp/pbcc/pbct/bcc',
    callee_method       VARCHAR(150)             COMMENT '被调用方方法名',
    callee_longname     VARCHAR(500)             COMMENT '被调用方中文名称（bcc 暂不填）',
    callee_domain       VARCHAR(20)              COMMENT '被调用方领域',
    callee_class        VARCHAR(200)             COMMENT '被调用方原始 Java 类名',
    callee_service_id   VARCHAR(200)             COMMENT '被调用方 service_id（来自 detail 表 map 匹配）',
    callee_service_name VARCHAR(200)             COMMENT '被调用方 service_name（来自 detail 表 map 匹配）',

    from_jar            VARCHAR(200)             COMMENT '来源工程名称',
    is_direct           TINYINT DEFAULT 1        COMMENT '1=直接调用 0=间接调用',
    cross_domain        TINYINT DEFAULT 0        COMMENT '0=同域调用 1=跨域调用',
    rule_violation      TINYINT DEFAULT 0        COMMENT '0=合规 1=违反分层或跨域规则',
    violation_desc      VARCHAR(500)             COMMENT '违规描述（合规时为 NULL）',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_relation (caller_id, caller_type, caller_method, callee_id, callee_type, callee_method),
    INDEX idx_caller (caller_id, caller_type),
    INDEX idx_callee (callee_id, callee_type),
    INDEX idx_violation (rule_violation),
    INDEX idx_cross_domain (cross_domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用关系表';
