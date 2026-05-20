-- ─────────────────────────────────────────────────────────────────────────────
-- V10：GitLab 源码提交分析 —— 文件维度基线（Phase 8 / 08-01）
--
-- 落库口径（已锁定，勿改）：
--   - 采集粒度 = 仓库 × 文件 × 作者邮箱（文件级事实表，不做逐行作者表）
--   - 主指标   = git blame 存活行占比 owned_lines / file_total_lines
--   - numstat 增删/提交数为辅助列，非主指标
--   - 作者口径 = author_email；email→人员 的别名表属 Phase 3，本期 user_id 恒 NULL
--   - file_path 存 `git ls-files` 原样输出（仓库相对路径、正斜杠），
--     这是 Phase 8 / 08-02 交易维度 join 的唯一键，本期就钉死
--
-- ⚠️ 本工程未接入 Flyway 运行时（pom 无 flyway 依赖、无 spring.flyway）。
--    db/migration/ 仅是 benchmarkdb 的 DDL 约定脚本，需由 DBA / 部署脚本
--    手工应用到结果库（dao-index-analysis.result-datasource 指向的 MySQL）。
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS code_repo_config (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    repo_name         VARCHAR(128) NOT NULL                COMMENT '仓库展示名',
    repo_url          VARCHAR(512) NOT NULL                COMMENT 'clone 地址（http/https/ssh）',
    branch            VARCHAR(128)                         COMMENT '分析分支，空=master',
    local_path        VARCHAR(512)                         COMMENT '指定本地工作副本目录；空=workspace/<repo_name>',
    credential_ref    VARCHAR(128)                         COMMENT '存放访问 token 的环境变量名（凭证不入库）',
    include_exts      VARCHAR(256)                         COMMENT '逗号分隔的纳入扩展名白名单，空=全部',
    exclude_paths     VARCHAR(512)                         COMMENT '逗号分隔的排除路径前缀',
    last_sync_commit  VARCHAR(64)                          COMMENT '上次成功同步的 HEAD sha；增量基线',
    last_sync_time    DATETIME                             COMMENT '上次同步时间',
    last_sync_status  VARCHAR(16)                          COMMENT 'SUCCESS / NO_CHANGE / FAILED',
    enabled           TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '1=参与定时分析，默认 0（接入参数齐全前不跑）',
    create_time       DATETIME              DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_repo_name (repo_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='源码仓库接入配置（文件维度提交分析）';

CREATE TABLE IF NOT EXISTS code_file_author_stat (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id           BIGINT        NOT NULL               COMMENT 'code_repo_config.id',
    file_path         VARCHAR(768)  NOT NULL               COMMENT '仓库相对路径（git ls-files 原样，正斜杠）',
    author_name       VARCHAR(255)                         COMMENT 'git author name（展示用）',
    author_email      VARCHAR(255)  NOT NULL               COMMENT 'git author email（本期作者口径主键）',
    user_id           BIGINT                               COMMENT 'Phase 3 email→人员 别名映射，本期恒 NULL',
    owned_lines       INT           NOT NULL DEFAULT 0     COMMENT '主指标分子：blame 存活归属行数',
    file_total_lines  INT           NOT NULL DEFAULT 0     COMMENT '主指标分母：文件 HEAD 总行数',
    added_lines       INT           NOT NULL DEFAULT 0     COMMENT '辅助：numstat 累计新增',
    deleted_lines     INT           NOT NULL DEFAULT 0     COMMENT '辅助：numstat 累计删除',
    commit_count      INT           NOT NULL DEFAULT 0     COMMENT '辅助：该作者对该文件的提交数',
    first_commit_time DATETIME                             COMMENT '该作者对该文件最早提交时间',
    last_commit_time  DATETIME                             COMMENT '该作者对该文件最近提交时间',
    snapshot_commit   VARCHAR(64)                          COMMENT '本行归属计算所基于的提交 sha',
    snapshot_time     DATETIME                             COMMENT '本行计算时间',
    PRIMARY KEY (id),
    -- 增量删旧/重插 与 文件级保留判定 的核心查询：WHERE repo_id=? AND file_path IN (...)
    KEY idx_cfas_repo_path (repo_id, file_path(191)),
    -- Phase 3 按作者跨文件 rollup
    KEY idx_cfas_repo_email (repo_id, author_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件×作者 提交事实（每次同步对变更文件全量替换）';
