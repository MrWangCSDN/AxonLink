-- ============================================================
-- db/daoindex/V26__dii_tx_error_code_txid_nullable.sql
-- dii_tx_error_code.tx_id 改为可空（允许 UNMATCHED 行）。
--
-- 背景：错误码扫到的 throw 中，凡 Neo4j 图里找不到对应可达方法的（归不到任何联机交易，
--       多为批量/文件处理/工具代码，如 BGBxxxx ReadFileProcessor），物化时产出
--       match_status='UNMATCHED' 且 tx_id=NULL 的行。旧 V24 的 tx_id NOT NULL 导致整批
--       INSERT 报 “Column 'tx_id' cannot be null” → dii_tx_error_code 全空。
--       设计 §8.2 本就含 match_status MATCHED/UNMATCHED，UNMATCHED 行 tx_id 即应为 NULL。
--
-- 适用：已执行过旧版 V24（tx_id NOT NULL）的环境。全新环境跑新版 V24 已是可空，无需本脚本
--       （重复执行幂等无害）。
--
-- 注意：结果库无自动 Flyway，本脚本需人工对结果库 MySQL 执行；执行后 rescan 即可。
-- tx_id 在 uk_tx_throw 唯一键中，MySQL 唯一键允许 NULL（NULL 互不相等），且每行 throw_seq 唯一，
-- 故多条 UNMATCHED（tx_id=NULL）行不会冲突。
-- 按 tx_id 的查询（listByTxId/countByTxId）用 WHERE tx_id=?，NULL 行天然被排除（即单交易视图只含 MATCHED）。
-- ============================================================

ALTER TABLE dii_tx_error_code
    MODIFY COLUMN tx_id VARCHAR(64) NULL
    COMMENT '归属交易 ID；UNMATCHED（throw 未归属任何交易，多为批量/工具代码）为 NULL';
