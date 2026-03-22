-- ============================================================
-- 交易链路分析平台 — 测试数据 SQL
-- 对应 mock/data.js 全部数据
-- ============================================================
USE mall_admin;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ─────────────────────────────────────────────
-- 领域表
-- ─────────────────────────────────────────────
INSERT INTO t_domain (id, domain_key, name, icon, sort_order, create_time, update_time, deleted) VALUES
(1, 'public',     '公共领域', 'globe',       1, NOW(), NOW(), 0),
(2, 'loan',       '贷款领域', 'credit-card', 2, NOW(), NOW(), 0),
(3, 'deposit',    '存款领域', 'bank',        3, NOW(), NOW(), 0),
(4, 'settlement', '结算领域', 'exchange',    4, NOW(), NOW(), 0);

-- ─────────────────────────────────────────────
-- 交易表（15 笔）
-- domain_id: 1=公共 2=贷款 3=存款 4=结算
-- ─────────────────────────────────────────────
INSERT INTO t_transaction (id, tx_code, name, domain_id, layers, sort_order, create_time, update_time, deleted) VALUES
(1,  'TD0001', '用户认证交易', 1, 4,  1, NOW(), NOW(), 0),
(2,  'TD0002', '系统参数查询', 1, 4,  2, NOW(), NOW(), 0),
(3,  'TD0003', '系统日志记录', 1, 4,  3, NOW(), NOW(), 0),
(4,  'TD0101', '贷款申请提交', 2, 4,  1, NOW(), NOW(), 0),
(5,  'TD0102', '贷款额度查询', 2, 4,  2, NOW(), NOW(), 0),
(6,  'TD0103', '还款计划生成', 2, 4,  3, NOW(), NOW(), 0),
(7,  'TD0104', '贷款审批查询', 2, 4,  4, NOW(), NOW(), 0),
(8,  'TD0105', '提前还款处理', 2, 4,  5, NOW(), NOW(), 0),
(9,  'TD0201', '开户申请处理', 3, 4,  1, NOW(), NOW(), 0),
(10, 'TD0202', '存款入账处理', 3, 4,  2, NOW(), NOW(), 0),
(11, 'TD0203', '余额查询交易', 3, 4,  3, NOW(), NOW(), 0),
(12, 'TD0204', '利息结算处理', 3, 4,  4, NOW(), NOW(), 0),
(13, 'TD0301', '跨行转账处理', 4, 4,  1, NOW(), NOW(), 0),
(14, 'TD0302', '对账文件生成', 4, 4,  2, NOW(), NOW(), 0),
(15, 'TD0303', '日终轧账处理', 4, 4,  3, NOW(), NOW(), 0);

-- ─────────────────────────────────────────────
-- 服务节点表
-- tx_id 对应上方 t_transaction.id
-- ─────────────────────────────────────────────
INSERT INTO t_service_node (id, tx_id, prefix, service_code, name, cross_domain, sort_order, create_time, deleted) VALUES
-- TD0001 用户认证交易
(101, 1, 'pbs', 'SVC_AUTH_001', '用户认证服务', NULL,   1, NOW(), 0),
(102, 1, 'pbs', 'SVC_LOG_001',  '操作日志服务', NULL,   2, NOW(), 0),
-- TD0002 系统参数查询
(103, 2, 'pbs', 'SVC_PARAM_001','参数管理服务', NULL,   1, NOW(), 0),
(104, 2, 'pbs', 'SVC_CACHE_001','缓存管理服务', NULL,   2, NOW(), 0),
-- TD0003 系统日志记录
(105, 3, 'pbs', 'SVC_LOG_002',  '日志记录服务', NULL,   1, NOW(), 0),
(106, 3, 'pcs', 'SVC_ALERT_001','告警通知服务', NULL,   2, NOW(), 0),
(107, 3, 'pbs', 'SVC_MSG_001',  '消息推送服务', NULL,   3, NOW(), 0),
-- TD0101 贷款申请提交
(108, 4, 'pbs', 'SVC_AUTH_002',   '用户鉴权服务',   '公共领域', 1, NOW(), 0),
(109, 4, 'pbs', 'SVC_LIMIT_001',  '额度检查服务',   NULL,       2, NOW(), 0),
(110, 4, 'pbs', 'SVC_RISK_001',   '风险评估服务',   NULL,       3, NOW(), 0),
(111, 4, 'pcs', 'SVC_NOTIFY_001', '通知发送服务',   '公共领域', 4, NOW(), 0),
(112, 4, 'pcs', 'SVC_FLOW_002',   '流程控制服务',   NULL,       5, NOW(), 0),
(113, 4, 'pbs', 'SVC_LOAN_001',   '贷款申请服务',   NULL,       6, NOW(), 0),
(114, 4, 'pbs', 'SVC_CREDIT_001', '信用评估服务',   NULL,       7, NOW(), 0),
(115, 4, 'pbs', 'SVC_WORK_001',   '工作流服务',     '结算领域', 8, NOW(), 0),
-- TD0102 贷款额度查询
(116, 5, 'pbs', 'SVC_QUOTA_001', '额度查询服务', NULL, 1, NOW(), 0),
(117, 5, 'pbs', 'SVC_LEVEL_001', '等级评定服务', NULL, 2, NOW(), 0),
-- TD0103 还款计划生成
(118, 6, 'pbs', 'SVC_REPAY_001',  '还款计划服务',NULL,  1, NOW(), 0),
(119, 6, 'pbs', 'SVC_CALC_001',   '利率计算服务',NULL,  2, NOW(), 0),
(120, 6, 'pcs', 'SVC_REPORT_001', '报表生成服务',NULL,  3, NOW(), 0),
(121, 6, 'pbs', 'SVC_RPT_001',    '计划报表服务',NULL,  4, NOW(), 0),
-- TD0104 贷款审批查询
(122, 7, 'pbs', 'SVC_APPROVE_001','审批查询服务',NULL,  1, NOW(), 0),
(123, 7, 'pbs', 'SVC_FLOW_001',   '流程状态服务',NULL,  2, NOW(), 0),
-- TD0105 提前还款处理
(124, 8, 'pbs', 'SVC_PREPAY_001', '提前还款校验服务', NULL,       1, NOW(), 0),
(125, 8, 'pbs', 'SVC_CALC_001',   '金融计算服务',     NULL,       2, NOW(), 0),
(126, 8, 'pcs', 'SVC_NOTIFY_001', '通知发送服务',     '公共领域', 3, NOW(), 0),
(127, 8, 'pbs', 'SVC_EARLY_001',  '提前还款服务',     NULL,       4, NOW(), 0),
(128, 8, 'pbs', 'SVC_ACCT_001',   '账务处理服务',     NULL,       5, NOW(), 0),
-- TD0201 开户申请处理
(129,  9, 'pbs', 'SVC_OPEN_001',      '开户服务',     NULL,       1, NOW(), 0),
(130,  9, 'pbs', 'SVC_KYC_001',       '实名认证服务', '公共领域', 2, NOW(), 0),
(131,  9, 'pcs', 'SVC_NOTIFY_001',    '通知发送服务', '公共领域', 3, NOW(), 0),
(132,  9, 'pbs', 'SVC_ACCT_OPEN_001', '账户开立服务', NULL,       4, NOW(), 0),
-- TD0202 存款入账处理
(133, 10, 'pbs', 'SVC_DEPOSIT_001','存款服务',     NULL,       1, NOW(), 0),
(134, 10, 'pbs', 'SVC_ACCT_001',   '账务处理服务', NULL,       2, NOW(), 0),
(135, 10, 'pcs', 'SVC_NOTIFY_001', '通知发送服务', '公共领域', 3, NOW(), 0),
(136, 10, 'pbs', 'SVC_SETTLE_001', '结算通知服务', NULL,       4, NOW(), 0),
-- TD0203 余额查询交易
(137, 11, 'pbs', 'SVC_BALANCE_001',  '余额查询服务', NULL, 1, NOW(), 0),
(138, 11, 'pbs', 'SVC_ACCT_QRY_001', '账户查询服务', NULL, 2, NOW(), 0),
-- TD0204 利息结算处理
(139, 12, 'pbs', 'SVC_INTEREST_001','利息计算服务', NULL, 1, NOW(), 0),
(140, 12, 'pbs', 'SVC_ACCT_001',    '账务处理服务', NULL, 2, NOW(), 0),
(141, 12, 'pcs', 'SVC_BATCH_001',   '批量处理服务', NULL, 3, NOW(), 0),
(142, 12, 'pbs', 'SVC_SETTLE_001',  '结算服务',     NULL, 4, NOW(), 0),
-- TD0301 跨行转账处理
(143, 13, 'pbs', 'SVC_TRANSFER_001', '转账处理服务', NULL,       1, NOW(), 0),
(144, 13, 'pbs', 'SVC_CLEAR_001',    '清算服务',     NULL,       2, NOW(), 0),
(145, 13, 'pcs', 'SVC_NOTIFY_001',   '通知发送服务', '公共领域', 3, NOW(), 0),
(146, 13, 'pcs', 'SVC_RISK_SVC_001', '风控服务',     NULL,       4, NOW(), 0),
(147, 13, 'pbs', 'SVC_MSG_001',      '消息推送服务', '公共领域', 5, NOW(), 0),
-- TD0302 对账文件生成
(148, 14, 'pbs', 'SVC_RECON_001', '对账服务',     NULL, 1, NOW(), 0),
(149, 14, 'pbs', 'SVC_FILE_001',  '文件生成服务', NULL, 2, NOW(), 0),
(150, 14, 'pcs', 'SVC_EXPORT_001','导出服务',     NULL, 3, NOW(), 0),
-- TD0303 日终轧账处理
(151, 15, 'pbs', 'SVC_EOD_001',       '日终处理服务', NULL, 1, NOW(), 0),
(152, 15, 'pbs', 'SVC_SETTLE_001',    '结算服务',     NULL, 2, NOW(), 0),
(153, 15, 'pcs', 'SVC_REPORT_SVC_001','报表服务',     NULL, 3, NOW(), 0),
(154, 15, 'pcs', 'SVC_NOTIFY_SVC_001','通知服务',     NULL, 4, NOW(), 0),
(155, 15, 'pbs', 'SVC_REPORT_001',    '报表生成服务', NULL, 5, NOW(), 0);

-- ─────────────────────────────────────────────
-- 构件节点表
-- ─────────────────────────────────────────────
INSERT INTO t_component_node (id, tx_id, prefix, component_code, name, cross_domain, sort_order, create_time, deleted) VALUES
-- TD0001
(201, 1, 'pbcc', 'COMP_AUTH_001', '认证鉴权组件', NULL, 1, NOW(), 0),
(202, 1, 'pbct', 'COMP_ENC_001',  '加密解密组件', NULL, 2, NOW(), 0),
(203, 1, 'pbct', 'COMP_LOG_001',  '日志写入组件', NULL, 3, NOW(), 0),
-- TD0002
(204, 2, 'pbct', 'COMP_CACHE_001', '缓存读取组件', NULL, 1, NOW(), 0),
(205, 2, 'pbcc', 'COMP_QUERY_001', '数据查询组件', NULL, 2, NOW(), 0),
-- TD0003
(206, 3, 'pbcc', 'COMP_LOG_001', '日志写入组件', NULL, 1, NOW(), 0),
(207, 3, 'pbct', 'COMP_MSG_001', '消息发送组件', NULL, 2, NOW(), 0),
-- TD0101（含 pbcb/pbcp 业务构件）
(208, 4, 'pbcb', 'COMP_AUTH_BIZ_001',   '认证业务组件', '公共领域', 1, NOW(), 0),
(209, 4, 'pbcp', 'COMP_QUOTA_PROD_001',  '额度产品组件', NULL,       2, NOW(), 0),
(210, 4, 'pbcb', 'COMP_RISK_BASE_001',   '风险基础组件', NULL,       3, NOW(), 0),
(211, 4, 'pbcp', 'COMP_LOAN_PROD_001',   '贷款产品组件', NULL,       4, NOW(), 0),
(212, 4, 'pbcc', 'COMP_TOKEN_001', '令牌验证组件', '公共领域', 5, NOW(), 0),
(213, 4, 'pbct', 'COMP_PERM_001',  '权限检查组件', '公共领域', 6, NOW(), 0),
(214, 4, 'pbcc', 'COMP_CALC_002',  '额度计算组件', NULL,       7, NOW(), 0),
(215, 4, 'pbct', 'COMP_CACHE_002', '缓存查询组件', NULL,       8, NOW(), 0),
(216, 4, 'pbcc', 'COMP_RULE_002',  '风控规则组件', NULL,       9, NOW(), 0),
(217, 4, 'pbct', 'COMP_SCORE_001', '评分计算组件', NULL,      10, NOW(), 0),
(218, 4, 'pbcc', 'COMP_VALID_001', '数据校验组件', '公共领域',11, NOW(), 0),
(219, 4, 'pbct', 'COMP_RULE_001',  '规则引擎组件', '公共领域',12, NOW(), 0),
(220, 4, 'pbct', 'COMP_PROC_001',  '流程处理组件', '结算领域',13, NOW(), 0),
-- TD0102
(221, 5, 'pbct', 'COMP_CACHE_001', '缓存读取组件', NULL, 1, NOW(), 0),
(222, 5, 'pbcc', 'COMP_CALC_001',  '金融计算组件', NULL, 2, NOW(), 0),
-- TD0103
(223, 6, 'pbcc', 'COMP_CALC_001', '金融计算组件', NULL, 1, NOW(), 0),
(224, 6, 'pbct', 'COMP_DATE_001', '日期处理组件', NULL, 2, NOW(), 0),
(225, 6, 'pbct', 'COMP_FMT_001',  '格式化组件',   NULL, 3, NOW(), 0),
-- TD0104
(226, 7, 'pbct', 'COMP_CACHE_001', '缓存读取组件', NULL, 1, NOW(), 0),
(227, 7, 'pbcc', 'COMP_QUERY_001', '数据查询组件', NULL, 2, NOW(), 0),
-- TD0105
(228, 8, 'pbcc', 'COMP_CALC_001',  '金融计算组件', NULL,       1, NOW(), 0),
(229, 8, 'pbcc', 'COMP_VALID_001', '数据校验组件', '公共领域', 2, NOW(), 0),
(230, 8, 'pbcc', 'COMP_LOCK_001',  '账户锁定组件', NULL,       3, NOW(), 0),
-- TD0201
(231,  9, 'pbcc', 'COMP_AUTH_001',  '认证鉴权组件', '公共领域', 1, NOW(), 0),
(232,  9, 'pbct', 'COMP_VALID_001', '数据校验组件', '公共领域', 2, NOW(), 0),
(233,  9, 'pbcc', 'COMP_ACCT_001',  '账户创建组件', NULL,       3, NOW(), 0),
-- TD0202
(234, 10, 'pbcc', 'COMP_LOCK_001',  '账户锁定组件', NULL,       1, NOW(), 0),
(235, 10, 'pbcc', 'COMP_VALID_001', '数据校验组件', '公共领域', 2, NOW(), 0),
(236, 10, 'pbct', 'COMP_MSG_001',   '消息发送组件', NULL,       3, NOW(), 0),
-- TD0203
(237, 11, 'pbct', 'COMP_CACHE_001', '缓存读取组件', NULL, 1, NOW(), 0),
(238, 11, 'pbcc', 'COMP_QUERY_001', '数据查询组件', NULL, 2, NOW(), 0),
-- TD0204
(239, 12, 'pbcc', 'COMP_CALC_001', '金融计算组件', NULL, 1, NOW(), 0),
(240, 12, 'pbct', 'COMP_DATE_001', '日期处理组件', NULL, 2, NOW(), 0),
(241, 12, 'pbct', 'COMP_MSG_001',  '消息发送组件', NULL, 3, NOW(), 0),
-- TD0301
(242, 13, 'pbcc', 'COMP_AUTH_001',  '认证鉴权组件', '公共领域', 1, NOW(), 0),
(243, 13, 'pbcc', 'COMP_LOCK_001',  '账户锁定组件', NULL,       2, NOW(), 0),
(244, 13, 'pbcc', 'COMP_QUERY_001', '批量查询组件', NULL,       3, NOW(), 0),
(245, 13, 'pbct', 'COMP_MSG_001',   '消息发送组件', '公共领域', 4, NOW(), 0),
-- TD0302
(246, 14, 'pbcc', 'COMP_QUERY_001',  '批量查询组件', NULL, 1, NOW(), 0),
(247, 14, 'pbct', 'COMP_FMT_001',    '格式化组件',   NULL, 2, NOW(), 0),
(248, 14, 'pbct', 'COMP_EXPORT_001', '文件导出组件', NULL, 3, NOW(), 0),
-- TD0303
(249, 15, 'pbcc', 'COMP_CALC_001',  '金融计算组件', NULL, 1, NOW(), 0),
(250, 15, 'pbct', 'COMP_BATCH_001', '批处理组件',   NULL, 2, NOW(), 0),
(251, 15, 'pbct', 'COMP_FMT_001',   '格式化组件',   NULL, 3, NOW(), 0);

-- ─────────────────────────────────────────────
-- 数据表节点
-- ─────────────────────────────────────────────
INSERT INTO t_data_table_node (id, tx_id, table_code, name, sort_order, create_time, deleted) VALUES
-- TD0001
(301, 1, 'USR_INFO',    'USR_INFO',    1, NOW(), 0),
(302, 1, 'USR_TOKEN',   'USR_TOKEN',   2, NOW(), 0),
(303, 1, 'SYS_LOG',     'SYS_LOG',     3, NOW(), 0),
(304, 1, 'AUDIT_TRAIL', 'AUDIT_TRAIL', 4, NOW(), 0),
-- TD0002
(305, 2, 'SYS_PARAM',        'SYS_PARAM',        1, NOW(), 0),
(306, 2, 'SYS_PARAM_DETAIL', 'SYS_PARAM_DETAIL', 2, NOW(), 0),
(307, 2, 'CACHE_CONFIG',     'CACHE_CONFIG',     3, NOW(), 0),
-- TD0003
(308, 3, 'SYS_LOG',        'SYS_LOG',        1, NOW(), 0),
(309, 3, 'SYS_LOG_DETAIL', 'SYS_LOG_DETAIL', 2, NOW(), 0),
(310, 3, 'SYS_ALERT',      'SYS_ALERT',      3, NOW(), 0),
(311, 3, 'NOTIFY_LOG',     'NOTIFY_LOG',     4, NOW(), 0),
-- TD0101
(312, 4, 'TOKEN_INFO',    'TOKEN_INFO',    1, NOW(), 0),
(313, 4, 'USR_SESSION',   'USR_SESSION',   2, NOW(), 0),
(314, 4, 'ROLE_INFO',     'ROLE_INFO',     3, NOW(), 0),
(315, 4, 'PERM_RULE',     'PERM_RULE',     4, NOW(), 0),
(316, 4, 'LOAN_QUOTA',    'LOAN_QUOTA',    5, NOW(), 0),
(317, 4, 'CREDIT_LINE',   'CREDIT_LINE',   6, NOW(), 0),
(318, 4, 'QUOTA_CACHE',   'QUOTA_CACHE',   7, NOW(), 0),
(319, 4, 'RISK_RULE',     'RISK_RULE',     8, NOW(), 0),
(320, 4, 'BLACK_LIST',    'BLACK_LIST',    9, NOW(), 0),
(321, 4, 'CREDIT_SCORE',  'CREDIT_SCORE', 10, NOW(), 0),
(322, 4, 'SCORE_LOG',     'SCORE_LOG',    11, NOW(), 0),
(323, 4, 'LOAN_APPLY',    'LOAN_APPLY',   12, NOW(), 0),
(324, 4, 'CUSTOMER_INFO', 'CUSTOMER_INFO',13, NOW(), 0),
(325, 4, 'CREDIT_RECORD', 'CREDIT_RECORD',14, NOW(), 0),
(326, 4, 'NOTIFY_LOG',    'NOTIFY_LOG',   15, NOW(), 0),
(327, 4, 'WORK_FLOW',     'WORK_FLOW',    16, NOW(), 0),
(328, 4, 'FLOW_NODE',     'FLOW_NODE',    17, NOW(), 0),
-- TD0102
(329, 5, 'LOAN_QUOTA',     'LOAN_QUOTA',     1, NOW(), 0),
(330, 5, 'CUSTOMER_LEVEL', 'CUSTOMER_LEVEL', 2, NOW(), 0),
(331, 5, 'CREDIT_LINE',    'CREDIT_LINE',    3, NOW(), 0),
-- TD0103
(332, 6, 'REPAY_PLAN',    'REPAY_PLAN',    1, NOW(), 0),
(333, 6, 'LOAN_CONTRACT', 'LOAN_CONTRACT', 2, NOW(), 0),
(334, 6, 'INTEREST_RATE', 'INTEREST_RATE', 3, NOW(), 0),
(335, 6, 'RPT_FILE',      'RPT_FILE',      4, NOW(), 0),
-- TD0104
(336, 7, 'APPROVE_INFO', 'APPROVE_INFO', 1, NOW(), 0),
(337, 7, 'FLOW_NODE',    'FLOW_NODE',    2, NOW(), 0),
(338, 7, 'FLOW_LOG',     'FLOW_LOG',     3, NOW(), 0),
-- TD0105
(339, 8, 'REPAY_PLAN',   'REPAY_PLAN',   1, NOW(), 0),
(340, 8, 'LOAN_ACCT',    'LOAN_ACCT',    2, NOW(), 0),
(341, 8, 'TRANS_RECORD', 'TRANS_RECORD', 3, NOW(), 0),
(342, 8, 'LOAN_APPLY',   'LOAN_APPLY',   4, NOW(), 0),
(343, 8, 'NOTIFY_LOG',   'NOTIFY_LOG',   5, NOW(), 0),
-- TD0201
(344,  9, 'ACCOUNT_INFO',  'ACCOUNT_INFO',  1, NOW(), 0),
(345,  9, 'CUSTOMER_INFO', 'CUSTOMER_INFO', 2, NOW(), 0),
(346,  9, 'KYC_RECORD',    'KYC_RECORD',    3, NOW(), 0),
(347,  9, 'ACCT_LOG',      'ACCT_LOG',      4, NOW(), 0),
-- TD0202
(348, 10, 'ACCOUNT_INFO', 'ACCOUNT_INFO', 1, NOW(), 0),
(349, 10, 'TRANS_RECORD', 'TRANS_RECORD', 2, NOW(), 0),
(350, 10, 'BALANCE_LOG',  'BALANCE_LOG',  3, NOW(), 0),
(351, 10, 'NOTIFY_LOG',   'NOTIFY_LOG',   4, NOW(), 0),
(352, 10, 'SETTLE_LOG',   'SETTLE_LOG',   5, NOW(), 0),
-- TD0203
(353, 11, 'ACCOUNT_INFO', 'ACCOUNT_INFO', 1, NOW(), 0),
(354, 11, 'BALANCE_LOG',  'BALANCE_LOG',  2, NOW(), 0),
-- TD0204
(355, 12, 'ACCOUNT_INFO',  'ACCOUNT_INFO',  1, NOW(), 0),
(356, 12, 'INTEREST_RATE', 'INTEREST_RATE', 2, NOW(), 0),
(357, 12, 'INTEREST_LOG',  'INTEREST_LOG',  3, NOW(), 0),
(358, 12, 'SETTLE_LOG',    'SETTLE_LOG',    4, NOW(), 0),
-- TD0301
(359, 13, 'TRANS_ORDER',  'TRANS_ORDER',  1, NOW(), 0),
(360, 13, 'ACCOUNT_INFO', 'ACCOUNT_INFO', 2, NOW(), 0),
(361, 13, 'CLEAR_RECORD', 'CLEAR_RECORD', 3, NOW(), 0),
(362, 13, 'NOTIFY_LOG',   'NOTIFY_LOG',   4, NOW(), 0),
(363, 13, 'RISK_LOG',     'RISK_LOG',     5, NOW(), 0),
(364, 13, 'MSG_LOG',      'MSG_LOG',      6, NOW(), 0),
-- TD0302
(365, 14, 'TRANS_RECORD', 'TRANS_RECORD', 1, NOW(), 0),
(366, 14, 'RECON_FILE',   'RECON_FILE',   2, NOW(), 0),
(367, 14, 'CLEAR_RECORD', 'CLEAR_RECORD', 3, NOW(), 0),
(368, 14, 'EXPORT_LOG',   'EXPORT_LOG',   4, NOW(), 0),
-- TD0303
(369, 15, 'ACCOUNT_INFO',  'ACCOUNT_INFO',  1, NOW(), 0),
(370, 15, 'TRANS_RECORD',  'TRANS_RECORD',  2, NOW(), 0),
(371, 15, 'SETTLE_RECORD', 'SETTLE_RECORD', 3, NOW(), 0),
(372, 15, 'DAY_REPORT',    'DAY_REPORT',    4, NOW(), 0),
(373, 15, 'EOD_LOG',       'EOD_LOG',       5, NOW(), 0),
(374, 15, 'RPT_FILE',      'RPT_FILE',      6, NOW(), 0);

-- ─────────────────────────────────────────────
-- 关联关系表
-- relation_type:
--   SERVICE_TO_SERVICE      pcs→pbs
--   SERVICE_TO_COMPONENT    pbs→构件
--   COMPONENT_TO_COMPONENT  pbcb/pbcp→pbcc/pbct
--   COMPONENT_TO_DATA       构件→数据表
-- ─────────────────────────────────────────────
INSERT INTO t_relation (id, tx_id, relation_type, from_code, to_code, create_time, deleted) VALUES
-- ── TD0001 ──
(4001, 1, 'SERVICE_TO_COMPONENT', 'SVC_AUTH_001', 'COMP_AUTH_001', NOW(), 0),
(4002, 1, 'SERVICE_TO_COMPONENT', 'SVC_AUTH_001', 'COMP_ENC_001',  NOW(), 0),
(4003, 1, 'SERVICE_TO_COMPONENT', 'SVC_LOG_001',  'COMP_LOG_001',  NOW(), 0),
(4004, 1, 'COMPONENT_TO_DATA',    'COMP_AUTH_001','USR_INFO',      NOW(), 0),
(4005, 1, 'COMPONENT_TO_DATA',    'COMP_AUTH_001','USR_TOKEN',     NOW(), 0),
(4006, 1, 'COMPONENT_TO_DATA',    'COMP_ENC_001', 'USR_TOKEN',     NOW(), 0),
(4007, 1, 'COMPONENT_TO_DATA',    'COMP_LOG_001', 'SYS_LOG',       NOW(), 0),
(4008, 1, 'COMPONENT_TO_DATA',    'COMP_LOG_001', 'AUDIT_TRAIL',   NOW(), 0),
-- ── TD0002 ──
(4011, 2, 'SERVICE_TO_COMPONENT', 'SVC_PARAM_001','COMP_QUERY_001',NOW(), 0),
(4012, 2, 'SERVICE_TO_COMPONENT', 'SVC_PARAM_001','COMP_CACHE_001',NOW(), 0),
(4013, 2, 'SERVICE_TO_COMPONENT', 'SVC_CACHE_001','COMP_CACHE_001',NOW(), 0),
(4014, 2, 'COMPONENT_TO_DATA',    'COMP_CACHE_001','CACHE_CONFIG', NOW(), 0),
(4015, 2, 'COMPONENT_TO_DATA',    'COMP_QUERY_001','SYS_PARAM',    NOW(), 0),
(4016, 2, 'COMPONENT_TO_DATA',    'COMP_QUERY_001','SYS_PARAM_DETAIL',NOW(), 0),
-- ── TD0003 ──
(4021, 3, 'SERVICE_TO_SERVICE',   'SVC_ALERT_001','SVC_MSG_001',   NOW(), 0),
(4022, 3, 'SERVICE_TO_COMPONENT', 'SVC_LOG_002',  'COMP_LOG_001',  NOW(), 0),
(4023, 3, 'SERVICE_TO_COMPONENT', 'SVC_MSG_001',  'COMP_MSG_001',  NOW(), 0),
(4024, 3, 'COMPONENT_TO_DATA',    'COMP_LOG_001', 'SYS_LOG',       NOW(), 0),
(4025, 3, 'COMPONENT_TO_DATA',    'COMP_LOG_001', 'SYS_LOG_DETAIL',NOW(), 0),
(4026, 3, 'COMPONENT_TO_DATA',    'COMP_MSG_001', 'SYS_ALERT',     NOW(), 0),
(4027, 3, 'COMPONENT_TO_DATA',    'COMP_MSG_001', 'NOTIFY_LOG',    NOW(), 0),
-- ── TD0101 ──
(4031, 4, 'SERVICE_TO_SERVICE',      'SVC_NOTIFY_001','SVC_LOAN_001',   NOW(), 0),
(4032, 4, 'SERVICE_TO_SERVICE',      'SVC_NOTIFY_001','SVC_CREDIT_001', NOW(), 0),
(4033, 4, 'SERVICE_TO_SERVICE',      'SVC_FLOW_002',  'SVC_WORK_001',   NOW(), 0),
(4034, 4, 'SERVICE_TO_COMPONENT',    'SVC_AUTH_002',  'COMP_AUTH_BIZ_001',  NOW(), 0),
(4035, 4, 'SERVICE_TO_COMPONENT',    'SVC_LIMIT_001', 'COMP_QUOTA_PROD_001',NOW(), 0),
(4036, 4, 'SERVICE_TO_COMPONENT',    'SVC_RISK_001',  'COMP_RISK_BASE_001', NOW(), 0),
(4037, 4, 'SERVICE_TO_COMPONENT',    'SVC_LOAN_001',  'COMP_LOAN_PROD_001', NOW(), 0),
(4038, 4, 'SERVICE_TO_COMPONENT',    'SVC_CREDIT_001','COMP_RULE_001',      NOW(), 0),
(4039, 4, 'SERVICE_TO_COMPONENT',    'SVC_WORK_001',  'COMP_PROC_001',      NOW(), 0),
(4040, 4, 'COMPONENT_TO_COMPONENT',  'COMP_AUTH_BIZ_001',  'COMP_TOKEN_001',NOW(), 0),
(4041, 4, 'COMPONENT_TO_COMPONENT',  'COMP_AUTH_BIZ_001',  'COMP_PERM_001', NOW(), 0),
(4042, 4, 'COMPONENT_TO_COMPONENT',  'COMP_QUOTA_PROD_001','COMP_CALC_002', NOW(), 0),
(4043, 4, 'COMPONENT_TO_COMPONENT',  'COMP_QUOTA_PROD_001','COMP_CACHE_002',NOW(), 0),
(4044, 4, 'COMPONENT_TO_COMPONENT',  'COMP_RISK_BASE_001', 'COMP_RULE_002', NOW(), 0),
(4045, 4, 'COMPONENT_TO_COMPONENT',  'COMP_RISK_BASE_001', 'COMP_SCORE_001',NOW(), 0),
(4046, 4, 'COMPONENT_TO_COMPONENT',  'COMP_LOAN_PROD_001', 'COMP_VALID_001',NOW(), 0),
(4047, 4, 'COMPONENT_TO_COMPONENT',  'COMP_LOAN_PROD_001', 'COMP_RULE_001', NOW(), 0),
(4048, 4, 'COMPONENT_TO_DATA',       'COMP_TOKEN_001','TOKEN_INFO',    NOW(), 0),
(4049, 4, 'COMPONENT_TO_DATA',       'COMP_TOKEN_001','USR_SESSION',   NOW(), 0),
(4050, 4, 'COMPONENT_TO_DATA',       'COMP_PERM_001', 'ROLE_INFO',     NOW(), 0),
(4051, 4, 'COMPONENT_TO_DATA',       'COMP_PERM_001', 'PERM_RULE',     NOW(), 0),
(4052, 4, 'COMPONENT_TO_DATA',       'COMP_CALC_002', 'LOAN_QUOTA',    NOW(), 0),
(4053, 4, 'COMPONENT_TO_DATA',       'COMP_CALC_002', 'CREDIT_LINE',   NOW(), 0),
(4054, 4, 'COMPONENT_TO_DATA',       'COMP_CACHE_002','QUOTA_CACHE',   NOW(), 0),
(4055, 4, 'COMPONENT_TO_DATA',       'COMP_RULE_002', 'RISK_RULE',     NOW(), 0),
(4056, 4, 'COMPONENT_TO_DATA',       'COMP_RULE_002', 'BLACK_LIST',    NOW(), 0),
(4057, 4, 'COMPONENT_TO_DATA',       'COMP_SCORE_001','CREDIT_SCORE',  NOW(), 0),
(4058, 4, 'COMPONENT_TO_DATA',       'COMP_SCORE_001','SCORE_LOG',     NOW(), 0),
(4059, 4, 'COMPONENT_TO_DATA',       'COMP_VALID_001','LOAN_APPLY',    NOW(), 0),
(4060, 4, 'COMPONENT_TO_DATA',       'COMP_VALID_001','CUSTOMER_INFO', NOW(), 0),
(4061, 4, 'COMPONENT_TO_DATA',       'COMP_RULE_001', 'CREDIT_RECORD', NOW(), 0),
(4062, 4, 'COMPONENT_TO_DATA',       'COMP_RULE_001', 'NOTIFY_LOG',    NOW(), 0),
(4063, 4, 'COMPONENT_TO_DATA',       'COMP_PROC_001', 'WORK_FLOW',     NOW(), 0),
(4064, 4, 'COMPONENT_TO_DATA',       'COMP_PROC_001', 'FLOW_NODE',     NOW(), 0),
-- ── TD0102 ──
(4071, 5, 'SERVICE_TO_COMPONENT','SVC_QUOTA_001','COMP_CALC_001', NOW(), 0),
(4072, 5, 'SERVICE_TO_COMPONENT','SVC_QUOTA_001','COMP_CACHE_001',NOW(), 0),
(4073, 5, 'SERVICE_TO_COMPONENT','SVC_LEVEL_001','COMP_CACHE_001',NOW(), 0),
(4074, 5, 'COMPONENT_TO_DATA',   'COMP_CACHE_001','LOAN_QUOTA',    NOW(), 0),
(4075, 5, 'COMPONENT_TO_DATA',   'COMP_CACHE_001','CUSTOMER_LEVEL',NOW(), 0),
(4076, 5, 'COMPONENT_TO_DATA',   'COMP_CALC_001', 'CREDIT_LINE',   NOW(), 0),
-- ── TD0103 ──
(4081, 6, 'SERVICE_TO_SERVICE',  'SVC_REPORT_001','SVC_RPT_001',   NOW(), 0),
(4082, 6, 'SERVICE_TO_COMPONENT','SVC_REPAY_001', 'COMP_CALC_001', NOW(), 0),
(4083, 6, 'SERVICE_TO_COMPONENT','SVC_REPAY_001', 'COMP_DATE_001', NOW(), 0),
(4084, 6, 'SERVICE_TO_COMPONENT','SVC_CALC_001',  'COMP_CALC_001', NOW(), 0),
(4085, 6, 'SERVICE_TO_COMPONENT','SVC_RPT_001',   'COMP_FMT_001',  NOW(), 0),
(4086, 6, 'COMPONENT_TO_DATA',   'COMP_CALC_001', 'REPAY_PLAN',    NOW(), 0),
(4087, 6, 'COMPONENT_TO_DATA',   'COMP_CALC_001', 'INTEREST_RATE', NOW(), 0),
(4088, 6, 'COMPONENT_TO_DATA',   'COMP_DATE_001', 'LOAN_CONTRACT', NOW(), 0),
(4089, 6, 'COMPONENT_TO_DATA',   'COMP_FMT_001',  'RPT_FILE',      NOW(), 0),
-- ── TD0104 ──
(4091, 7, 'SERVICE_TO_COMPONENT','SVC_APPROVE_001','COMP_QUERY_001',NOW(), 0),
(4092, 7, 'SERVICE_TO_COMPONENT','SVC_APPROVE_001','COMP_CACHE_001',NOW(), 0),
(4093, 7, 'SERVICE_TO_COMPONENT','SVC_FLOW_001',   'COMP_CACHE_001',NOW(), 0),
(4094, 7, 'COMPONENT_TO_DATA',   'COMP_CACHE_001', 'FLOW_LOG',      NOW(), 0),
(4095, 7, 'COMPONENT_TO_DATA',   'COMP_QUERY_001', 'APPROVE_INFO',  NOW(), 0),
(4096, 7, 'COMPONENT_TO_DATA',   'COMP_QUERY_001', 'FLOW_NODE',     NOW(), 0),
-- ── TD0105 ──
(4101, 8, 'SERVICE_TO_SERVICE',  'SVC_NOTIFY_001', 'SVC_EARLY_001', NOW(), 0),
(4102, 8, 'SERVICE_TO_SERVICE',  'SVC_NOTIFY_001', 'SVC_ACCT_001',  NOW(), 0),
(4103, 8, 'SERVICE_TO_COMPONENT','SVC_PREPAY_001', 'COMP_VALID_001',NOW(), 0),
(4104, 8, 'SERVICE_TO_COMPONENT','SVC_PREPAY_001', 'COMP_CALC_001', NOW(), 0),
(4105, 8, 'SERVICE_TO_COMPONENT','SVC_CALC_001',   'COMP_CALC_001', NOW(), 0),
(4106, 8, 'SERVICE_TO_COMPONENT','SVC_EARLY_001',  'COMP_VALID_001',NOW(), 0),
(4107, 8, 'SERVICE_TO_COMPONENT','SVC_ACCT_001',   'COMP_LOCK_001', NOW(), 0),
(4108, 8, 'COMPONENT_TO_DATA',   'COMP_CALC_001',  'REPAY_PLAN',    NOW(), 0),
(4109, 8, 'COMPONENT_TO_DATA',   'COMP_CALC_001',  'LOAN_ACCT',     NOW(), 0),
(4110, 8, 'COMPONENT_TO_DATA',   'COMP_VALID_001', 'LOAN_APPLY',    NOW(), 0),
(4111, 8, 'COMPONENT_TO_DATA',   'COMP_LOCK_001',  'TRANS_RECORD',  NOW(), 0),
(4112, 8, 'COMPONENT_TO_DATA',   'COMP_LOCK_001',  'NOTIFY_LOG',    NOW(), 0),
-- ── TD0201 ──
(4121,  9, 'SERVICE_TO_SERVICE',  'SVC_NOTIFY_001',    'SVC_ACCT_OPEN_001',NOW(), 0),
(4122,  9, 'SERVICE_TO_COMPONENT','SVC_OPEN_001',       'COMP_AUTH_001',    NOW(), 0),
(4123,  9, 'SERVICE_TO_COMPONENT','SVC_OPEN_001',       'COMP_VALID_001',   NOW(), 0),
(4124,  9, 'SERVICE_TO_COMPONENT','SVC_KYC_001',        'COMP_AUTH_001',    NOW(), 0),
(4125,  9, 'SERVICE_TO_COMPONENT','SVC_ACCT_OPEN_001',  'COMP_ACCT_001',    NOW(), 0),
(4126,  9, 'COMPONENT_TO_DATA',   'COMP_AUTH_001',      'CUSTOMER_INFO',    NOW(), 0),
(4127,  9, 'COMPONENT_TO_DATA',   'COMP_AUTH_001',      'KYC_RECORD',       NOW(), 0),
(4128,  9, 'COMPONENT_TO_DATA',   'COMP_VALID_001',     'CUSTOMER_INFO',    NOW(), 0),
(4129,  9, 'COMPONENT_TO_DATA',   'COMP_ACCT_001',      'ACCOUNT_INFO',     NOW(), 0),
(4130,  9, 'COMPONENT_TO_DATA',   'COMP_ACCT_001',      'ACCT_LOG',         NOW(), 0),
-- ── TD0202 ──
(4131, 10, 'SERVICE_TO_SERVICE',  'SVC_NOTIFY_001', 'SVC_SETTLE_001', NOW(), 0),
(4132, 10, 'SERVICE_TO_COMPONENT','SVC_DEPOSIT_001','COMP_LOCK_001',  NOW(), 0),
(4133, 10, 'SERVICE_TO_COMPONENT','SVC_DEPOSIT_001','COMP_VALID_001', NOW(), 0),
(4134, 10, 'SERVICE_TO_COMPONENT','SVC_ACCT_001',   'COMP_LOCK_001',  NOW(), 0),
(4135, 10, 'SERVICE_TO_COMPONENT','SVC_SETTLE_001', 'COMP_MSG_001',   NOW(), 0),
(4136, 10, 'COMPONENT_TO_DATA',   'COMP_LOCK_001',  'ACCOUNT_INFO',   NOW(), 0),
(4137, 10, 'COMPONENT_TO_DATA',   'COMP_LOCK_001',  'TRANS_RECORD',   NOW(), 0),
(4138, 10, 'COMPONENT_TO_DATA',   'COMP_LOCK_001',  'BALANCE_LOG',    NOW(), 0),
(4139, 10, 'COMPONENT_TO_DATA',   'COMP_VALID_001', 'ACCOUNT_INFO',   NOW(), 0),
(4140, 10, 'COMPONENT_TO_DATA',   'COMP_MSG_001',   'NOTIFY_LOG',     NOW(), 0),
(4141, 10, 'COMPONENT_TO_DATA',   'COMP_MSG_001',   'SETTLE_LOG',     NOW(), 0),
-- ── TD0203 ──
(4151, 11, 'SERVICE_TO_COMPONENT','SVC_BALANCE_001',  'COMP_CACHE_001',NOW(), 0),
(4152, 11, 'SERVICE_TO_COMPONENT','SVC_BALANCE_001',  'COMP_QUERY_001',NOW(), 0),
(4153, 11, 'SERVICE_TO_COMPONENT','SVC_ACCT_QRY_001', 'COMP_QUERY_001',NOW(), 0),
(4154, 11, 'COMPONENT_TO_DATA',   'COMP_CACHE_001',   'BALANCE_LOG',   NOW(), 0),
(4155, 11, 'COMPONENT_TO_DATA',   'COMP_QUERY_001',   'ACCOUNT_INFO',  NOW(), 0),
(4156, 11, 'COMPONENT_TO_DATA',   'COMP_QUERY_001',   'BALANCE_LOG',   NOW(), 0),
-- ── TD0204 ──
(4161, 12, 'SERVICE_TO_SERVICE',  'SVC_BATCH_001',    'SVC_SETTLE_001',  NOW(), 0),
(4162, 12, 'SERVICE_TO_COMPONENT','SVC_INTEREST_001', 'COMP_CALC_001',   NOW(), 0),
(4163, 12, 'SERVICE_TO_COMPONENT','SVC_INTEREST_001', 'COMP_DATE_001',   NOW(), 0),
(4164, 12, 'SERVICE_TO_COMPONENT','SVC_ACCT_001',     'COMP_CALC_001',   NOW(), 0),
(4165, 12, 'SERVICE_TO_COMPONENT','SVC_SETTLE_001',   'COMP_MSG_001',    NOW(), 0),
(4166, 12, 'COMPONENT_TO_DATA',   'COMP_CALC_001',    'ACCOUNT_INFO',    NOW(), 0),
(4167, 12, 'COMPONENT_TO_DATA',   'COMP_CALC_001',    'INTEREST_RATE',   NOW(), 0),
(4168, 12, 'COMPONENT_TO_DATA',   'COMP_DATE_001',    'INTEREST_LOG',    NOW(), 0),
(4169, 12, 'COMPONENT_TO_DATA',   'COMP_MSG_001',     'SETTLE_LOG',      NOW(), 0),
-- ── TD0301 ──
(4171, 13, 'SERVICE_TO_SERVICE',  'SVC_NOTIFY_001',   'SVC_MSG_001',    NOW(), 0),
(4172, 13, 'SERVICE_TO_SERVICE',  'SVC_RISK_SVC_001', 'SVC_MSG_001',    NOW(), 0),
(4173, 13, 'SERVICE_TO_COMPONENT','SVC_TRANSFER_001', 'COMP_AUTH_001',  NOW(), 0),
(4174, 13, 'SERVICE_TO_COMPONENT','SVC_TRANSFER_001', 'COMP_LOCK_001',  NOW(), 0),
(4175, 13, 'SERVICE_TO_COMPONENT','SVC_CLEAR_001',    'COMP_QUERY_001', NOW(), 0),
(4176, 13, 'SERVICE_TO_COMPONENT','SVC_MSG_001',      'COMP_MSG_001',   NOW(), 0),
(4177, 13, 'COMPONENT_TO_DATA',   'COMP_AUTH_001',    'TRANS_ORDER',    NOW(), 0),
(4178, 13, 'COMPONENT_TO_DATA',   'COMP_AUTH_001',    'ACCOUNT_INFO',   NOW(), 0),
(4179, 13, 'COMPONENT_TO_DATA',   'COMP_LOCK_001',    'ACCOUNT_INFO',   NOW(), 0),
(4180, 13, 'COMPONENT_TO_DATA',   'COMP_LOCK_001',    'CLEAR_RECORD',   NOW(), 0),
(4181, 13, 'COMPONENT_TO_DATA',   'COMP_QUERY_001',   'CLEAR_RECORD',   NOW(), 0),
(4182, 13, 'COMPONENT_TO_DATA',   'COMP_QUERY_001',   'RISK_LOG',       NOW(), 0),
(4183, 13, 'COMPONENT_TO_DATA',   'COMP_MSG_001',     'NOTIFY_LOG',     NOW(), 0),
(4184, 13, 'COMPONENT_TO_DATA',   'COMP_MSG_001',     'MSG_LOG',        NOW(), 0),
-- ── TD0302 ──
(4191, 14, 'SERVICE_TO_SERVICE',  'SVC_EXPORT_001', 'SVC_FILE_001',   NOW(), 0),
(4192, 14, 'SERVICE_TO_COMPONENT','SVC_RECON_001',  'COMP_QUERY_001', NOW(), 0),
(4193, 14, 'SERVICE_TO_COMPONENT','SVC_FILE_001',   'COMP_FMT_001',   NOW(), 0),
(4194, 14, 'SERVICE_TO_COMPONENT','SVC_FILE_001',   'COMP_EXPORT_001',NOW(), 0),
(4195, 14, 'COMPONENT_TO_DATA',   'COMP_QUERY_001', 'TRANS_RECORD',   NOW(), 0),
(4196, 14, 'COMPONENT_TO_DATA',   'COMP_QUERY_001', 'CLEAR_RECORD',   NOW(), 0),
(4197, 14, 'COMPONENT_TO_DATA',   'COMP_FMT_001',   'RECON_FILE',     NOW(), 0),
(4198, 14, 'COMPONENT_TO_DATA',   'COMP_EXPORT_001','RECON_FILE',     NOW(), 0),
(4199, 14, 'COMPONENT_TO_DATA',   'COMP_EXPORT_001','EXPORT_LOG',     NOW(), 0),
-- ── TD0303 ──
(4201, 15, 'SERVICE_TO_SERVICE',  'SVC_REPORT_SVC_001','SVC_REPORT_001', NOW(), 0),
(4202, 15, 'SERVICE_TO_SERVICE',  'SVC_NOTIFY_SVC_001','SVC_REPORT_001', NOW(), 0),
(4203, 15, 'SERVICE_TO_COMPONENT','SVC_EOD_001',    'COMP_CALC_001',    NOW(), 0),
(4204, 15, 'SERVICE_TO_COMPONENT','SVC_EOD_001',    'COMP_BATCH_001',   NOW(), 0),
(4205, 15, 'SERVICE_TO_COMPONENT','SVC_SETTLE_001', 'COMP_CALC_001',    NOW(), 0),
(4206, 15, 'SERVICE_TO_COMPONENT','SVC_REPORT_001', 'COMP_FMT_001',     NOW(), 0),
(4207, 15, 'COMPONENT_TO_DATA',   'COMP_CALC_001',  'ACCOUNT_INFO',     NOW(), 0),
(4208, 15, 'COMPONENT_TO_DATA',   'COMP_CALC_001',  'TRANS_RECORD',     NOW(), 0),
(4209, 15, 'COMPONENT_TO_DATA',   'COMP_CALC_001',  'SETTLE_RECORD',    NOW(), 0),
(4210, 15, 'COMPONENT_TO_DATA',   'COMP_BATCH_001', 'DAY_REPORT',       NOW(), 0),
(4211, 15, 'COMPONENT_TO_DATA',   'COMP_BATCH_001', 'EOD_LOG',          NOW(), 0),
(4212, 15, 'COMPONENT_TO_DATA',   'COMP_FMT_001',   'RPT_FILE',         NOW(), 0);

SET FOREIGN_KEY_CHECKS = 1;

-- 验证
SELECT '领域数量' AS item, COUNT(*) AS cnt FROM t_domain WHERE deleted=0
UNION ALL
SELECT '交易数量', COUNT(*) FROM t_transaction WHERE deleted=0
UNION ALL
SELECT '服务节点', COUNT(*) FROM t_service_node WHERE deleted=0
UNION ALL
SELECT '构件节点', COUNT(*) FROM t_component_node WHERE deleted=0
UNION ALL
SELECT '数据表节点', COUNT(*) FROM t_data_table_node WHERE deleted=0
UNION ALL
SELECT '关联关系', COUNT(*) FROM t_relation WHERE deleted=0;
