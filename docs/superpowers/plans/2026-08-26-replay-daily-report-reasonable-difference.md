# 交易日报合理差异与批次独立统计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在交易日报中增加按单批次计算的“合理差异”列，并使首次导入上半区为空、后续上下批次独立统计。

**Architecture:** 扩展日报批次切片，使 DAO 一次返回问题级别、状态、领域和沙箱；聚合 DTO 计算交易级“无需处理”数量。Excel 写入层在静态交易列中插入合理差异并统一移动后续列，首次无历史日报时不再消费输入上半区。

**Tech Stack:** Java 17、Spring JDBC、Apache POI、JUnit 5、H2

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 统计范围必须通过出现批次关系圈定。
- 上一批次与本批次不得合并统计。
- 首次导入上半区只保留表头，批次号与数据留空。
- 不新增数据库表或列，不提交现有脏工作区改动。

---

### Task 1: 批次切片和合理差异聚合

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/DailyIssueSlice.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/DailyReportRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Produces: `DailyIssueSlice.issueLevel()` 与 `DailyReportRow.reasonableDifferenceCount()`。

- [x] **Step 1: Write the failing test** — 构造同批次中交易级无需处理、字段级无需处理和交易级打开数据，断言只统计第一条。
- [x] **Step 2: Run test to verify it fails** — `mvn -Dtest=ReplayIssueDailyReportServiceTest#findDailySlicesByBatchReturnsIssueLevelAndCountsReasonableDifference test`
- [x] **Step 3: Write minimal implementation** — DAO 查询增加 `issue_level`，聚合器增加 `reasonableDifferenceCount`。
- [x] **Step 4: Run test to verify it passes** — 重新执行同一定向测试。

### Task 2: Excel 列和批次滚动

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Consumes: `DailyReportRow.reasonableDifferenceCount()`。
- Produces: 上下区均含“合理差异”，位置为“二者均成功”之后、“响应码忽略”之前。

- [x] **Step 1: Write the failing tests** — 分别验证首次导入上半区无领域数据、第二次导入上下批次合理差异独立、合计列正确。
- [x] **Step 2: Run tests to verify they fail** — `mvn -Dtest=ReplayIssueDailyReportServiceTest test`。
- [x] **Step 3: Write minimal implementation** — 首次导入设置空上半区；插入合理差异表头和数值；统一移动响应码忽略、成功率、通过率及后续问题统计列。
- [x] **Step 4: Run tests to verify they pass** — 重新执行日报服务测试。

### Task 3: 回归与工作簿验证

**Files:**
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Verify: generated `*.xlsx` in a temporary report directory

**Interfaces:**
- Consumes: 最终日报生成行为。
- Produces: 自动化回归证据与实际工作簿结构检查结果。

- [x] **Step 1: Run focused tests** — 执行日报、汇总解析与导入服务定向测试。
- [x] **Step 2: Generate workbook** — 用测试数据生成首次、第二次日报。
- [x] **Step 3: Inspect workbook** — 校验 Sheet、表头位置、首次空上半区、上下批次数据及合计。
- [x] **Step 4: Report unrelated failures** — 控制器套件仍有 3 个既有批次跟踪断言失败；另有 2 个 Java 25 下 Mockito inline mock 错误，与本次日报改动无关。
