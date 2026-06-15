-- ============================================================
-- db/daoindex/V24__dii_error_code.sql
-- 错误码 throw 明细 + 交易维度物化
-- 注意：结果库(diiResultDataSource)无自动 Flyway，本脚本需人工对结果库 MySQL 执行。
-- ============================================================

-- (1) throw 明细：一行一处 throw，每次扫描 DELETE + 批量 INSERT 重建
CREATE TABLE dii_error_code (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    error_code   VARCHAR(20)   NOT NULL COMMENT '错误码本体，如 E0003 / R0005',
    error_scope  VARCHAR(64)   NOT NULL COMMENT '错误类.分类，如 CmError.Brch / CmError',
    throw_text   VARCHAR(1024) NOT NULL COMMENT 'throw 后的完整内容(AST打印)',
    class_fqn    VARCHAR(255)  NOT NULL COMMENT '源类全限定名(pkg.ClassName)，与 Neo4j Method.classFqn 对齐',
    method_name  VARCHAR(128)  NOT NULL COMMENT '源方法简名(不含参数)',
    file_path    VARCHAR(512)           COMMENT '源文件绝对路径',
    line_no      INT                    COMMENT 'throw 起始行号，解析失败为 NULL（不参与去重）',
    module_name  VARCHAR(128)           COMMENT '模块名，如 loan-bcc（来源 detectModule(file)）',
    inner_class_name VARCHAR(128)       COMMENT '预留：原始内部类简名(本期可空)',
    code_signature   VARCHAR(512)       COMMENT '预留：Method.signature 精确重载识别(本期可空)',
    throw_seq    BIGINT        NOT NULL COMMENT '扫描内自增序号，保证同方法多 throw 不丢',
    scanned_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '扫描时间',
    UNIQUE KEY uk_throw (error_scope, error_code, class_fqn, method_name, throw_seq),
    KEY idx_error_code (error_code),
    KEY idx_class_method (class_fqn, method_name),
    KEY idx_scanned (scanned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='throw 错误码扫描明细（一行一处 throw）';

-- (2) 交易维度物化：tx × error_code，扫描期物化、整表重建
CREATE TABLE dii_tx_error_code (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tx_id         VARCHAR(64)   NOT NULL COMMENT '归属交易 ID',
    tx_name       VARCHAR(255)           COMMENT '归属交易名(longname)',
    domain_key    VARCHAR(16)            COMMENT '领域 key',
    error_code    VARCHAR(20)   NOT NULL COMMENT '错误码本体',
    error_scope   VARCHAR(64)   NOT NULL COMMENT '错误类.分类',
    throw_text    VARCHAR(1024) NOT NULL COMMENT 'throw 完整内容',
    class_fqn     VARCHAR(255)  NOT NULL COMMENT '源类 FQN',
    method_name   VARCHAR(128)  NOT NULL COMMENT '源方法简名',
    file_path     VARCHAR(512)           COMMENT '源文件路径',
    line_no       INT                    COMMENT '行号，解析失败为 NULL',
    module_name   VARCHAR(128)           COMMENT '模块名',
    component_code VARCHAR(255)          COMMENT '归属构件 code(op.serviceId/st.id)，NULL=工具方法',
    component_name VARCHAR(255)          COMMENT '归属构件名(op.longname/st.longname)',
    match_status  VARCHAR(16)   NOT NULL DEFAULT 'MATCHED'
                  COMMENT 'MATCHED / UNMATCHED(扫描 classFqn 未命中 Neo4j，含 enum 体内 throw)',
    throw_seq     BIGINT        NOT NULL COMMENT '来自 dii_error_code.throw_seq',
    scanned_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tx_throw (tx_id, error_scope, error_code, class_fqn, method_name, throw_seq),
    KEY idx_tx_id (tx_id),
    KEY idx_error_code (error_code),
    KEY idx_domain (domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='交易维度物化错误码（tx_id × error_code × 归属构件）';
