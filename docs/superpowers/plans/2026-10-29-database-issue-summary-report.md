# 回放数据库问题清单批次汇总报表 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to execute the following tasks with test-first verification.

**Goal:** 数据库问题清单提供按批次选择的真实统计 Excel 报表，动态展示实际问题类型。
**Architecture:** 独立 report 包流式读取指定批次输入快照，结合当前问题类型及领域聚合，Apache POI 输出汇总及口径两页；独立弹窗调用完整批次目录和二进制下载接口。
**Tech Stack:** Java 17 / Spring JDBC / MySQL 8+ / H2 / Apache POI / Vue 3 / Vitest。
**Spec:** /Users/java/obsidian/01 Engineering/axon-link-server/回放数据库问题清单-系统设计.md 及数据模型、API接口。

## Global Constraints
- API：GET /api/ai/parallel-replay/database-issues/report/options → R.data {batches:[]}; POST /report/export {batchNo?:string} → XLSX。
- 所有已登录用户可读；默认业务日期最新的已导入批次，指定不存在或非法批次返回400，空库输出零值报表。
- 同批次最后导入incoming为空代表消失；无导入历史的旧数据才回退当前快照。指标来自批次输入，分类及领域来自当前数据库。
- 每表总量只计一次；字段差异求和不是去重主键数；字段数只表示当前唯一有效配置，缺失/歧义为null。
- 问题总数=本批次实际差异>0的issue数，未处理=类型空；动态分类守恒；合计进度按合计计数计算。
- 不修改业务数据、不新增迁移、不替换原清单明细导出，报表不受分页或其他列筛选影响。
- 当前原工作区有用户修改，独立baseline已保存；只按内容核对同步本任务改动，禁止覆盖其他变化。

## Review Focus
- 当前问题全部滚入新批次后，旧批次目录和历史输入仍可导出。
- 同批次先出现后消失不重复统计，类型改名即时生效，字段差异不造成表总量重复。
- 非法/不存在批次、空类型、空库和坏快照错误可读，鉴权或后端错误不能下载伪Excel。
- 配置缺失/跨schema歧义不伪造字段数；未分配领域不丢失总数。
- 下载失败保留批次选择可重试，加载中防重复，卸载后不发送旧close事件。

## Tasks
- [x] 读取既有SQL、页面与存储，确认按批次及历史批次用户选择；先写canonical设计并通过自审。
- [x] 使用独立后端/前端工作区保存当前未提交源码baseline。
- [x] Task 1: Report模型/Dao/Service及真实H2统计回归（backend agent）；先RED由root运行，再最小实现并验证GREEN。
  - 接口：ReplayDatabaseIssueReport(batchNo,issueTypes,rows,total,notes); nested Row(domain,total528,totalCcbs,only528Count,onlyCcbsCount,fieldDifferenceCount,comparedTableCount,fieldCount,identicalTableCount,issueCount,unprocessedCount,typeCounts)。
  - Service.build(String)、batchNames()；只读一致事务；scoped JDBC回调逐行JSON解析，聚合内存与表/分类规模有关。
- [x] Task 2: WorkbookWriter和ReportController（root），先测样式/数值/百分比/类型字符串安全与HTTP权限/参数/下载。
  - write(ReplayDatabaseIssueReport) → byte[]；汇总紫/黄表头、千分位与0.00%格式、加粗合计、统计口径页。
- [x] Task 3: 前端独立ReportDialog及API/Page入口（frontend agent），先RED再GREEN；完整目录加载/失败重试、默认最新/历史、下载失败与防重复、旧明细导出。
- [x] Task 4a: 后端定向与回放回归、前端完整suite及隔离构建；独立复核、源文件安全同步、构建前端至后端，生成样例XLSX。
- [x] Task 4b: 源码ZIP完整性、中文路径及与原工作区逐文件一致性验证后交付。
- [x] 文档：Obsidian三件套及overview/index/log提交推送并验证，仅同步确认归属的canonical任务章节。

## Verification Evidence

- 报表专项 21 项通过，0 失败/错误/跳过，包含独立本地 MySQL 数据库验证；原后端目录专项再次通过（20 项通过、1 项显式启用的 MySQL 测试跳过）。
- 回放相关回归 815 项，0 失败、0 错误、2 项可选 MySQL 测试跳过。排除已有基线失败的 `ReplayDatabaseIssueTest`、`ReplayDatabaseIssueControllerTest`，不将旧模块问题归为本次通过。
- 前端 33 个文件、554 项测试全部通过；隔离构建及原前端生产构建通过，最新页面已输出至原后端 static。
- 独立二审通过。导入口令认证、空历史批次的表级指标未知均有先失败后通过的回归证据。
- 设计文档提交 `dae87bd07d6c603cf7d21056670757f45a37dd52`，已验证远端并同步三份 canonical 页面，保留原目录其他修改。
- 未启动连接真实业务库的完整 Spring 服务；H2、MockMvc 和本地临时 MySQL 验证未修改真实业务数据。
- 交付归档 1148 个文件与原后端逐文件字节一致，7 个首页引用资源齐全，7 个中文文件名均带 UTF-8 标记；未包含开发机配置、备份 ZIP、构建缓存或 Git 目录。
