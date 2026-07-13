-- 白名单申请人姓名快照：列表「发起人」列按 姓名/工号 模糊查询需要姓名落在结果库
-- （sys_user 在主库，跨库无法 JOIN）。申请时由后端解析写入；此处从流转表回补存量。
ALTER TABLE dii_whitelist_application
    ADD COLUMN applicant_name VARCHAR(64) DEFAULT NULL COMMENT '申请人姓名(申请时解析快照)';

-- 回补存量：用流转表 APPLY 事件里已解析出的姓名（V31 后的新事件才有；更早的留空，
-- 显示时由后端读取兜底解析，仅按姓名的 SQL 模糊查询覆盖不到这些最老数据——可按工号查）
UPDATE dii_whitelist_application a
  JOIN dii_whitelist_flow_history h
    ON h.application_id = a.id AND h.action = 'APPLY' AND h.actor_name IS NOT NULL
   SET a.applicant_name = h.actor_name
 WHERE a.applicant_name IS NULL;
