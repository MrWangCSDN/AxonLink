# 回放问题日报首次导入与滚动修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复日报首次导入上下区缺失、后续滚动错误、尾部说明行误导出和下载命名不一致，并确保问题统计按区域批次查询问题清单计算。

**Architecture:** 保留现有“落盘日报作为滚动快照”的架构。`ReplayIssueSummaryParser` 只负责从输入 Excel 识别合法静态指标行，`ReplayIssueDailyReportService` 根据日报目录是否存在历史文件选择首次或后续分支，并从最近日报下半区续接；动态问题列始终由 `ReplayIssueDao.findDailySlicesByBatch` 提供。

**Tech Stack:** Java 17、Spring Boot、Apache POI、JUnit 5、H2、Maven。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` 的“回放问题日报 Excel 快照（2026-08-18）”章节，以及 `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md` 的“回放问题日报下载”章节。

## Global Constraints

- 日报目录没有历史 `.xlsx` 文件时才是首次导入。
- 首次导入上下区分别取本次 Excel 上下区；后续导入上区取最近日报下区、下区取本次 Excel 下区。
- 日报测试入口不得写问题清单、历史、批次或汇总业务表。
- 问题总数及后续问题统计列必须按区域批次查询数据库计算，不能采用 Excel 内的计算值。
- 文件名统一为“本批次号 + 日报.xlsx”。
- 当前工作区已有用户改动；不得 reset、清理或提交无关文件，本轮不创建 Git 提交。

---

### Task 1: 用真实模板形态锁定解析边界

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParserTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParser.java`

**Interfaces:**
- Consumes: `ReplayIssueSummaryParser.parse(MultipartFile)`。
- Produces: `ParsedSummary.upperRows/lowerRows` 只包含具有领域和至少一个交易静态指标的数据行。

- [ ] **Step 1: 写失败测试**：构造上下两个双层表头区，在下半区数据后增加两行只有长文本、没有任何交易指标的说明，断言上下区各保留真实领域行且说明行不进入 `lowerRows`。
- [ ] **Step 2: 验证测试先失败**：运行 `mvn -Dtest=ReplayIssueSummaryParserTest#ignoresTrailingNarrativeRowsAfterLowerSection test`，预期现实现把说明文字当数据导致 FAIL。
- [ ] **Step 3: 最小修复**：横排和历史日报区域统一采用合法数据行判断；领域非空，且静态交易指标中至少一个非空，继续兼容合并批次单元格。
- [ ] **Step 4: 验证通过**：运行 `mvn -Dtest=ReplayIssueSummaryParserTest test`，预期 PASS。

### Task 2: 锁定首次与连续滚动数据来源

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`

**Interfaces:**
- Consumes: `generateNext(String currentBatch, LocalDateTime importedAt, ParsedSummary excelSummary)`。
- Produces: 首次快照使用输入上下区；第二、第三次快照上区精确继承前一快照下区。

- [ ] **Step 1: 写首次导入失败测试**：输入上一批次上区和本批次下区，断言生成文件同时包含两区各自静态指标，且不存在“批次号：无”。
- [ ] **Step 2: 写连续滚动失败测试**：连续生成三份日报，每次输入故意带一个不应采用的上区；断言第二、第三份上区分别来自上一份下区。
- [ ] **Step 3: 验证测试先失败**：运行两个新增测试，预期历史日报区段识别缺陷导致至少一个 FAIL。
- [ ] **Step 4: 最小修复**：历史日报上下区识别锚定到两个“交易核对分类统计”表头区域；首次直接使用输入上下区，后续只读取最近日报下区。历史下区读取为空时不再静默生成“无”上区。
- [ ] **Step 5: 验证通过**：运行 `mvn -Dtest=ReplayIssueDailyReportServiceTest test`，预期 PASS。

### Task 3: 锁定数据库统计与输出命名

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`

**Interfaces:**
- Consumes: `ReplayIssueDao.findDailySlicesByBatch(batchName)` 与日报下载接口。
- Produces: 两区问题统计按各自批次数据计算；落盘及下载名称为 `<本批次号>日报.xlsx`。

- [ ] **Step 1: 写统计保护测试**：为上一批次和本批次插入数量及状态明显不同的问题，断言上区问题总数来自上一批次、下区问题总数来自本批次。
- [ ] **Step 2: 写命名失败测试**：断言生成文件名及下载 `Content-Disposition` 均使用 `BATCH-CURR日报.xlsx`。
- [ ] **Step 3: 验证测试先失败**：运行日报服务和新增下载测试，预期文件名断言 FAIL。
- [ ] **Step 4: 最小修复**：统一 `locateReport`、生成目标路径、历史文件排除和下载名为 `<safeBatch>日报.xlsx`；动态统计仍只调用 DAO。
- [ ] **Step 5: 验证通过**：运行 `mvn -Dtest=ReplayIssueDailyReportServiceTest,ReplayIssueControllerTest test`，预期 PASS。

### Task 4: 集成回归与工作簿结构验收

**Files:**
- Verify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`
- Verify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: 正式导入和日报测试入口。
- Produces: 正式导入生成日报；测试入口仍零业务表写入。

- [ ] **Step 1: 运行专项回归**：执行 `mvn -Dtest=ReplayIssueSummaryParserTest,ReplayIssueDailyReportServiceTest,ReplayIssueImportServiceTest,ReplayIssueControllerTest test`，预期 PASS。
- [ ] **Step 2: 运行完整回归**：执行 `mvn test`，预期 BUILD SUCCESS；若有无关既有失败，记录证据。
- [ ] **Step 3: 工作簿验收**：POI 重开连续三份日报，确认只有两个可见数据区、没有尾部说明伪数据行，批次标题、静态指标和动态统计符合手工期望。
- [ ] **Step 4: 检查边界**：执行 `git status --short` 和 `git diff --check`，确认不影响用户其他改动。
