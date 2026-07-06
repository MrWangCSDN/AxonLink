-- 慢SQL「已优化」增强：记录优化人姓名 + 优化内容。
-- 工号沿用已有列 optimized_by（打标时存当前登录用户工号 empNo，取不到回退登录名）。
ALTER TABLE dii_slow_sql_optimization
    ADD COLUMN optimized_by_name VARCHAR(64)  DEFAULT NULL COMMENT '优化人姓名(按工号解析快照)',
    ADD COLUMN optimize_note     VARCHAR(200) DEFAULT NULL COMMENT '优化内容(≤200字，打标时填写)';
