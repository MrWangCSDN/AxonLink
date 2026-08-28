# Replay Daily Report Statistics and Column Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正回放问题日报的问题分类、状态进度统计口径，并从生成日报中删除“CCBS失败明细”列。

**Architecture:** 保留 `DailyIssueSlice` 的问题清单原始切片，在 `DailyReportRow` 聚合层同时维护全部问题分类集合、已修复分类数量和状态数量；日报服务只负责动态列发现、分组映射和 Excel 布局。输入解析器及历史字段继续保留 `ccbsFailureDetail`，仅生成日报时不输出该列。

**Tech Stack:** Java 17、Spring JDBC、Apache POI、JUnit 5、H2、Maven。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 问题分类必须来自对应批次问题清单；`NULL`、空字符串和纯空格统一为“其他问题”，且“其他问题”固定最后。
- 所有统计按 `(group_name, is_sandbox)` 隔离。
- 上半区排查进度与下半区解决进度均为 `(已修复 + 修复待验证 + 延后修复) / 问题总数`；上轮问题解决率为 `已修复 / 问题总数`，均保留两位小数。
- 日报上下区及合计不输出“CCBS失败明细”；输入解析和数据模型继续兼容该字段。
- 使用 Java 17；不提交 Git，不覆盖无关工作区改动。

---

### Task 1: 动态问题分类与进度聚合

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/DailyReportRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Consumes: `DailyIssueSlice(String groupName, boolean sandbox, String issueType, String lastStatus)`。
- Produces: `DailyReportRow.issueTypes()` 返回该分组全部规范化问题分类；`DailyReportRow.inspectionProgress()` 使用解决进度口径。

- [x] **Step 1: 写失败测试覆盖动态分类、空值归类和公式**

在 `aggregateGroupsByGroupNameAndSandbox` 中加入非已修复分类与空分类，断言分类集合包含真实分类和最后的“其他问题”，并断言：

```java
assertEquals(List.of("代码问题", "数据差异", "其他问题"), row.issueTypes());
assertEquals(75.0, row.inspectionProgress());
```

在生成日报测试中断言没有已修复数量的问题分类仍产生表头，空分类显示“其他问题”，且各组已修复数量独立。

- [x] **Step 2: 运行测试并确认因旧逻辑失败**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -Dtest=ReplayIssueDailyReportServiceTest test
```

Expected: FAIL，旧实现只从 `fixedByIssueType` 发现列，且 `inspectionProgress()` 使用已分类数。

- [x] **Step 3: 最小实现新的聚合口径**

在 `DailyReportRow.aggregate` 中对每条切片先规范化问题分类并保存全部分类，再独立累加已修复分类数量；将进度计算改为：

```java
long progress = fixedCount
        + unresolvedByStatus.getOrDefault("延后修复", 0L)
        + unresolvedByStatus.getOrDefault("修复待验证", 0L);
return totalCount == 0 ? 0.0 : roundPercent(progress * 100.0 / totalCount);
```

日报动态表头从全部 `issueTypes()` 合并生成，“其他问题”排序到最后；单元格仍从 `fixedByIssueType` 取已修复数量。

- [x] **Step 4: 运行日报服务测试确认通过**

Run: 与 Step 2 相同。

Expected: PASS。

### Task 2: 删除生成日报的 CCBS失败明细列

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueSummaryRow.ccbsFailureDetail()` 继续存在但生成日报忽略。
- Produces: 上下区交易核对分类仅输出六个子列，后续统计列整体左移一列。

- [x] **Step 1: 写失败测试验证表头和位置**

在生成日报工作簿断言中加入：

```java
assertFalse(sheetToString(sheet).contains("CCBS失败明细"));
assertEquals(6, mergedTransactionChildCount(sheet, upperHeader));
```

并继续断言“问题总数”、动态问题分类、状态分类和百分比列能按表头定位并读取正确值，防止只删文字造成列错位。

- [x] **Step 2: 运行测试并确认旧输出失败**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -Dtest=ReplayIssueDailyReportServiceTest test
```

Expected: FAIL，旧日报仍输出“CCBS失败明细”。

- [x] **Step 3: 最小调整日报布局**

将交易子表头改为六列，`writeStaticMetrics` 和 `writeStaticTotals` 跳过 `ccbsFailureDetail`；同步调整交易父表头合并范围、成功率/通过率、问题总数、动态分类、状态分类、合计和列宽索引。

- [x] **Step 4: 运行日报专项测试**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -Dtest=ReplayIssueSummaryParserTest,ReplayIssueDailyReportServiceTest,ReplayIssueSummaryDaoTest,ReplayIssueSummaryImportIntegrationTest,ReplayIssueImportServiceTest test -Dstyle.color=never
```

Expected: 0 failures, 0 errors。

### Task 3: 构建与增量 ZIP

**Files:**
- Package: `src/main/java/com/axonlink/ai/replay/dto/DailyReportRow.java`
- Package: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Package: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Create: `axon-link-server-daily-report-statistics-column-fix-20260818.zip`

**Interfaces:**
- Consumes: Task 1、Task 2 已验证的三个增量文件。
- Produces: 可直接覆盖到同目录结构的 ZIP 包。

- [x] **Step 1: Java 17 构建**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -DskipTests package -Dstyle.color=never
```

- [x] **Step 2: 生成只含本次增量的 ZIP 并校验**

```bash
zip axon-link-server-daily-report-statistics-column-fix-20260818.zip \
  src/main/java/com/axonlink/ai/replay/dto/DailyReportRow.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java
unzip -t axon-link-server-daily-report-statistics-column-fix-20260818.zip
shasum -a 256 axon-link-server-daily-report-statistics-column-fix-20260818.zip
```

Expected: ZIP 中恰好三个文件，完整性检查无错误并输出 SHA-256。
