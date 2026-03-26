-- ─────────────────────────────────────────────────────────────────────────────
-- 外网本地测试数据：flowtran（来源：截图 benchmarkdb.flowtran 表）
-- 领域：dept（存款/对公存款），package_path 第4段 = dept
-- 共 21 条记录，涵盖 frcCtrl / qryMnt / opnCnclAc 三个模块
-- ─────────────────────────────────────────────────────────────────────────────

-- 清除旧测试数据（如已存在）
DELETE FROM flowtran WHERE id IN (
  'TC0076','TC0094','TC0099',
  'TC0022','TC0023','TC0024','TC0025','TC0026','TC0027','TC0028',
  'TC0029','TC0030','TC0031',
  'TC0032','TC0033','TC0034','TC0035','TC0036','TC0037','TC0040','TC0041'
);

-- ── 存款领域 - frcCtrl（利率管制类）────────────────────────────────────────
INSERT INTO flowtran (id, longname, package_path, txn_mode, from_jar, domain_key) VALUES
('TC0076', '定时利率调整计划执行',     'com.spdb.ccbs.dept.pbf.trans.frcCtrl', 'R', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/frcCtrl/TC0076.flowtrans.aml', 'dept'),
('TC0094', '浏览网络查批量结算信息查询', 'com.spdb.ccbs.dept.pbf.trans.frcCtrl', 'R', 'D:\\22222\\ccbs-dept-impl\\dept-pbf\\src\\main\\resources\\trans\\frcCtrl\\TC0094.flowtrans.aml', 'dept'),
('TC0099', '对公联机批量结算落地控制解析','com.spdb.ccbs.dept.pbf.trans.frcCtrl', 'R', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/frcCtrl/TC0099.flowtrans.aml', 'dept');

-- ── 存款领域 - qryMnt（账户查询与维护类）──────────────────────────────────
INSERT INTO flowtran (id, longname, package_path, txn_mode, from_jar, domain_key) VALUES
('TC0022', '对公客户账号子账户列表查询',    'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/qryMnt/TC0022.flowtrans.aml', 'dept'),
('TC0023', '对公客户信息查询',              'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'R', 'D:\\22222\\ccbs-dept-impl\\dept-pbf\\src\\main\\resources\\trans\\qryMnt\\TC0023.flowtrans.aml', 'dept'),
('TC0024', '对公通联账户信息查询',           'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'R', 'D:\\22222\\ccbs-dept-impl\\dept-pbf\\src\\main\\resources\\trans\\qryMnt\\TC0024.flowtrans.aml', 'dept'),
('TC0025', '对公账户余额及发生额查询',       'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'R', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/qryMnt/TC0025.flowtrans.aml', 'dept'),
('TC0026', '对公活期账户发生明细查询',       'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'R', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/qryMnt/TC0026.flowtrans.aml', 'dept'),
('TC0027', '对公客户账户维护',              'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/qryMnt/TC0027.flowtrans.aml', 'dept'),
('TC0028', '活期帐户变约关系检查',           'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/qryMnt/TC0028.flowtrans.aml', 'dept'),
('TC0029', '根据活期帐户查询关联定期账户',   'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'R', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/qryMnt/TC0029.flowtrans.aml', 'dept'),
('TC0030', '对公活期帐户账单发生查询',       'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'R', 'D:\\22222\\ccbs-dept-impl\\dept-pbf\\src\\main\\resources\\trans\\qryMnt\\TC0030.flowtrans.aml', 'dept'),
('TC0031', '对公活期账户账单发生明细查询',   'com.spdb.ccbs.dept.pbf.trans.qryMnt', 'R', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/qryMnt/TC0031.flowtrans.aml', 'dept');

-- ── 存款领域 - opnCnclAc（开销户类）──────────────────────────────────────
INSERT INTO flowtran (id, longname, package_path, txn_mode, from_jar, domain_key) VALUES
('TC0032', '账户开户意愿核实',              'com.spdb.ccbs.dept.pbf.trans.opnCnclAc', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/opnCnclAc/TC0032.flowtrans.aml', 'dept'),
('TC0033', '对公活期开户',                  'com.spdb.ccbs.dept.pbf.trans.opnCnclAc', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/opnCnclAc/TC0033.flowtrans.aml', 'dept'),
('TC0034', '核准新客户账户信息',             'com.spdb.ccbs.dept.pbf.trans.opnCnclAc', 'A', 'D:\\22222\\ccbs-dept-impl\\dept-pbf\\src\\main\\resources\\trans\\opnCnclAc\\TC0034.flowtrans.aml', 'dept'),
('TC0035', '对公账户启用',                  'com.spdb.ccbs.dept.pbf.trans.opnCnclAc', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/opnCnclAc/TC0035.flowtrans.aml', 'dept'),
('TC0036', '对公外币通账户复制',             'com.spdb.ccbs.dept.pbf.trans.opnCnclAc', 'A', 'D:\\22222\\ccbs-dept-impl\\dept-pbf\\src\\main\\resources\\trans\\opnCnclAc\\TC0036.flowtrans.aml', 'dept'),
('TC0037', '对公外币合一账户分录解析',       'com.spdb.ccbs.dept.pbf.trans.opnCnclAc', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/opnCnclAc/TC0037.flowtrans.aml', 'dept'),
('TC0040', '对公通联客户账户开户',           'com.spdb.ccbs.dept.pbf.trans.opnCnclAc', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/opnCnclAc/TC0040.flowtrans.aml', 'dept'),
('TC0041', '对公定期开户',                  'com.spdb.ccbs.dept.pbf.trans.opnCnclAc', 'A', 'ccbs-dept-impl.master:dept-pbf/src/main/resources/trans/opnCnclAc/TC0041.flowtrans.aml', 'dept');
