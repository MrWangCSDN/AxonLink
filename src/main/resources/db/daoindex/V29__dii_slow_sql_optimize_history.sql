-- 慢SQL「优化路线」：每次打标追加一条，永不覆盖——审计"谁在哪轮标了已优化、内容是什么、
-- 该次优化是否未生效(又现于哪轮)"。真身表 dii_slow_sql_optimization 仍是"当前态"，本表是全历史。
CREATE TABLE dii_slow_sql_optimize_history (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    service_name      VARCHAR(128) NOT NULL          COMMENT '微服务名',
    abstract_hash     CHAR(64)     NOT NULL          COMMENT '抽象SQL SHA-256',
    optimized_round   VARCHAR(20)  NOT NULL          COMMENT '该次优化锚定轮次 R0',
    optimized_by      VARCHAR(100)                   COMMENT '优化人工号',
    optimized_by_name VARCHAR(64)                    COMMENT '优化人姓名',
    optimize_note     VARCHAR(200)                   COMMENT '优化内容',
    optimized_at      DATETIME     NOT NULL          COMMENT '打标时间',
    reappeared_round  VARCHAR(20)  DEFAULT NULL      COMMENT '该次优化未生效——之后又出现的最新轮次(为空=暂未失效)',
    KEY idx_svc_hash (service_name, abstract_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢SQL优化路线(追加不删)';

-- 回填：存量真身记录作为各 SQL 的第一条路线（含已回填的未生效轮次）
INSERT INTO dii_slow_sql_optimize_history
  (service_name, abstract_hash, optimized_round, optimized_by, optimized_by_name,
   optimize_note, optimized_at, reappeared_round)
SELECT service_name, abstract_hash, optimized_round, optimized_by, optimized_by_name,
       optimize_note, optimized_at, reappeared_round
  FROM dii_slow_sql_optimization;
