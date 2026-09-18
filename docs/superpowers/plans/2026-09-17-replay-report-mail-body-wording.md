# Replay Report Mail Body Wording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将日报和周报中的查询、账务统计句式统一调整为“交易总交易、本轮回放实发交易、采集交易量、实发交易量”。

**Architecture:** 保持附件解析、指标计算、查询/账务排序和日报/周报结构不变，仅修改 `ReplayReportMailBodyComposer` 的文本拼接。通过组合器、服务和控制器现有测试覆盖最终输出。

**Tech Stack:** Java 17、Spring Boot 3.1.1、JUnit 5、AssertJ

**Spec:** 用户于 2026-09-17 确认的正文文案调整；该改动仅涉及展示文案，不新增接口或数据模型。

## Global Constraints

- 查询必须排在账务之前。
- 两类同时存在时分两行，第一行以中文分号结尾，最后一行以中文句号结尾。
- 周报保留“本周回放比对主要内容如下，请查阅，谢谢。”。
- 所有统计数值来源和计算逻辑保持不变。

---

### Task 1: 调整日报和周报统计句式

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailBodyComposer.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailBodyComposerTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailBodyServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `ReplayReportMailBodyComposer.compose(ReplayReportPeriod, List<FamilyMetrics>)`
- Produces: 文案调整后的邮件正文字符串，方法签名保持不变。

- [x] **Step 1: 更新测试期望并验证失败**

将查询与账务输出断言改为：

```text
查询交易总交易10，本轮回放实发交易9，采集交易量100，实发交易量100。
账务交易总交易20，本轮回放实发交易18，采集交易量200，实发交易量200。
```

运行：

```bash
mvn -Dtest=ReplayReportMailBodyComposerTest,ReplayReportMailBodyServiceTest,ReplayIssueControllerTest test
```

预期：旧模板导致正文断言失败。

- [x] **Step 2: 最小修改正文组合器**

将每类统计拼接改为：

```java
body.append(familyLabel(value.family()))
        .append("交易总交易").append(value.expectedTransactions())
        .append("，本轮回放实发交易").append(value.actualTransactions())
        .append("，采集交易量").append(value.collectedTransactions())
        .append("，实发交易量").append(value.collectedTransactions());
```

- [x] **Step 3: 运行专项测试**

```bash
mvn -Dtest=ReplayReportMailBodyComposerTest,ReplayReportMailBodyServiceTest,ReplayIssueControllerTest test
```

预期：全部通过。

- [x] **Step 4: 运行完整后端测试**

```bash
mvn test
```

预期：无失败、无错误。
