-- ─────────────────────────────────────────────────────────────────────────────
-- V17：补 code_person_tx_stat 表
--
-- 背景：CodeDashboardDao.rebuildPersonTxStat / personStats / personStatsByType
-- 三个方法均读写 dii_sql_pool 同库的 code_person_tx_stat 表，但 V10/V11/V12 都未
-- 建该表，生产部署后大屏报：
--   PreparedStatementCallback; bad SQL grammar [...]
--   Caused by: Table 'benchmarkdb.code_person_tx_stat' doesn't exist
--
-- 表结构按 DAO 写入字段反推：
--   (repo_id, author_email, person_name, person_type, tx_id,
--    owned_lines, file_count, snapshot_commit, snapshot_time)
-- 主键：自增 id
-- 索引：repo_id + author_email（personStats LEFT JOIN 主键）；
--      repo_id + person_type（行员/厂商分榜）
--
-- 注意：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS code_person_tx_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    author_email     VARCHAR(255) NOT NULL            COMMENT 'git author email',
    person_name      VARCHAR(128)                     COMMENT '别名优先，否则 git author name',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR',
    tx_id            VARCHAR(64)  NOT NULL            COMMENT '交易号',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '该人在该交易上的 blame 存活行合计',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '该人在该交易上的文件数（distinct）',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_cpts_repo_author (repo_id, author_email),
    KEY idx_cpts_repo_type   (repo_id, person_type),
    KEY idx_cpts_repo_tx     (repo_id, tx_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='人员维度：仓库×作者×交易 提交聚合（来源 code_file_author_stat ⋈ code_tx_file_map）';
