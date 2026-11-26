# 数据库问题报表遗留数与一致字段数实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 数据库问题清单按批次导出报表增加“上一批遗留问题总数”和“比对一致字段数量”，说明上一日期批次来源，并核对跨表同名字段计数。

**Architecture:** 沿用只读一致事务、历史最后导入事件及版本领域/字段快照。先扫描所选批次汇总和未修复候选，关闭流后扫描上一日期批次匹配候选，避免 MySQL 流式查询期间嵌套查询。每表维护有正差异的字段问题集合，从登记字段总数减去集合大小。

**Tech Stack:** Java 17、Spring JDBC、Apache POI、JUnit 5、H2、MySQL。

**Spec:** Obsidian `01 Engineering/axon-link-server/回放数据库问题清单-{系统设计,数据模型,API接口}.md` 的 2026-11-26 报表指标章节；仅在 Obsidian 维护设计。

## Global Constraints

- 上一日期批次要求 `batch_date < 所选批次日期`，按 `batch_date DESC, imported_at DESC, batch_no DESC` 取第一项；同日多批不作为上一日期，最近日期多批取最后导入者。
- 遗留数仅计两批最后导入仍有 `differenceCount > 0` 的同一问题，当前 `defect_repair_date` 为 NULL、空串或全空格；所选批次缺席的 ID 直接排除。
- 当前状态为“不参与比对”的记录继续排除，按所选报表领域归属。无上一日期批次为 0。
- A.a 和 B.a 是两个字段；一致字段数扣除有差异的字段数量，不扣差异数据笔数或表级问题。
- 登记字段总数未知、表原始明细不完整或差异字段数超出登记字段数，一致字段数显示“—”；禁止用零掩盖未知。
- 保留旧 DTO 构造器；请求接口、数据库结构及前端无需变更。

## Review Focus

- 同日重导和历史 NULL 消失事件不恢复已移除成员。
- 状态与修复日期矛盾时以日期判断遗留，状态只用于现有“不参与比对”排除。
- 两批领域变化时按所选批次归属，前一版本失效不影响所选版本。
- 未绑定当前字段配置变化导致差异字段多于总数时，一致字段数未知且合计传播未知。
- 实际 MySQL 两次流式读取均关闭后才执行后续查询，保持现有一致事务。

### Task 1: 日期批次与当前修复日期读取

- [x] DAO 增加 `String previousDateBatchName(String batchNo)`，严格按上述日期和排序选择。
- [x] `ImportedIssue` 追加 `String defectRepairDate`，直接读当前投影列，保留最后导入快照读取逻辑。
- [x] 报表测试安装修复日期列；新增 DAO 测试验证无上一日期、同日排除、最近日期、最后导入和同时间稳定排序。
- [x] 运行 DAO 与现有报表回归，记录结果。

### Task 2: 两项指标汇总与边界测试

- [x] 新增集成测试，先观察新增指标失败：跨批交集、正差异、当前日期空白、缺席忽略、重导消失、字段每表去重。
- [x] 所选批次扫描保存候选 `Map<Long, DomainTotals>`；前批流只解析候选、匹配正差异后计数一次。
- [x] 每表维护差异字段 ID 集合，逐表计算一致字段数；字段数未知/超界及表原始不完整传播未知。
- [x] `Row` 追加 `Long identicalFieldCount, long previousUnrepairedIssueCount`；顶层报表追加 `String previousBatchNo`，兼容既有构造器。
- [x] notes 明确两个指标、表字段组合语义、前批完整编号及无前批零值。
- [x] 覆盖绑定版本领域变化、同名跨表字段、修复状态与日期不一致、全部排除/空库、未知及超界。

### Task 3: Excel 新列与整体验证

- [x] 在“字段总数”后加入“比对一致字段数量”，在“问题总数（注）”后加入“上一批遗留问题总数”；调整动态类型起点、颜色分界和旧格式未知列。
- [x] 元数据及“统计口径”展示前批编号；更新工作簿测试及原有列位置断言，检查合计与进度。
- [x] Java 17 下运行全部报表专项，再运行 `REPORT_MYSQL_REGRESSION=true` 的真实 MySQL 专项，最后 `mvn test`。
- [x] 新审查者核对完整改动，修复实际发现并补验证；保留样例 XLSX 供核对。
- [x] 更新本计划执行记录并报告代码工作区、验证结果与文档链接。

## 执行记录

- 需求口径及上一日期同日多批选择已由用户确认。
- 已核查现有字段总数按表计数；历史测试 A 表3字段、B表4字段（字段同名）总计7。
- 基线工作区：`/Users/wangshanhe/.codex/worktrees/dbissue-report-carryover-fields/axon-link-server`，分支 `codex/dbissue-report-carryover-fields`，基于 `aac1d46`。

- 设计已完成并推送：Obsidian 提交 `879b2ef`，[PR #17](https://github.com/MrWangCSDN/obsidian-vault/pull/17)。
- 新增指标8项先断言失败再转绿；DAO6项、Writer6项通过；报表专项53项含真实MySQL全部通过。
- 独立审查无阻断；已补批次同导入时间决胜说明与匹配候选坏快照第9项回归。
- 首轮全量1702项：0失败、2错误、6跳过；两个错误是隔离工作区缺少忽略的既有静态资源。已复用原工作区同版产物，最终全量已在完整资源环境中通过。

- 最终全量（Java17，REPORT_MYSQL_REGRESSION=true）：{'tests': 1703, 'failures': 0, 'errors': 0, 'skipped': 5}，退出码0；报表专项54项（含指标9项、真实MySQL1项）全部通过。
- 示例 XLSX：`/private/tmp/axon-dbissue-report-carryover-fields-example.xlsx`；全部代码保留在隔离功能分支，未操作主工作区未知修改。
