# 回放日报周报排除“无需处理”实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 日报和周报的问题总数、已解决问题分类、上一批次未解决问题分类及相关比例统一排除状态为“无需处理”的问题。

**Architecture:** 日报与周报共用 `ReplayDailyReportCalculator`，因此只在计算器的统计输入中排除“无需处理”，Workbook Writer 保持纯展示职责。保留交易核对区域原有的“无需处理”交易数量逻辑，不改变成功率和比对通过率的既有业务口径。

**Tech Stack:** Java 17、Spring Boot、JUnit 5、Apache POI

**Spec:** 当前会话已确认的方案 A 与统计口径。

## Global Constraints

- 日报和周报使用同一统计口径。
- “无需处理”不进入问题总数、问题分类、上一批次未解决分类。
- 以问题总数为分母的排查进度、解决进度和上一批次问题解决率同步重算。
- 不影响交易核对区域单独展示的“无需处理”交易数量。

---

### Task 1: 共用计算器统计口径

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculator.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculatorTest.java`

**Interfaces:**
- Consumes: `ReplayDailyIssueStatisticRow.issueStatus()`
- Produces: `ReplayDailyReportCalculator.calculate(...)` 返回排除“无需处理”后的 `CalculatedReport`

- [ ] **Step 1: 写失败测试**

在上一批次和当前批次数据中同时放入“无需处理”、已修复和未解决问题，断言：

```java
assertEquals(2L, report.previousTotal().issueTotal());
assertEquals(2L, report.currentTotal().issueTotal());
assertEquals(0L, report.previousTotal().reasonableDifferenceIssueCount());
assertEquals(1L, report.previousUnresolved().total());
```

同时断言 `investigationProgress()`、`previousResolutionRate()` 使用排除后的分母。

- [ ] **Step 2: 验证测试失败**

运行：

```bash
JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayDailyReportCalculatorTest test
```

预期：问题总数或分类仍包含“无需处理”，测试失败。

- [ ] **Step 3: 实现最小修复**

在 `calculate(...)` 建立上一批次和当前批次问题集合时，过滤：

```java
private static boolean isReportableIssue(ReplayDailyIssueStatisticRow issue) {
    return !NO_ACTION.equals(normalizeText(issue.issueStatus()));
}
```

所有问题总数、分类、上一批次未解决和比例均基于过滤后的集合；交易核对的 `noAction` 继续读取原始集合。

- [ ] **Step 4: 验证计算器测试通过**

运行 Task 1 Step 2 的命令，预期全部通过。

---

### Task 2: 日报与周报服务回归

**Files:**
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportServiceTest.java`

**Interfaces:**
- Consumes: `ReplayDailyReportCalculator.calculate(...)`
- Produces: 日报和周报 Excel 中一致的排除口径

- [ ] **Step 1: 增加日报服务测试**

插入含“无需处理”的问题数据，生成日报并读取汇总单元格，断言问题总数及三类统计区域均未计入该记录。

- [ ] **Step 2: 增加周报服务测试**

起止批次均插入“无需处理”记录，生成周报并读取汇总单元格，断言与日报口径一致。

- [ ] **Step 3: 运行服务级测试**

```bash
JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayIssueDailyReportServiceTest,ReplayWeeklyReportServiceTest test
```

预期：全部通过。

- [ ] **Step 4: 运行完整回归**

```bash
JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" mvn test
```

预期：无新增失败。
