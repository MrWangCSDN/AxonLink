# WHERE EXISTS 自定义 SQL 支持实施计划

**Goal:** 支持用户截图同形的外层单表 WHERE 单层 EXISTS/NOT EXISTS，并验证真实Axon生成脚本到BD Spark。
**Spec:** Obsidian现行《回放数据库比对字段登记》三件套2026-11-27 EXISTS章节，单一设计来源。
**Architecture:** 从外层WHERE的AND/OR/NOT布尔树登记允许的SQLExistsExpr子查询对象，逐层结构校验且全AST保留原限制。解析结果记录去重物理表集合，同一BASE只读连接/Schema逐表校验；外层source与结果列/键不变，脚本保留完整查询。

## Constraints / review focus

- 仅WHERE布尔谓词中的单层EXISTS/NOT EXISTS；并列可，深层不可，投影/HAVING/ORDER/IN/FROM派生/JOIN/WITH/UNION维持拒绝。
- 内层单个无schema物理表，投影单个无别名整数1或未限定*；无DISTINCT/聚合/分组/排序/分页/锁/副作用函数/参数。
- 用户最终确认：所有CUSTOM_SQL按外层结果字段名匹配来源表完整主键/唯一索引，不额外分析字段SQL表达式血缘。零候选解析报错，一候选自动填，多候选由用户选索引再整组填入结果比对键及参与字段；保存/版本均再次校验完整字段集合，历史编辑重新解析，冻结下载保持。
- 每个唯一物理来源均查同BASEschema pg_class r/p；缺表/视图/权限失败保持受控错误，不泄漏连接细节。
- 版本再次校验、NEW17完整scope及SQL双层包装、组合键、HASH分区、sample/full发布都需真实验证；无DDL。
- 当前server基于最新main c7831ee，保留其忽略配置新逻辑及06ee868免密钥功能；原dirty checkouts不碰。

## Tasks

- [x] 用户批准范围，先更新Obsidian设计；独立review已提出边界。
- [x] RED Analyzer/Generator正反例和可输出的真实三配置fixture。
- [x] 实现EXISTS准入/逐表可见性校验；153项GREEN并独立审查。
- [x] 真实Axon fixture→BD导入→Spark比较/HASH/发布回归，不手改脚本；BD全套349项含1现场Gauss跳过。
- [x] 所有CUSTOM_SQL完整来源键字段集合匹配与分析/保存/版本门禁；先RED后GREEN，最终专项203项通过。
- [x] 同步UI索引选择和升级提示，完整879项tests及生产build通过。
- [x] 服务完整verify/真实MySQL：1826项含5既有环境跳过；BD相关模块349项含1现场Gauss跳过，独立复核无阻断。

## 发布验证

发布时同步远程main后内嵌前端，使用既有package-source脚本输出源码ZIP，并在解压目录JDK17独立构建、核对233份前端资源与Java17类版本。提交哈希、最终包与构建记录放在交付目录的RELEASE-MANIFEST.json、BUILD-VERIFICATION.json及SHA256SUMS；现场仍由用户内网部署，无新增DDL。
