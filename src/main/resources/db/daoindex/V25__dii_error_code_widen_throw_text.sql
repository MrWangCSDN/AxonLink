-- ============================================================
-- db/daoindex/V25__dii_error_code_widen_throw_text.sql
-- 放宽 throw_text / file_path：throw_text VARCHAR(1024) → MEDIUMTEXT、file_path → VARCHAR(2048)。
--
-- 背景：内网实测错误码扫描时 INSERT 报 “Data too long for column 'throw_text'”
--       —— 银行业务 throw 表达式（嵌套调用 + MDict 引用）超过 1024 字符，整批插入失败、明细表为空。
--       file_path 一并放宽（内网源码路径较深，512 可能不够）。
--
-- 适用：已执行过旧版 V24（throw_text VARCHAR(1024) / file_path VARCHAR(512)）的环境。
--       全新环境直接执行新版 V24 即已是 MEDIUMTEXT / VARCHAR(2048)，无需本脚本（重复执行也幂等无害）。
--
-- 注意：结果库(diiResultDataSource)无自动 Flyway，本脚本需人工对结果库 MySQL 执行；执行后 rescan 即可。
-- throw_text 不在任何索引/唯一键中（uk_throw 为 error_scope/error_code/class_fqn/method_name/throw_seq），
-- 改 MEDIUMTEXT 不影响索引。
-- ============================================================

ALTER TABLE dii_error_code
    MODIFY COLUMN throw_text MEDIUMTEXT NOT NULL COMMENT 'throw 后的完整内容(AST打印)；MEDIUMTEXT',
    MODIFY COLUMN file_path  VARCHAR(2048)       COMMENT '源文件绝对路径（放宽到 2048）';

ALTER TABLE dii_tx_error_code
    MODIFY COLUMN throw_text MEDIUMTEXT NOT NULL COMMENT 'throw 完整内容；MEDIUMTEXT',
    MODIFY COLUMN file_path  VARCHAR(2048)       COMMENT '源文件路径（放宽到 2048）';
