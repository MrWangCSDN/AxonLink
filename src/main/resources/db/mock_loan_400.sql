-- ============================================================
-- 贷款领域 400 条交易 Mock 数据（存储过程循环生成）
-- 运行：source 本文件 即可
-- ============================================================
USE mall_admin;
SET NAMES utf8mb4;

DELIMITER //
CREATE PROCEDURE gen_loan_400()
BEGIN
  DECLARE i   INT DEFAULT 0;
  DECLARE v_tx_id   INT;
  DECLARE v_tx_name VARCHAR(100);
  DECLARE v_svc1    VARCHAR(60); DECLARE v_svc1_n  VARCHAR(100);
  DECLARE v_svc2    VARCHAR(60); DECLARE v_svc2_n  VARCHAR(100);
  DECLARE v_c1      VARCHAR(60); DECLARE v_c1_n    VARCHAR(100); DECLARE v_c1_pfx VARCHAR(10);
  DECLARE v_c2      VARCHAR(60); DECLARE v_c2_n    VARCHAR(100); DECLARE v_c2_pfx VARCHAR(10);
  DECLARE v_t1      VARCHAR(60); DECLARE v_t2 VARCHAR(60); DECLARE v_t3 VARCHAR(60);

  WHILE i < 400 DO
    SET v_tx_id = 16 + i;

    -- ── 交易名称：20 种轮换 ──────────────────────────────
    SET v_tx_name = ELT(MOD(i,20)+1,
      '贷款合同签署','贷款放款处理','贷款状态查询','贷款逾期处理','贷款展期申请',
      '贷款结清处理','贷款转让处理','贷款抵押登记','贷款担保查询','信用评分查询',
      '客户资质审核','贷款利率调整','贷款额度调整','贷款产品查询','还款方式查询',
      '还款记录查询','逾期记录查询','贷款风险评估','黑名单查询',  '贷款档案查询'
    );

    -- ── 5 种链路模板：服务/构件/数据表代码轮换 ──────────
    CASE MOD(i,5)
      WHEN 0 THEN
        SET v_svc1='SVC_QUOTA_001';   SET v_svc1_n='额度查询服务';
        SET v_svc2='SVC_LEVEL_001';   SET v_svc2_n='等级评定服务';
        SET v_c1='COMP_CACHE_001'; SET v_c1_n='缓存读取组件'; SET v_c1_pfx='pbct';
        SET v_c2='COMP_CALC_001';  SET v_c2_n='金融计算组件'; SET v_c2_pfx='pbcc';
        SET v_t1='LOAN_QUOTA'; SET v_t2='CREDIT_LINE';   SET v_t3='CUSTOMER_LEVEL';
      WHEN 1 THEN
        SET v_svc1='SVC_APPROVE_001'; SET v_svc1_n='审批查询服务';
        SET v_svc2='SVC_FLOW_001';    SET v_svc2_n='流程状态服务';
        SET v_c1='COMP_QUERY_001'; SET v_c1_n='数据查询组件'; SET v_c1_pfx='pbcc';
        SET v_c2='COMP_CACHE_001'; SET v_c2_n='缓存读取组件'; SET v_c2_pfx='pbct';
        SET v_t1='APPROVE_INFO'; SET v_t2='FLOW_NODE';    SET v_t3='FLOW_LOG';
      WHEN 2 THEN
        SET v_svc1='SVC_REPAY_001';   SET v_svc1_n='还款计划服务';
        SET v_svc2='SVC_CALC_001';    SET v_svc2_n='利率计算服务';
        SET v_c1='COMP_CALC_001';  SET v_c1_n='金融计算组件'; SET v_c1_pfx='pbcc';
        SET v_c2='COMP_DATE_001';  SET v_c2_n='日期处理组件'; SET v_c2_pfx='pbct';
        SET v_t1='REPAY_PLAN';   SET v_t2='LOAN_CONTRACT'; SET v_t3='INTEREST_RATE';
      WHEN 3 THEN
        SET v_svc1='SVC_LOAN_001';    SET v_svc1_n='贷款申请服务';
        SET v_svc2='SVC_RISK_001';    SET v_svc2_n='风险评估服务';
        SET v_c1='COMP_VALID_001'; SET v_c1_n='数据校验组件'; SET v_c1_pfx='pbcc';
        SET v_c2='COMP_CACHE_001'; SET v_c2_n='缓存读取组件'; SET v_c2_pfx='pbct';
        SET v_t1='LOAN_APPLY';   SET v_t2='CUSTOMER_INFO';  SET v_t3='CREDIT_RECORD';
      ELSE -- 4
        SET v_svc1='SVC_CREDIT_001';  SET v_svc1_n='信用评估服务';
        SET v_svc2='SVC_LIMIT_001';   SET v_svc2_n='额度检查服务';
        SET v_c1='COMP_CACHE_001'; SET v_c1_n='缓存读取组件'; SET v_c1_pfx='pbct';
        SET v_c2='COMP_CALC_001';  SET v_c2_n='金融计算组件'; SET v_c2_pfx='pbcc';
        SET v_t1='CREDIT_LINE';  SET v_t2='LOAN_QUOTA';    SET v_t3='LOAN_ACCT';
    END CASE;

    -- ── 写入各表 ────────────────────────────────────────
    INSERT INTO t_transaction (id, tx_code, name, domain_id, layers, sort_order, create_time, update_time, deleted)
    VALUES (v_tx_id, CONCAT('TD', LPAD(106+i, 4, '0')), v_tx_name, 2, 4, 6+i, NOW(), NOW(), 0);

    -- 服务节点（每条交易 2 个）
    INSERT INTO t_service_node (id, tx_id, prefix, service_code, name, cross_domain, sort_order, create_time, deleted)
    VALUES (1001+i*2, v_tx_id, 'pbs', v_svc1, v_svc1_n, NULL, 1, NOW(), 0),
           (1002+i*2, v_tx_id, 'pbs', v_svc2, v_svc2_n, NULL, 2, NOW(), 0);

    -- 构件节点（每条交易 2 个）
    INSERT INTO t_component_node (id, tx_id, prefix, component_code, name, cross_domain, sort_order, create_time, deleted)
    VALUES (2001+i*2, v_tx_id, v_c1_pfx, v_c1, v_c1_n, NULL, 1, NOW(), 0),
           (2002+i*2, v_tx_id, v_c2_pfx, v_c2, v_c2_n, NULL, 2, NOW(), 0);

    -- 数据表节点（每条交易 3 个）
    INSERT INTO t_data_table_node (id, tx_id, table_code, name, sort_order, create_time, deleted)
    VALUES (3001+i*3, v_tx_id, v_t1, v_t1, 1, NOW(), 0),
           (3002+i*3, v_tx_id, v_t2, v_t2, 2, NOW(), 0),
           (3003+i*3, v_tx_id, v_t3, v_t3, 3, NOW(), 0);

    -- 关联关系（每条交易 6 条：3 个 s2c + 3 个 c2d）
    INSERT INTO t_relation (id, tx_id, relation_type, from_code, to_code, create_time, deleted)
    VALUES (5001+i*6, v_tx_id, 'SERVICE_TO_COMPONENT', v_svc1, v_c1, NOW(), 0),
           (5002+i*6, v_tx_id, 'SERVICE_TO_COMPONENT', v_svc1, v_c2, NOW(), 0),
           (5003+i*6, v_tx_id, 'SERVICE_TO_COMPONENT', v_svc2, v_c1, NOW(), 0),
           (5004+i*6, v_tx_id, 'COMPONENT_TO_DATA',    v_c1,   v_t1, NOW(), 0),
           (5005+i*6, v_tx_id, 'COMPONENT_TO_DATA',    v_c1,   v_t2, NOW(), 0),
           (5006+i*6, v_tx_id, 'COMPONENT_TO_DATA',    v_c2,   v_t3, NOW(), 0);

    SET i = i + 1;
  END WHILE;
END//
DELIMITER ;

CALL gen_loan_400();
DROP PROCEDURE IF EXISTS gen_loan_400;

-- 验证
SELECT '贷款领域交易总数' AS item, COUNT(*) AS cnt
FROM t_transaction WHERE domain_id=2 AND deleted=0;
