# 缺失日报明细页的问题清单-only导入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 当两个日报明细页都缺失时安全地只导入问题清单，同时拒绝半套日报页签。

**Architecture:** `ReplayDailyWorkbookParser` 保留严格三页解析入口，并增加面向导入编排的页签组合探测入口。`ReplayIssueImportService` 根据探测结果复用既有无日报合并分支；所有组合校验均在数据库事务前完成。

**Tech Stack:** Java 17、Spring、Apache POI、JUnit 5、H2

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 两个日报明细页都缺失时不解析、不校验、不写入任何日报数据。
- 两个日报明细页只存在一个时整次失败。
- 三个日报页都存在时保持现有联合导入。
- 问题清单-only模式至少存在一个问题页签，动账批次继续将 `RPT` 转为 `DZ`。
- 不改变问题合并、状态流转、自动修复和日报永久快照规则。

---

### Task 1: 页签组合探测

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParser.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParserTest.java`

**Interfaces:**
- Produces: `Optional<ReplayDailyWorkbookData> parseIfDailySheetsPresent(MultipartFile, ReplayIssueImportMode)`

- [x] **Step 1: 写失败测试**：覆盖两个明细页都缺失返回空、只缺一个时报错、明细齐全但汇总缺失时报错、三页齐全返回数据。
- [x] **Step 2: 验证测试按预期失败**：运行 `mvn -Dtest=ReplayDailyWorkbookParserTest,ReplayIssueImportServiceTest test`，确认新接口缺失导致测试编译失败。
- [x] **Step 3: 最小实现**：一次打开工作簿完成组合判定，并复用同一工作簿的严格解析私有方法，避免重复读取上传流。
- [x] **Step 4: 验证测试通过**：解析器与导入服务共 37 项通过。

### Task 2: 导入编排与事务边界

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`

**Interfaces:**
- Consumes: `ReplayDailyWorkbookParser.parseIfDailySheetsPresent(...)`

- [x] **Step 1: 写失败测试**：覆盖查询问题清单-only、动账前缀转换、只缺一个明细页整体失败、无日报且无问题页失败，并断言日报四表和问题事务均未被误写。
- [x] **Step 2: 验证测试按预期失败**：与页签探测测试一起确认红灯。
- [x] **Step 3: 最小实现**：切换到组合探测入口；无日报时要求至少一个问题页签并调用既有问题合并分支。
- [x] **Step 4: 验证测试通过**：导入、解析、DAO 与集成测试共 61 项通过。

### Task 3: 回归验证与交付

**Files:**
- Modify: `/Users/java/obsidian/log.md`

- [x] **Step 1: 运行相关导入、DAO 与日报测试。**
- [x] **Step 2: 使用 Java 17 运行全量测试并打包后端。**
- [x] **Step 3: 生产模式构建前端到后端静态资源目录。**
- [x] **Step 4: 生成后端源码 ZIP 并校验压缩包完整性。**
- [x] **Step 5: 追加实施日志，记录测试数量和产物路径。**
