-- ═════════════════════════════════════════════════════════════════════════════
-- 源码提交分析 —— 全量建表 DDL（MySQL / benchmarkdb）
--
-- 目标库：dao-index-analysis.result-datasource 指向的 MySQL（与 dii_* 同库）。
-- 应用方式：本工程未接 Flyway 运行时，需 DBA / 部署脚本手工执行本文件。
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 内容 = db/migration 下 V10 + V11 + V12 的合并整理（列名与 Java DAO 严格一致）。
--
-- 应用顺序：先本文件建表；重置数据见同目录 02_clear_data.sql。
-- ═════════════════════════════════════════════════════════════════════════════

-- ── L0 采集层 ──────────────────────────────────────────────────────────────

-- 代码仓库接入配置（本地扫描行 enabled=0，定时任务不挑）
CREATE TABLE IF NOT EXISTS code_repo_config (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    repo_name         VARCHAR(128) NOT NULL                COMMENT '仓库展示名',
    repo_url          VARCHAR(512) NOT NULL                COMMENT 'clone 地址；本地扫描存 local:<path>',
    branch            VARCHAR(128)                         COMMENT '分析分支，空=master',
    local_path        VARCHAR(512)                         COMMENT '本地工作副本/扫描目录',
    credential_ref    VARCHAR(128)                         COMMENT '存放访问 token 的环境变量名（凭证不入库）',
    include_exts      VARCHAR(256)                         COMMENT '逗号分隔纳入扩展名白名单，空=全部',
    exclude_paths     VARCHAR(512)                         COMMENT '逗号分隔排除路径前缀',
    last_sync_commit  VARCHAR(64)                          COMMENT '上次成功同步 HEAD sha；增量基线',
    last_sync_time    DATETIME                             COMMENT '上次同步时间',
    last_sync_status  VARCHAR(16)                          COMMENT 'SUCCESS / NO_CHANGE / FAILED',
    enabled           TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '1=参与定时分析；本地扫描行恒 0',
    create_time       DATETIME              DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_repo_name (repo_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='源码仓库接入配置';

-- 文件×作者 提交事实（真相源；每次同步对变更文件全量替换）
CREATE TABLE IF NOT EXISTS code_file_author_stat (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id           BIGINT        NOT NULL               COMMENT 'code_repo_config.id',
    file_path         VARCHAR(768)  NOT NULL               COMMENT '仓库相对路径（git ls-files 原样，正斜杠）',
    author_name       VARCHAR(255)                         COMMENT 'git author name',
    author_email      VARCHAR(255)  NOT NULL               COMMENT 'git author email（作者口径主键）',
    user_id           BIGINT                               COMMENT 'Phase 3 别名映射，本期恒 NULL',
    owned_lines       INT           NOT NULL DEFAULT 0     COMMENT '主指标分子：blame 存活归属行数',
    file_total_lines  INT           NOT NULL DEFAULT 0     COMMENT '主指标分母：文件 HEAD 总行数',
    added_lines       INT           NOT NULL DEFAULT 0     COMMENT '辅助：numstat 累计新增',
    deleted_lines     INT           NOT NULL DEFAULT 0     COMMENT '辅助：numstat 累计删除',
    commit_count      INT           NOT NULL DEFAULT 0     COMMENT '辅助：该作者对该文件提交数',
    first_commit_time DATETIME                             COMMENT '最早提交时间',
    last_commit_time  DATETIME                             COMMENT '最近提交时间',
    snapshot_commit   VARCHAR(64)                          COMMENT '本行计算所基于的提交 sha',
    snapshot_time     DATETIME                             COMMENT '本行计算时间',
    PRIMARY KEY (id),
    KEY idx_cfas_repo_path (repo_id, file_path(191)),
    KEY idx_cfas_repo_email (repo_id, author_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件×作者 提交事实';

-- ── 身份层 ─────────────────────────────────────────────────────────────────

-- 作者身份人工覆盖（email→人员/类型；c-/t- 规则误判时单点修正）
CREATE TABLE IF NOT EXISTS code_author_alias (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    email        VARCHAR(255) NOT NULL                COMMENT 'git author email（匹配键）',
    person_name  VARCHAR(128)                         COMMENT '归一人员名（覆盖 git author name）',
    person_type  VARCHAR(8)                           COMMENT '覆盖分类 STAFF/VENDOR；空=用 c-/t- 规则',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '0=该覆盖暂不生效',
    create_time  DATETIME              DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_alias_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作者身份人工覆盖（行员/厂商修正）';

-- ── L1 物化聚合层（每次采集后重建；大屏只读这些小表） ──────────────────────

-- 工程维度：仓库×作者（行员/厂商总览与 KPI 由本表再 GROUP BY 派生）
CREATE TABLE IF NOT EXISTS code_repo_author_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    author_email     VARCHAR(255) NOT NULL            COMMENT 'git author email',
    person_name      VARCHAR(128)                     COMMENT '别名覆盖优先，否则 git author name',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '主指标：blame 存活行合计',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '参与文件数（distinct file_path）',
    added_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '辅助：numstat 累计新增',
    deleted_lines    BIGINT       NOT NULL DEFAULT 0  COMMENT '辅助：numstat 累计删除',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_cras_repo (repo_id),
    KEY idx_cras_repo_type (repo_id, person_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工程维度：仓库×作者 聚合';

-- 交易→文件 映射（Phase② 物化填充；空表时交易聚合自然 no-op）
CREATE TABLE IF NOT EXISTS code_tx_file_map (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT        NOT NULL           COMMENT 'code_repo_config.id',
    tx_id            VARCHAR(64)   NOT NULL           COMMENT '交易号',
    file_path        VARCHAR(768)  NOT NULL           COMMENT '仓库相对路径（与 code_file_author_stat 同键）',
    snapshot_commit  VARCHAR(64)                      COMMENT '映射所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '映射生成时间',
    PRIMARY KEY (id),
    KEY idx_ctfm_repo_tx (repo_id, tx_id),
    KEY idx_ctfm_repo_path (repo_id, file_path(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易→文件 映射（Phase② 填充）';

-- 交易维度：仓库×交易×person_type
CREATE TABLE IF NOT EXISTS code_tx_person_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    tx_id            VARCHAR(64)  NOT NULL            COMMENT '交易号',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '该交易该类人员 blame 存活行合计',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '该交易关联文件数（distinct file_path）',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_ctps_repo_tx (repo_id, tx_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易维度：仓库×交易×行员/厂商 聚合';

-- 文件→领域 映射（路径纯函数推导，DomainKeyResolver 口径，无 Neo4j）
CREATE TABLE IF NOT EXISTS code_file_domain (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT        NOT NULL           COMMENT 'code_repo_config.id',
    file_path        VARCHAR(768)  NOT NULL           COMMENT '仓库相对路径（与 code_file_author_stat 同键，天然对齐）',
    domain_key       VARCHAR(32)   NOT NULL           COMMENT '领域标识，识别不了为 public',
    snapshot_commit  VARCHAR(64)                      COMMENT '映射所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '映射生成时间',
    PRIMARY KEY (id),
    KEY idx_cfd_repo_path (repo_id, file_path(191)),
    KEY idx_cfd_repo_domain (repo_id, domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件→领域 映射';

-- 领域维度：仓库×领域×person_type
CREATE TABLE IF NOT EXISTS code_domain_person_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    domain_key       VARCHAR(32)  NOT NULL            COMMENT '领域标识',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '该领域该类人员 blame 存活行合计',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '该领域该类人员参与文件数',
    added_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '辅助：numstat 累计新增',
    deleted_lines    BIGINT       NOT NULL DEFAULT 0  COMMENT '辅助：numstat 累计删除',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_cdps_repo (repo_id),
    KEY idx_cdps_repo_domain (repo_id, domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域维度：仓库×领域×行员/厂商 聚合';

-- 人员×交易 归属明细（来源：code_tx_file_map ⋈ code_file_author_stat；flowtrans XML 驱动）
-- 口径：person 在该交易 *.flowtrans.xml 文件中有 blame 存活行即归属该交易
CREATE TABLE IF NOT EXISTS code_person_tx_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    author_email     VARCHAR(255) NOT NULL            COMMENT 'git author email',
    person_name      VARCHAR(128)                     COMMENT '别名覆盖优先，否则 git author name',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR',
    tx_id            VARCHAR(64)  NOT NULL            COMMENT '交易码（来自 *.flowtrans.xml 根元素 id 属性）',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '该人在该交易 flowtrans XML 的 blame 存活行',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '关联文件数（通常=1）',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_cpts_repo_email (repo_id, author_email),
    KEY idx_cpts_repo_tx    (repo_id, tx_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人员×交易 归属明细（flowtrans XML 维度）';

-- 领域内作者明细（下钻：某领域谁掌握得多）
CREATE TABLE IF NOT EXISTS code_domain_author_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    domain_key       VARCHAR(32)  NOT NULL            COMMENT '领域标识',
    author_email     VARCHAR(255) NOT NULL            COMMENT 'git author email',
    person_name      VARCHAR(128)                     COMMENT '别名覆盖优先，否则 git author name',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '该作者在该领域 blame 存活行合计',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '该作者在该领域参与文件数',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_cdas_repo_domain (repo_id, domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域内作者明细';

-- 仓库每日代码行数快照（折线图数据源）
CREATE TABLE IF NOT EXISTS ccbs_ai_code_repo_daily_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    stat_date        DATE         NOT NULL            COMMENT '统计日期',
    total_owned_lines BIGINT      NOT NULL DEFAULT 0  COMMENT '当日总存活行数',
    staff_owned_lines BIGINT      NOT NULL DEFAULT 0  COMMENT '行员存活行数',
    vendor_owned_lines BIGINT      NOT NULL DEFAULT 0  COMMENT '厂商存活行数',
    author_count     INT          NOT NULL DEFAULT 0  COMMENT '当日作者数',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '当日跟踪文件数',
    snapshot_commit  VARCHAR(64)                      COMMENT '快照所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '快照生成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ccbs_ai_code_rds_repo_date (repo_id, stat_date),
    KEY idx_ccbs_ai_code_rds_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库每日代码行数快照（ccbs-ai 模块）';
