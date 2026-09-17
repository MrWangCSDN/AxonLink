# Version History Query Condition Column Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an independent query-condition column to the replay database comparison version-history snapshot table.

**Architecture:** Reuse the immutable version snapshot's existing `whereCondition`, `whereSql`, `compareLimit`, and primary-key field metadata. The frontend formats the complete read-only scope as `WHERE + ORDER BY + LIMIT`, renders `全表` when empty, and removes scope details from the comparison-field column.

**Tech Stack:** Vue 3, Vitest, Vue Test Utils

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-系统设计.md`

## Global Constraints

- Version history remains read-only.
- No backend API or persistence change is required.
- Query-condition display follows the current registration list semantics.
- Existing uncommitted work in both repositories must be preserved.

---

### Task 1: Render the independent query-condition column

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonVersionHistory.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonVersionHistory.spec.js`

**Interfaces:**
- Consumes: version snapshot row properties `whereCondition`, `whereSql`, `compareLimit`, and `fields[].primaryKey/primaryKeyOrder`.
- Produces: a table cell with `data-testid="history-query-condition-<tableName>"` showing full scope text or `全表`.

- [x] **Step 1: Write the failing tests**

Add assertions that the table header contains `查询条件`, the configured row displays `WHERE (status = '1') ORDER BY acct_no LIMIT 1000` in its own cell, the comparison-field cell no longer contains scope badges, and an empty scope renders `全表`.

- [x] **Step 2: Run the focused test and verify RED**

Run: `npm test -- src/components/replay/ReplayDatabaseComparisonVersionHistory.spec.js`

Expected: FAIL because the history table does not yet contain an independent query-condition column.

- [x] **Step 3: Implement the minimal column**

Import and reuse `buildScopePreview`, derive ordered primary-key names from snapshot fields, render the new column after comparison fields, remove the old scope block from the field cell, update empty-state colspan, and allocate a fixed width for the new column.

- [x] **Step 4: Run focused and related tests**

Run: `npm test -- src/components/replay/ReplayDatabaseComparisonVersionHistory.spec.js src/components/replay/ReplayDatabaseComparisonPage.spec.js`

Expected: PASS.

- [x] **Step 5: Run production build and diff checks**

Run: `npm run build`

Run: `git diff --check`

Expected: both commands succeed without warnings introduced by this change.
