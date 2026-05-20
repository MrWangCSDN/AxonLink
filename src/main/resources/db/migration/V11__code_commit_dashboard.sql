-- ─────────────────────────────────────────────────────────────────────────────
-- V11：源码提交分析大屏 —— 身份分类 + 预聚合 summary 层（Phase 8 / 08-02 起步）
--
-- 架构口径（2026-05-19 用户拍板）：
--   - L0 事实表 code_file_author_stat 仍是真相源、保持纯采集不变
--   - 大屏不 compute-on-read：采集完成后追加物化聚合，落本文件这几张小 summary 表
--   - 每日快照：聚合挂每日采集 cron 之后
--   - 身份分类：email @ 前本地部分以 c- 或 t- 开头(不分大小写) → 厂商 VENDOR；否则 → 行员 STAFF
--     纯二分无第三档；code_author_alias 可对单 email 覆盖人员与类型
--   - v1 维度 = 工程(repo) + 交易(tx)；工程/作者/KPI 不依赖 Phase②，交易需 code_tx_file_map
--
-- ⚠️ 本工程未接入 Flyway 运行时；本脚本需 DBA/部署脚本手工应用到结果库
--    （dao-index-analysis.result-datasource 指向的 benchmarkdb / MySQL）。
-- ─────────────────────────────────────────────────────────────────────────────

-- 身份覆盖表：email -> 人员/类型 人工修正（同人多邮箱、规则误判时单点覆盖）
CREATE TABLE IF NOT EXISTS code_author_alias (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    email        VARCHAR(255) NOT NULL                COMMENT 'git author email（小写存储，匹配键）',
    person_name  VARCHAR(128)                         COMMENT '归一后人员名（覆盖 git author name）',
    person_type  VARCHAR(8)                           COMMENT '覆盖分类：STAFF/VENDOR；空=用 c-/t- 规则',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '0=该覆盖暂不生效',
    create_time  DATETIME              DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_alias_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作者身份人工覆盖（行员/厂商修正）';

-- 工程维度 summary：仓库 × 作者（含 person_type）。行员/厂商总览与 KPI 由本表再 GROUP BY 派生
-- （本表很小：作者数量级，再 rollup 廉价；不依赖 Phase②，采集后即可重建）。
CREATE TABLE IF NOT EXISTS code_repo_author_stat (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL            COMMENT 'code_repo_config.id',
    author_email     VARCHAR(255) NOT NULL            COMMENT 'git author email',
    person_name      VARCHAR(128)                     COMMENT '别名覆盖优先，否则 git author name',
    person_type      VARCHAR(8)   NOT NULL            COMMENT 'STAFF / VENDOR（别名覆盖优先，否则 c-/t- 规则）',
    owned_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '主指标：blame 存活归属行数合计',
    file_count       INT          NOT NULL DEFAULT 0  COMMENT '该作者参与的文件数（distinct file_path）',
    added_lines      BIGINT       NOT NULL DEFAULT 0  COMMENT '辅助：numstat 累计新增',
    deleted_lines    BIGINT       NOT NULL DEFAULT 0  COMMENT '辅助：numstat 累计删除',
    snapshot_commit  VARCHAR(64)                      COMMENT '聚合所基于的 HEAD',
    snapshot_time    DATETIME                         COMMENT '聚合时间',
    PRIMARY KEY (id),
    KEY idx_cras_repo (repo_id),
    KEY idx_cras_repo_type (repo_id, person_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工程维度：仓库×作者 提交聚合（每日快照重建）';

-- 交易→文件 映射（Phase② 由 FlowtranService.getChain + ProjectIndexer 物化填充）。
-- file_path 必须与 code_file_author_stat.file_path 逐字一致（仓库相对路径、正斜杠），否则 join 静默全 0。
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

-- 交易维度 summary：仓库 × 交易 × person_type（由 code_tx_file_map ⋈ 事实表聚合）。
-- 口径：一个文件被多笔交易共享时，对每笔交易各自全计（接受高估，已锁定）。
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易维度：仓库×交易×行员/厂商 聚合（Phase② 后有数据）';
