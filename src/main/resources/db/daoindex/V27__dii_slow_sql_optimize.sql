-- 慢SQL「已优化」状态 + 跨轮次「优化未生效」检测
-- 真身表：跨轮次身份键 (service_name, abstract_hash)，整轮 DELETE→INSERT 不影响它。
CREATE TABLE dii_slow_sql_optimization (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    service_name     VARCHAR(128) NOT NULL          COMMENT '微服务名（跨轮次身份键之一）',
    abstract_hash    CHAR(64)     NOT NULL          COMMENT '抽象SQL SHA-256（跨轮次身份键之一）',
    status           VARCHAR(20)  NOT NULL          COMMENT 'OPTIMIZED 已优化 / REGRESSED 优化未生效',
    optimized_round  VARCHAR(20)  NOT NULL          COMMENT '锚定轮次 R0 = 打标时该SQL最新出现的轮次 MAX(round)',
    reappeared_round VARCHAR(20)                    COMMENT '未生效时又出现的最新轮次（status=REGRESSED 才有值）',
    optimized_by     VARCHAR(100)                   COMMENT '打标人（当前登录用户，留痕，无审批）',
    optimized_at     DATETIME     NOT NULL          COMMENT '首次打标时间',
    updated_at       DATETIME     NOT NULL          COMMENT '最近状态变更时间（含被导入翻 REGRESSED）',
    UNIQUE KEY uk_svc_hash (service_name, abstract_hash),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢SQL已优化标记真身表（跨轮次，不随整轮导入清空）';

-- dii_slow_sql 加 3 冗余列（列表直读；每次导入后由继承钩子重新盖回）
ALTER TABLE dii_slow_sql
    ADD COLUMN optimize_status  VARCHAR(20)  DEFAULT NULL COMMENT 'OPTIMIZED / REGRESSED / NULL(未处理)',
    ADD COLUMN optimized_round  VARCHAR(20)  DEFAULT NULL COMMENT '锚定轮次 R0（冗余自真身表）',
    ADD COLUMN reappeared_round VARCHAR(20)  DEFAULT NULL COMMENT '未生效时又出现的最新轮次（冗余自真身表）';
