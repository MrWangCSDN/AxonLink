# 审核通过的无需处理交易量参与报表扣减 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in the existing isolated backend worktree. Preserve unrelated user changes and coordinate shared Maven output.

**Goal:** 汇总信息只扣除交易级、无需处理、审核通过的问题影响笔数。

**Architecture:** 内部 `ReplayDailyIssueStatisticRow` 增加 `ReplayIssueReviewStatus reviewStatus`，DAO 从既有 `review_status` 读取并归一化，计算器仅在 `noAction` 汇总处增加 `APPROVED` 条件。共用计算器的日报、周报、页面汇总、Excel 和合计行使用相同口径。

**Tech Stack:** Java 17、Spring JDBC、H2、JUnit 5、Apache POI。

**Spec:** Obsidian `01 Engineering/axon-link-server/并行回放问题清单-{系统设计,数据模型,API接口}.md` 中的审核通过后报表扣减规则；PR3 提交 `a96df2c`，canonical 已安全同步。

## Global Constraints

- 未审核、PENDING、null、空白或未知状态均不扣除；APPROVED 不以审核原因是否填写为条件。
- 仅影响 `noAction` 的展示及其作为成功率/比对通过率共用分母的扣减；问题总数/分类及其他交易分类对无需处理的既有排除口径保持。
- 仍按选定批次成员、当前问题字段、领域和沙箱统计，沿用问题 ID 去重和分母非正时返回 0 的行为。
- 无新增数据库字段、迁移或对外 JSON 字段；既有日报/周报快照通过显式重新生成更新。

## Review Focus

- PENDING/null/未知审核值不能当作已通过，legacy 已审核空原因仍认可。
- 不在 DAO 全局过滤无需处理，否则会改变其他问题统计。
- 审核通过的字段级问题和其他领域/沙箱/批次不能混入。
- 上下批次、合计和两类比例的扣减保持一致。
- 审批后的重新生成刷新 Excel 和汇总 JSON，已有快照下载保持原契约。

## Task 1: 审核状态贯通与回归

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyIssueStatisticRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculator.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Adapt fixtures: `ReplayDailyReportCalculatorTest.java`, `ReplayWeeklyReportServiceTest.java`

**Interface:** 内部 DTO 最后增加枚举字段，DAO `findDailyReportIssueStatistics(String)` 方法签名不变；`calculate` 接口不变。

- [x] 设计先写入现有 Obsidian 三件套并自审；原目录/隔离目录候选文件字节一致并记录 SHA 保护。
- [x] 既有 Calculator、DAO、日报、周报 78 项基线通过。
- [x] RED：真实 DAO 到日报 Excel，批准量 9、待审核量 2、无审核量 3、未知状态量 4，仅扣 9；字段级和其他批次/沙箱排除，旧实现实际扣 18，按预期失败。
- [x] GREEN：DAO 携带枚举，计算器增加审核通过过滤；旧测试显式提供批准状态，所有相关回归通过。
- [x] 审批待审核量 2 后重新生成扣 11，页面汇总及 Excel 和合计保持一致。
- [x] 独立复核通过；按 SHA 保护同步原工作区，仅提交隔离工作区中的本任务文件。

## Evidence

- Baseline: `ReplayDailyReportCalculatorTest` 10、`ReplayIssueDaoTest` 36、`ReplayIssueDailyReportServiceTest` 24、`ReplayWeeklyReportServiceTest` 8，共 78 项，失败/错误/跳过均为 0。
- RED：新日报集成回归失败于期望 `9.0`、实际 `18.0`，无运行错误。
- GREEN：同四类完整回归共 79 项（日报新增 1 项），失败/错误/跳过均为 0；独立复核及 `git diff --check` 通过。
