# 回放问题清单表头多选筛选实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在上方查询条件限定的数据集内，为七个表头增加类似 Excel 的搜索、多选筛选，并保持分页、总数和导出一致。

**Architecture:** 后端扩展查询对象和 SQL，以列表参数执行服务端筛选；新增候选值接口，应用上方查询条件和其他表头条件但排除当前列。前端维护独立表头筛选状态，每次应用后回到第一页，列表总数由后端重算，导出复用同一组参数。

**Tech Stack:** Spring Boot、Spring JDBC、Vue 3、Vitest。

## Global Constraints

- 仅修改并行回放问题清单。
- 同列多选使用 OR，不同列之间使用 AND。
- 候选值来自上方大条件限定的全部数据，不读取当前分页。
- 表头筛选改变后页码归零；导出包含相同筛选条件。

---

### Task 1: 后端多选筛选和候选值接口

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

- [ ] 扩展七个多选参数并生成参数化 `IN`/负责人匹配 SQL。
- [ ] 新增候选值接口，支持列名、搜索词和大条件。
- [ ] 验证筛选后的 count/list 使用相同条件。

### Task 2: 前端 Excel 式表头筛选

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Test: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`

- [ ] 七个表头增加筛选图标和搜索、多选、全选、清除、确定面板。
- [ ] 应用筛选时重置第一页并刷新服务端总数和列表。
- [ ] 上方重新查询时清空表头筛选；导出复用表头条件。

### Task 3: 文档和交付验证

**Files:**
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

- [ ] 同步服务端筛选和分页口径。
- [ ] 运行前后端专项测试、前端构建和后端打包。
