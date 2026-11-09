# 回放问题发送交易量升级说明

## 部署和使用

1. 在本系统结果数据库执行一次 `src/main/resources/db/daoindex/V87__replay_issue_sent_transaction_count.sql`。
2. 构建部署新版后端及其前端静态资源。
3. 重新导入包含“接口比对明细”的 Excel，为当前问题填入本次发送交易量。

V87 在 `dii_replay_issue` 增加可空 BIGINT `sent_transaction_count`。已有记录先保持 NULL，后续导入提供实际值；本脚本需人工执行，不会在本次源码打包时修改业务库。其他历史升级脚本仍按现有部署流程执行。

## 覆盖口径

- 页面和清单 Excel 在“出现笔数”前增加“发送交易量”，字段只读。
- 来源为本次 Excel“接口比对明细”的“发送交易量”，按交易码匹配。只去除交易码两端空白，保留前导零和大小写。
- 同码多条 DETAIL 求和，TOTAL 不参与；同码任一来源量未知时，结果为 NULL。完整计数安全求和，溢出会在写入前报错。
- 每次导入覆盖本次实际 RPT/DZ 批次族的全部当前问题；同码问题获得相同交易级数量，未匹配覆盖 NULL，0 保持为 0，重复导入不累加。
- 覆盖族优先取规范化后的日报批次，否则取首个非空问题批次。QUERY 请求可兼容原本为 DZ 的工作簿，实际只更新 DZ；DZ 模式继续先将 RPT 规范化为 DZ。
- 日报-only 也能更新这一个派生列。问题-only 或未提供接口明细时，同族发送量覆盖 NULL；不会读取旧数据库日报补本次来源值。无法识别批次族的旧日报-only 继续兼容，并跳过该列覆盖。
- 普通编辑、审核及状态变更保留发送量；原问题出现笔数和报表统计口径继续使用原字段。
- 问题合并、可选日报替换与发送量覆盖统一事务提交，失败整体回滚。

列表 GET/POST 返回 `sent_transaction_count` 为完整十进制字符串或 NULL，页面复制和清单 Excel 文本单元格保留长整数精度。列新增不增加筛选或排序，原出现笔数排序继续有效。

## 验证

发送交易量专项 13 项通过，覆盖按码汇总、合计排除、前导零、未知值、0、覆盖不累加、无接口明细清空、QUERY 原生 DZ 兼容、整次事务回滚、GET/POST 及 Excel 精度。包含一次独立本地 MySQL UUID 临时库的真实 V87 升级和 DAO 覆盖/隔离/回滚验证。前端 33 文件、572 项测试通过，独立审查未发现可行动问题。

相关回放回归 112 个类、865 项，0失败/错误，4项可选MySQL测试在常规回归中跳过。两个先前已确认存在基线失败的旧类 `ReplayDatabaseIssueTest`、`ReplayDatabaseIssueControllerTest` 未纳入该回归命令，本次未修改。

常规专项命令：

```bash
mvn -Dtest='ReplayIssueSentTransactionCountImportTest,ReplayIssueSentTransactionCountMigrationTest,ReplayIssueControllerTest#listsAndExportsSentTransactionCountsPreciselyBeforeOccurrenceCounts' test
```

MySQL 专项 `ReplayIssueSentTransactionCountMysqlTest` 需显式设置 `ISSUE_SENT_MYSQL_REGRESSION=true`；测试仅允许本地回环地址，独立创建并清理临时数据库。
