# 接口比对明细成功交易拆列升级说明

## 部署顺序

1. 在本系统当前使用的结果数据库执行 `src/main/resources/db/daoindex/V86__replay_interface_success_field_difference.sql`，每个数据库执行一次。
2. 构建并部署新版后端；导入、日报、周报复用同一套更新后的接口明细模型。

V86 是人工升级脚本，不会随本次源码交付自动执行。先完成两项新字段的添加，再启动新版后端。旧 `both_success` 和已有数据均保留；V56 原始建表脚本未修改。

## 新字段与模板

表为 `dii_replay_interface_comparison`：

| Excel 表头 | 数据库字段 | 类型 |
|---|---|---|
| 二者均成功且无字段差异 | `both_success_no_field_difference` | nullable BIGINT |
| 二者均成功且有字段差异 | `both_success_with_field_difference` | nullable BIGINT |

新 Excel 可以删除旧“二者均成功”。继续按表头名称读取，支持调换列顺序。两个新表头必须同时存在；只提供其中一个会拒绝整次导入，即使旧表头还存在也不会回退。

两个新表头完整时，以新列为准；数值都非空时安全相加保留内部兼容总数，有空值时总数为 NULL。空白不会变成 0；格式非法、非整数、超出 Long 范围或求和溢出会在写库前报错。明细及合计行均保存来源数值。

旧模板只有“二者均成功”时仍可导入。历史或旧模板数据的两个拆分值保持 NULL，不反推或分配旧总数。

新生成的日报、周报“接口比对明细”使用 19 列，发送交易量后依次为两个新成功数量，旧成功列不再输出；其余列按新模板排列。历史批次未记录拆分值时对应两格为空。既有报告快照保留其生成时内容；“汇总信息”页继续沿用原有输入和计算。

两项新数量超过 Excel 15 位整数精度时写完整十进制文本，正常数量保持数值格式，空值保持空白；再次导入仍精确解析为 Long。

## 验证

专项 58 项通过，包括新旧模板、表头乱序、半套新表头拒绝、249/1、250/0、合计行、历史 NULL、大整数导出回导，以及独立本地临时 MySQL 的真实 V56→V86 升级与 DAO 读写。

相关回放回归为 109 个测试类、852 项，0 失败/错误，3 项显式启用的 MySQL 测试在常规回归中跳过。两个已有基线失败的旧类 `ReplayDatabaseIssueTest`、`ReplayDatabaseIssueControllerTest` 未纳入该回归命令，未在本次修改。

常规专项命令：

```bash
mvn -Dtest='ReplayDailyWorkbookParserTest,ReplayDailyReportWorkbookWriterTest,ReplayDailyDataDaoTest,ReplayInterfaceComparisonSplitMigrationTest' test
```

MySQL 专项需要显式设置 `INTERFACE_SPLIT_MYSQL_REGRESSION=true` 并运行 `ReplayInterfaceComparisonSplitMysqlTest`；测试仅允许本地回环地址，创建并清理 UUID 临时数据库，不使用业务数据库。
