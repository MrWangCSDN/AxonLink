# Replay Issue Four Header Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 issue_id、流水号、全局流水号和缺陷修复日期补齐支持模糊候选搜索的 Excel 式页头多选筛选，并统一列表、计数和导出语义。

**Architecture:** 在 `ReplayIssueQuery` 末尾增加四个多值字段，DAO 使用统一空值语义和精确多值谓词；候选接口按字段白名单构造安全 SQL 表达式并使用包含搜索。前端只扩展既有 `headerFilterConfig`，Mock 对齐候选和值过滤，不复制筛选组件。

**Tech Stack:** Java 17、Spring MVC、JdbcTemplate、JUnit 5、Vue 3、Vitest、Vite Mock

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`、`并行回放问题清单-数据模型.md`、`并行回放问题清单-API接口.md`

## Global Constraints

- 四字段均支持候选包含搜索、全选、反选、多选和清空。
- “空”固定候选第一位；文本空匹配 NULL/空白，日期空匹配 NULL。
- 同字段多选 OR，跨字段及顶部条件 AND。
- 列表、count 和 Excel 导出使用同一查询字段。
- 候选接口字段必须来自固定白名单，不接受任意 SQL 列名。
- 现有顶部条件查询语义不变；问题明细分页、编辑和导入不受影响。
- 当前前后端工作区包含用户已有改动，不 reset、不清理、不提交混合变更。

---

### Task 1: Query model and DAO semantics

**Files:**
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `issueIds()`, `serialNos()`, `globalSerialNos()`, `defectRepairDates()`.
- Consumes: existing `appendIn`, new DATE-aware helper, and `headerFilterValues(field, query, keyword)`.

- [x] **Step 1:** Write DAO failing tests for four candidate fields, keyword contains search, empty-first ordering, multi-value OR and cross-field AND.
- [x] **Step 2:** Run `mvn -q -Dtest=ReplayIssueDaoTest test` and verify RED on unsupported fields/missing query accessors.
- [x] **Step 3:** Append four lists to `ReplayIssueQuery`, keep an auxiliary constructor for the old canonical signature, whitelist four candidate expressions, and append four predicates in `appendFilters`.
- [x] **Step 4:** Run the DAO test GREEN.

### Task 2: Controller list/options/export contract

**Files:**
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes/produces repeated request parameters `issueIds`, `serialNos`, `globalSerialNos`, `defectRepairDates` on list, header options and export.

- [x] **Step 1:** Write failing MVC tests proving candidate search and composed list filters reach real DAO results; verify export receives the same filters by workbook row contents.
- [x] **Step 2:** Run the focused controller test and verify RED.
- [x] **Step 3:** Add the four repeated params to all three endpoints plus single top filters to header options; construct the extended query consistently.
- [x] **Step 4:** Run controller focused GREEN.

### Task 3: Frontend and Mock integration

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`

**Interfaces:**
- Adds header config mappings `issue_id→issueId/issueIds`, `serial_no→serialNo/serialNos`, `global_serial_no→globalSerialNo/globalSerialNos`, `defect_repair_date→defectRepairDate/defectRepairDates`.

- [x] **Step 1:** Write failing Vue tests for four filter buttons, candidate keyword request, empty selection and composed list params.
- [x] **Step 2:** Run `npm test -- src/components/replay/ReplayIssuePage.spec.js` and verify RED.
- [x] **Step 3:** Extend the shared config and base candidate params; update Mock field map and exact multi-value filtering with empty semantics.
- [x] **Step 4:** Run frontend focused GREEN and `node --check mock/daoIndexMockServer.js`.

### Task 4: Full verification and local acceptance

- [x] **Step 1:** Run backend focused DAO/controller suites.
- [x] **Step 2:** Run `npm test` and `npm run build`.
- [x] **Step 3:** Run `git diff --check` in both repositories.
- [x] **Step 4:** Browser-verify all four buttons, fuzzy candidate searches, empty selection, multi-field composition, and mark the local Mock page deliverable.
