-- 慢SQL「撤销优化」审计：撤销时把撤销人(工号/姓名)与时间记到该次优化的路线条目上，
-- 路线仍追加不删——撤销不抹历史，防"被谁撤销了不知道"。
ALTER TABLE dii_slow_sql_optimize_history
    ADD COLUMN revoked_by      VARCHAR(100) DEFAULT NULL COMMENT '撤销人工号',
    ADD COLUMN revoked_by_name VARCHAR(64)  DEFAULT NULL COMMENT '撤销人姓名',
    ADD COLUMN revoked_at      DATETIME     DEFAULT NULL COMMENT '撤销时间';
