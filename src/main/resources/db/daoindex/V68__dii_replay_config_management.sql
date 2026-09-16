-- 回放配置管理：四类配置业务表 + 对应操作表 + 只读映射表 znzx_service
--
-- 应用方式：本项目无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- 说明：
--   1) 四张业务表统一包含 id / created_at / updated_at / version 系统字段。
--   2) 唯一键文本列使用 utf8mb4_bin，保证大小写敏感。
--   3) 操作表不使用外键，物理删除业务行后仍保留历史。
--   4) znzx_service 由其他系统维护，本工程只读；此脚本按逻辑结构创建，便于部署到结果库。
--   5) 字段名按历史契约保留 rmove / fiel / indx / trcd / arry 等拼写，不修正。

-- ═══════════ 只读映射表 znzx_service ═══════════
CREATE TABLE IF NOT EXISTS znzx_service (
    application_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '应用名称',
    esf_service_code VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ESF 服务码，映射为最终服务码基础值',
    flow_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '流程标识',
    tran_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '内部核心交易码',
    function_desc VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '功能描述',
    group_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '组名',
    KEY idx_znzx_service_tran_code (tran_code),
    KEY idx_znzx_service_esf_service_code (esf_service_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部核心交易码与 ESF 服务码映射（外部只读表）';

-- ═══════════ 无条件忽略 ═══════════
CREATE TABLE IF NOT EXISTS dii_replay_unconditional_ignore (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tran_code VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '最终服务码',
    field_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '忽略字段',
    enable_flag TINYINT NOT NULL DEFAULT 1 COMMENT '固定启用标识',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_replay_unconditional_code_field (tran_code, field_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放无条件忽略配置';

CREATE TABLE IF NOT EXISTS dii_replay_unconditional_ignore_operation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_id BIGINT NOT NULL COMMENT '对应业务记录 id',
    operation_type VARCHAR(16) NOT NULL COMMENT 'CREATE/UPDATE/DELETE',
    tran_code VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    field_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_tran_code VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_field_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    operator_username VARCHAR(128) NULL,
    operator_real_name VARCHAR(128) NULL,
    operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/EXCEL',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_replay_unconditional_operation (config_id, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='无条件忽略操作审计';

-- ═══════════ 有条件忽略 ═══════════
CREATE TABLE IF NOT EXISTS dii_replay_conditional_rmove (
    id BIGINT NOT NULL AUTO_INCREMENT,
    orig_trcd VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '最终服务码',
    field_rmove_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '忽略字段',
    field_fiel_state TINYINT NOT NULL DEFAULT 1 COMMENT '固定状态',
    field_file_indx INT UNSIGNED NOT NULL COMMENT '同服务码内字段索引，后端生成',
    field_file_flag TINYINT NOT NULL COMMENT '1=普通字段，2=对象或数组',
    orig_field_cond TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '主系统字段忽略条件',
    dest_field_cond TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '备系统字段忽略条件',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_replay_conditional_required (orig_trcd, field_rmove_name, field_file_indx),
    UNIQUE KEY uk_replay_conditional_position (orig_trcd, field_file_indx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放有条件忽略配置';

CREATE TABLE IF NOT EXISTS dii_replay_conditional_rmove_operation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_id BIGINT NOT NULL COMMENT '对应业务记录 id',
    operation_type VARCHAR(16) NOT NULL COMMENT 'CREATE/UPDATE/DELETE',
    orig_trcd VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    field_rmove_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    field_file_indx INT UNSIGNED NULL,
    field_file_flag TINYINT NULL,
    orig_field_cond TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    dest_field_cond TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_orig_trcd VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_field_rmove_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_field_file_indx INT UNSIGNED NULL,
    new_field_file_flag TINYINT NULL,
    new_orig_field_cond TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_dest_field_cond TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    operator_username VARCHAR(128) NULL,
    operator_real_name VARCHAR(128) NULL,
    operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/EXCEL',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_replay_conditional_operation (config_id, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='有条件忽略操作审计';

-- ═══════════ 错误码忽略 ═══════════
CREATE TABLE IF NOT EXISTS dii_replay_error_code_ignore_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_code VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '最终服务码',
    old_resp_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '老核心错误码',
    new_resp_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '新核心错误码',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '固定启用标识',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_replay_error_code (service_code, old_resp_code, new_resp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放错误码忽略配置';

CREATE TABLE IF NOT EXISTS dii_replay_error_code_ignore_config_operation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_id BIGINT NOT NULL COMMENT '对应业务记录 id',
    operation_type VARCHAR(16) NOT NULL COMMENT 'CREATE/UPDATE/DELETE',
    service_code VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    old_resp_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_resp_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_service_code VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_old_resp_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_new_resp_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    operator_username VARCHAR(128) NULL,
    operator_real_name VARCHAR(128) NULL,
    operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/EXCEL',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_replay_error_code_operation (config_id, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='错误码忽略操作审计';

-- ═══════════ 排序字段 ═══════════
CREATE TABLE IF NOT EXISTS dii_replay_sort_field (
    id BIGINT NOT NULL AUTO_INCREMENT,
    orig_trcd VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '最终服务码',
    orig_arry_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '对象或数组节点名称',
    orig_field_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '排序字段',
    tran_mode TINYINT NOT NULL DEFAULT 1 COMMENT '固定交易模式',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_replay_sort_field (orig_trcd, orig_arry_name, orig_field_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回放排序字段配置';

CREATE TABLE IF NOT EXISTS dii_replay_sort_field_operation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_id BIGINT NOT NULL COMMENT '对应业务记录 id',
    operation_type VARCHAR(16) NOT NULL COMMENT 'CREATE/UPDATE/DELETE',
    orig_trcd VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    orig_arry_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    orig_field_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_orig_trcd VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_orig_arry_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    new_orig_field_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    operator_username VARCHAR(128) NULL,
    operator_real_name VARCHAR(128) NULL,
    operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/EXCEL',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_replay_sort_field_operation (config_id, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排序字段操作审计';
