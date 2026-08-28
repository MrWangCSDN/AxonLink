# 交易日报成功率与比对通过率重算 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按扣除响应码忽略和合理差异的新分母重新计算交易日报两项比例，并保持批次滚动口径稳定。

**Architecture:** 日报写入层根据静态计数和批次合理差异重算行级与合计比例；比对通过率先从来源比例反算无字段差异交易数。本次 Excel 与历史日报使用不同反算分母，历史日报读取时保留百分比单元格完整数值精度。

**Tech Stack:** Java 17、Spring JDBC、Apache POI、JUnit 5、H2

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 当前 Excel 来源按旧分母反算，历史日报来源按新分母反算。
- 反算数量四舍五入并限制在 `0～二者均成功`。
- 分母小于等于 0 时输出 `0.00%`。
- 合计行按合计计数重算，不平均领域比例。
- 不新增数据库表、列或可见 Excel 列，不提交脏工作区。

---

### Task 1: 比例公式与边界测试

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`

**Interfaces:**
- Produces: 行级和合计行的新接口成功率、比对通过率。

- [ ] 写失败测试，覆盖合理差异扣减、反算四舍五入、上下限和非正分母。
- [ ] 运行定向测试并确认因旧比例直出逻辑失败。
- [ ] 实现最小比例计算函数并接入上下区域与合计行。
- [ ] 运行日报服务测试并确认通过。

### Task 2: 滚动来源与精度

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParser.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParserTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Produces: 精确百分比读取，以及当前 Excel/历史日报两种反算口径。

- [ ] 写失败测试，验证百分比底层精度和第二、第三批滚动结果稳定。
- [ ] 运行定向测试并确认失败原因正确。
- [ ] 对数值型百分比读取保留底层精度，并区分来源反算分母。
- [ ] 运行解析、日报和正式导入集成测试。

### Task 3: Excel 与构建验证

**Files:**
- Verify: generated first/second/third report workbooks

**Interfaces:**
- Produces: 可视结构、比例数值和构建验证证据。

- [ ] 生成首次、第二次和第三次日报。
- [ ] 检查首次空上半区以及后续上下区比例。
- [ ] 渲染工作簿并检查列宽、格式和百分比显示。
- [ ] 运行 Maven 打包与差异检查。
