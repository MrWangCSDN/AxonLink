-- 白名单流转路径（追加不删，与「优化路线」同款审计）：每一步流转一条——
-- 申请/一级通过/一级退回/二级通过/二级退回/撤回申请：谁、何时、理由/意见。
-- 同一 SQL 跨多次申请（退回后重新申请=新 application）也能串成完整处理过程。
CREATE TABLE dii_whitelist_flow_history (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT       NOT NULL          COMMENT '所属申请 dii_whitelist_application.id',
    target_type    VARCHAR(16)  NOT NULL          COMMENT 'HASH / NAMED_SQL / SLOW_SQL',
    sql_hash       VARCHAR(64)                    COMMENT '抽象SQL哈希（SLOW_SQL/HASH）',
    named_sql      VARCHAR(500)                   COMMENT 'NAMED_SQL 模式',
    project_name   VARCHAR(200)                   COMMENT '微服务名（SLOW_SQL）',
    action         VARCHAR(20)  NOT NULL          COMMENT 'APPLY/L1_APPROVE/L1_REJECT/L2_APPROVE/L2_REJECT/CANCEL',
    actor          VARCHAR(100)                   COMMENT '操作人（登录名/工号）',
    actor_name     VARCHAR(64)                    COMMENT '操作人姓名（写入时解析快照，回填数据为空）',
    opinion        VARCHAR(1000)                  COMMENT '申请理由 / 审批·退回意见',
    created_at     DATETIME     NOT NULL          COMMENT '发生时间',
    KEY idx_app (application_id),
    KEY idx_slow_key (project_name, sql_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='白名单流转路径(追加不删)';

-- 回填存量（尽力而为）：每条申请补 APPLY 事件 + 现存的最近一次 L1/L2 决策事件。
-- 局限：历史上被覆盖的中间轮决策、以及取消事件（无操作时间列）无法回补；姓名留空由前端回退工号。
INSERT INTO dii_whitelist_flow_history
  (application_id, target_type, sql_hash, named_sql, project_name, action, actor, opinion, created_at)
SELECT id, target_type, sql_hash, named_sql, project_name, 'APPLY', applicant, apply_reason, apply_at
  FROM dii_whitelist_application;

INSERT INTO dii_whitelist_flow_history
  (application_id, target_type, sql_hash, named_sql, project_name, action, actor, opinion, created_at)
SELECT id, target_type, sql_hash, named_sql, project_name,
       CASE l1_decision WHEN 'APPROVE' THEN 'L1_APPROVE' ELSE 'L1_REJECT' END,
       l1_approver, l1_opinion, l1_at
  FROM dii_whitelist_application
 WHERE l1_decision IS NOT NULL AND l1_at IS NOT NULL;

INSERT INTO dii_whitelist_flow_history
  (application_id, target_type, sql_hash, named_sql, project_name, action, actor, opinion, created_at)
SELECT id, target_type, sql_hash, named_sql, project_name,
       CASE l2_decision WHEN 'APPROVE' THEN 'L2_APPROVE' ELSE 'L2_REJECT' END,
       l2_approver, l2_opinion, l2_at
  FROM dii_whitelist_application
 WHERE l2_decision IS NOT NULL AND l2_at IS NOT NULL;
