-- ─────────────────────────────────────────────────────────────────────────────
-- V12：项目级 按领域划分 视图（Phase 8 / 08-02 领域维度，避开 Phase② 交易路径对齐）
--
-- 口径（2026-05-19 用户拍板）：
--   - 领域复用 com.axonlink.common.DomainKeyResolver 原样（与 flowtran/DomainSidebar 同口径）：
--     工程名优先（ccbs-loan-* → loan），否则包路径第 4 段经 DOMAIN_MAP 兜底，识别不了 → public
--   - file→domain 由 Java 步对 code_file_author_stat 的 DISTINCT file_path 纯函数算出，
--     join 键 = 事实表自身 file_path，天然对齐，无 Phase② 路径对齐风险
--   - 身份分类沿用 c-/t- 规则 + code_author_alias 覆盖
--
-- ⚠️ Flyway 未接入，需 DBA/部署脚本手工应用到 benchmarkdb（dao-index-analysis.result-datasource）。
-- ─────────────────────────────────────────────────────────────────────────────

-- 文件→领域 映射（每仓库 DISTINCT file_path 一行；每日采集后重建）。
CREATE TABLE IF NOT EXISTS code_file_domain (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT        NOT NULL           COMMENT 'code_repo_config.id',
    file_path        VARCHAR(768)  NOT NULL           COMMENT '仓库相对路径（与 code_file_author_stat 同键，天然对齐）',
    domain_key       VARCHAR(32)   NOT NULL           COMMENT 'DomainKeyResolver 解析结果，识别不了为 public',
    snapshot_commit  VARCHAR(64)                      COMMENT '映射所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '映射生成时间',
    PRIMARY KEY (id),
    KEY idx_cfd_repo_path (repo_id, file_path(191)),
    KEY idx_cfd_repo_domain (repo_id, domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件→领域 映射（路径纯函数推导，无 Neo4j）';

-- 领域维度 summary：仓库 × 领域 × person_type。
CREATE TABLE IF NOT EXISTS code_domain_person_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    domain_key       VARCHAR(32)  NOT NULL            COMMENT '领域标识',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '该领域该类人员 blame 存活行合计',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '该领域该类人员参与文件数（distinct file_path）',
    added_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '辅助：numstat 累计新增',
    deleted_lines    BIGINT       NOT NULL DEFAULT 0  COMMENT '辅助：numstat 累计删除',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_cdps_repo (repo_id),
    KEY idx_cdps_repo_domain (repo_id, domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域维度：仓库×领域×行员/厂商 聚合（每日快照重建）';

-- 领域内作者明细（下钻：某领域里谁掌握得多）。
CREATE TABLE IF NOT EXISTS code_domain_author_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    domain_key       VARCHAR(32)  NOT NULL            COMMENT '领域标识',
    author_email     VARCHAR(255) NOT NULL            COMMENT 'git author email',
    person_name      VARCHAR(128)                     COMMENT '别名覆盖优先，否则 git author name',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '该作者在该领域的 blame 存活行合计',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '该作者在该领域参与文件数',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_cdas_repo_domain (repo_id, domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域内作者明细（下钻：领域×作者掌握度）';
