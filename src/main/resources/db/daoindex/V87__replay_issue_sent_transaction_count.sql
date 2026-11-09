-- Manual migration: apply once before deploying the current-issue sent transaction count column.
-- Historical counts stay unknown; only a subsequent import supplies the current workbook's values.
ALTER TABLE dii_replay_issue
    ADD COLUMN sent_transaction_count BIGINT NULL COMMENT '本次导入接口比对明细发送交易量';
