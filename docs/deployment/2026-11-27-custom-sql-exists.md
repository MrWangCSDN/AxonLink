# 自定义 SQL 的单层 EXISTS 过滤

本次只扩展“回放数据库比对字段登记”的CUSTOM_SQL分析、保存及版本门禁。普通结构化WHERE不增加自由SQL能力。部署采用本次规则的Axon服务；没有新增DDL，不需为EXISTS重跑既有V91/V99，也不要求BD生产代码或脚本格式升级。历史说明中“所有子查询均拒绝”仅对下述例外放宽。

## 可接受的写法

使用已登录的人员账号打开“自定义 SQL”，先分析结果列，再选择比对字段和结果键：

```sql
SELECT a.id, a.amount
FROM acct_main a
WHERE a.status = '1'
  AND EXISTS (
    SELECT 1 FROM acct_filter f
    WHERE f.acct_id = a.id AND f.enabled = '1'
  )
```

也允许 `NOT EXISTS (SELECT * FROM acct_filter f WHERE f.acct_id = a.id)`。外层WHERE可用AND/OR/NOT及括号组合多个并列单层节点，相关或非相关均可。外层计算列、聚合和普通GROUP/HAVING仍受下述完整来源键门禁约束。

## 解析后选择完整来源键

无论是否含EXISTS，“校验并解析结果字段”都会读取来源表的主键和有效唯一索引，按结果字段名匹配完整列集合，不额外分析结果字段的SQL表达式来源。联合键必须全列出现在结果中；键列使用源字段名或同名别名。解析响应新增 `keyOptions`，每项提供索引名称、主键/唯一索引类型及完整结果列名，`candidateKeyColumns`兼容保留。

没有任何完整匹配项时，解析直接报错并提示修改SQL；只有一项时自动选中；有多项时下拉列出索引类型、名称和完整列，由用户选择后自动填入结果比对键并勾选参与比对。键复选框只展示所选整组；不能手工拼出部分组合键。修改SQL清空解析结果及选择；编辑或复制历史登记须重新解析后才能保存，以免使用失效索引。

键元数据复用同一只读BASE连接、5秒超时及现行有效、非部分、非表达式唯一索引规则，保留既有nullable唯一索引政策。目录精确匹配来源表名，避免混入大小写不同的另一关系；列原名读取后按既有结果字段名规则匹配。保存和生成版本均再次确认所选结果键字段名恰好对应一组完整来源键，且全部参与比对。计算与聚合继续沿现有语法准入。旧配置若结果字段名不含任何完整来源键，将无法重新保存或生成版本；已有冻结版本和下载内容保持不变。

内层投影仅单个无别名整数1或未限定*，FROM仅一个不带schema的基础表，可有别名。示例的来源始终是 `acct_main`，人员/领域、固定代码和问题ID均沿外层表；辅助表只过滤，不生成额外字段或任务。

## 继续拒绝的范围

- IN子查询、用户输入的任意FROM派生表、JOIN、WITH、UNION。
- SELECT投影、HAVING、ORDER BY或其它位置的子查询；本次仅WHERE布尔树的EXISTS/NOT EXISTS例外。
- 内层更深查询、DISTINCT、多投影、table.*、计算/聚合、GROUP BY、HAVING、ORDER BY、LIMIT/OFFSET/FETCH、INTO、锁、窗口等禁止子句。
- 任意层级的参数占位符、会话/带owner函数、未列入既有纯函数白名单的调用、非基础类型CAST。
- 带schema的来源、辅助视图、物化视图或其它非基础关系。

例如 `EXISTS (SELECT COUNT(*) FROM acct_filter)`、`id IN (SELECT id FROM acct_filter)` 和 `EXISTS (SELECT 1 FROM (SELECT * FROM acct_filter) f)` 都应报错。不存在自动降级、改写或忽略不支持片段的路径。

## 环境与验收

外层及去重辅助表在同一只读BASE连接、同一 `replay-database-comparison.base-schema` 下逐表参数化查询pg_catalog.pg_class/pg_namespace，只接受r/p关系；同时通过 `pg_catalog.pg_table_is_visible` 确认该BASE表未被同名系统表或临时表遮蔽，避免校验对象与实际查询解析对象不一致。随后仍由不取数据的结果元数据查询验证输出列；65535字节SQL上限、5秒元数据超时及错误脱敏保持。缺表、类型不符、遮蔽、无权限或连接故障均不能保存半条配置或部分版本。

Axon只验证BASE目录。BD实际在来源和目标环境分别执行orig/dest SQL，两侧实际Schema都须有辅助表、相关字段并具备SELECT权限；不能据BASE存在性推断两侧已部署，也不能以跨schema引用绕过门禁。

保存后生成登记版本，核对来源、人员、结果键和固定代码仍归外层；下载并通过BD正式配置导入，再在受控环境核对EXISTS与NOT EXISTS的结果。生成器既有内部result_q包装保持，它不表示用户输入可用任意派生FROM。配置元信息和报表仍只展示外层来源；旧冻结脚本不因本次升级重新分析、执行或改写。

本次前端879项测试及生产构建通过，Axon完整verify为1826项（1821通过、5项既有环境跳过），BD service为245项（244通过、1项现场Gauss连接测试跳过）、Spark104项通过。真实生成脚本已验证导入、EXISTS/NOT EXISTS、抽样/全量发布和分桶读取。本地H2不能代替现场GaussDB部署验收。回退时不含EXISTS的旧单表配置沿旧规则处理，旧分析器不能重新保存/校验新增EXISTS SQL；冻结下载和受影响方案应在回退前核对，不自动改写SQL。
