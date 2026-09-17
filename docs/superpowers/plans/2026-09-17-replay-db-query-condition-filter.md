# Replay Database Query Condition Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a standard header filter to the registration list’s “查询条件” column, including fuzzy candidate search, multi-select exact filtering, and a “全表” option in Mock and real backend modes.

**Architecture:** Reuse the existing header-filter panel and request flow. The frontend submits opaque condition keys; the backend groups by normalized `where_condition_json`, maps null to `__FULL_TABLE__`, formats readable SQL-style labels in one dedicated formatter, and applies exact values through bound SQL parameters. Current and version snapshot DAOs share the same condition-key semantics, while only the current registration page gains the new visible header control in this iteration.

**Tech Stack:** Vue 3, Vitest, Spring Boot, Java records, Spring JDBC, JUnit 5, H2/MySQL-compatible SQL.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-API接口.md`

## Global Constraints

- Preserve all unrelated dirty working-tree changes in both repositories; do not reset, stash, clean, or broadly overwrite files.
- Do not add a database column or migration; use existing normalized `where_condition_json` as the exact condition key.
- Use `__FULL_TABLE__` only as the transport sentinel for records whose `where_condition_json` is null.
- Candidate labels are either `全表` or the complete SQL-style expression without a leading `WHERE`.
- Candidate keyword matching is case-insensitive and runs against the readable label, so field names, operators, values, and `全表` are searchable.
- Multiple query-condition values use OR semantics; they combine with all other column filters using AND semantics.
- All SQL matching uses bound parameters; never concatenate a label or search keyword into SQL.
- Do not commit repository code unless the user explicitly requests it.

---

### Task 1: Backend condition keys and readable labels

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConditionLabeler.java`
- Create: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConditionLabelerTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareHeaderFilterRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareVersionQuery.java`

**Interfaces:**
- Produces: `ReplayDatabaseComparisonConditionLabeler.label(ReplayDbCompareConditionTree): String`, returning `全表` for null and a parenthesized SQL-style condition expression otherwise.
- Produces: `List<String> whereConditionValues()` on current query, header-filter request, and version query records.
- Uses: `ReplayDatabaseComparisonConditionCodec.encode/decode` as the only canonical condition-key serializer.

- [ ] **Step 1: Write the failing labeler test**

```java
@Test
void formats_full_table_and_nested_condition_labels() {
    assertEquals("全表", labeler.label(null));
    assertEquals("(status_cd = '1' AND amount BETWEEN 10 AND 20)", labeler.label(tree));
}
```

- [ ] **Step 2: Run the focused test and verify failure**

Run: `./mvnw -Dtest=ReplayDatabaseComparisonConditionLabelerTest test`

Expected: FAIL because `ReplayDatabaseComparisonConditionLabeler` does not exist.

- [ ] **Step 3: Implement the formatter and DTO fields**

Implement operator rendering for `EQ`, `NE`, `GT`, `GE`, `LT`, `LE`, `LIKE`, `IN`, `BETWEEN`, `IS_NULL`, and `IS_NOT_NULL`. Preserve condition values as configured, quote text values consistently with the existing frontend preview, join conditions and groups with their configured `AND`/`OR`, and normalize DTO lists to immutable empty lists when null.

- [ ] **Step 4: Run formatter and DTO-adjacent tests**

Run: `./mvnw -Dtest=ReplayDatabaseComparisonConditionLabelerTest,ReplayDatabaseComparisonControllerTest test`

Expected: PASS.

---

### Task 2: Current registration DAO and service filtering

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`

**Interfaces:**
- Consumes: `ReplayDbCompareQuery.whereConditionValues()` and `ReplayDbCompareHeaderFilterRequest.whereConditionValues()`.
- Produces: `targetColumn=whereCondition` options with raw canonical keys in `value`, readable labels in `label`, per-key counts, and `__FULL_TABLE__ / 全表` for null conditions.

- [ ] **Step 1: Add failing DAO tests for exact filtering and candidate grouping**

```java
assertEquals(List.of("acct_full"), tableNames(dao.search(queryWithConditions(List.of("__FULL_TABLE__")))));
assertEquals(Set.of("全表", "(status_cd = '1')"), labels(dao.headerFilterOptions(conditionRequest).options()));
```

Also assert selecting the full-table sentinel plus one configured key returns both groups, while an unrelated domain filter still restricts the result.

- [ ] **Step 2: Run DAO tests and verify failure**

Run: `./mvnw -Dtest=ReplayDatabaseComparisonDaoTest test`

Expected: FAIL because query DTOs and DAO filters do not yet recognize condition values or the target column.

- [ ] **Step 3: Implement parameter-bound condition filtering**

Extend `buildRegistrationFilter` with an excluded key named `whereCondition`. For selected values, create one parenthesized predicate: `r.where_condition_json IS NULL` when `__FULL_TABLE__` is present and `r.where_condition_json IN (?,...)` for configured keys. Join those branches with OR and append every JSON key as a JDBC argument.

Add a `whereCondition` header-column mode that groups by `COALESCE(r.where_condition_json,'__FULL_TABLE__')` without applying the keyword in SQL. In `ReplayDatabaseComparisonService.headerFilterOptions`, decode configured keys, generate labels through the labeler, apply case-insensitive keyword matching to labels, then enforce the requested limit and truncation metadata.

- [ ] **Step 4: Add service tests for label keyword behavior**

Assert `status_cd`, `=`, `'1'`, and `全表` each find the expected candidate; assert the returned `value` remains the opaque key and is never replaced by the label.

- [ ] **Step 5: Run current-list backend tests**

Run: `./mvnw -Dtest=ReplayDatabaseComparisonDaoTest,ReplayDatabaseComparisonServiceTest test`

Expected: PASS.

---

### Task 3: Version snapshot query parity

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonVersionDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonVersionDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionServiceTest.java`

**Interfaces:**
- Consumes: the same canonical condition keys and `__FULL_TABLE__` sentinel as the current list.
- Produces: version snapshot search and `/versions/{versionNo}/header-filter-options` behavior matching the current list without reading BASE metadata.

- [ ] **Step 1: Add failing version DAO tests**

Create one full-table snapshot and one configured-condition snapshot. Assert candidate labels/counts and exact multi-select filtering are identical to current-list semantics.

- [ ] **Step 2: Run version tests and verify failure**

Run: `./mvnw -Dtest=ReplayDatabaseComparisonVersionDaoTest,ReplayDatabaseComparisonVersionServiceTest test`

Expected: FAIL because version query filtering does not include condition keys.

- [ ] **Step 3: Implement version DAO filtering and service label mapping**

Apply the same null/IN parenthesized predicate against the version table’s `where_condition_json`. Keep label formatting in `ReplayDatabaseComparisonConditionLabeler`; do not duplicate operator formatting in the version DAO.

- [ ] **Step 4: Run version backend tests**

Run: `./mvnw -Dtest=ReplayDatabaseComparisonVersionDaoTest,ReplayDatabaseComparisonVersionServiceTest test`

Expected: PASS.

---

### Task 4: Main-page header filter and Mock behavior

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.spec.js`
- Mirror changes in isolated worktree: `/Users/java/axon-link-frontend/.worktrees/replay-db-compare-scope-20260917/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Mirror tests in isolated worktree: `/Users/java/axon-link-frontend/.worktrees/replay-db-compare-scope-20260917/src/components/replay/ReplayDatabaseComparisonPage.spec.js`

**Interfaces:**
- Consumes: header options with `targetColumn=whereCondition` and exact values submitted as `whereConditionValues`.
- Produces: a filter icon in the “查询条件” header using the existing anchored panel, yellow active state, fuzzy search, multi-select, full select, inverse select, clear, cancel, and apply behavior.

- [ ] **Step 1: Add failing component tests**

```js
await wrapper.get('[data-filter-key="queryCondition"]').trigger('click')
await wrapper.get('[data-testid="header-filter-search"]').setValue('status_cd')
await wrapper.get('[aria-label="查询筛选选项"]').trigger('click')
expect(wrapper.get('[data-testid="header-filter-option"]').text()).toContain("status_cd = '1'")
```

Add coverage for searching `全表`, selecting both full-table and configured options, sending `whereConditionValues`, combining with another column, and leaving field expansion state independent.

- [ ] **Step 2: Run the focused frontend test and verify failure**

Run from the isolated worktree: `npx vitest run src/components/replay/ReplayDatabaseComparisonPage.spec.js`

Expected: FAIL because the query-condition header has no filter button or criteria mapping.

- [ ] **Step 3: Implement the standard header filter**

Insert `{ key: 'queryCondition', label: '查询条件', width: '90px' }` immediately after `fields` in `filterColumns`; render it through the common header template. For Mock mode, use `__FULL_TABLE__` for empty conditions and stable `JSON.stringify(row.whereCondition)` for configured conditions, while labels come from the existing `queryConditionExpression(row)`. Map the key to backend `whereCondition`, and include `whereConditionValues: filters.queryCondition || []` in list criteria.

- [ ] **Step 4: Re-run the focused frontend test**

Run: `npx vitest run src/components/replay/ReplayDatabaseComparisonPage.spec.js`

Expected: PASS.

---

### Task 5: Regression verification and production build

**Files:**
- Generated frontend assets under `src/main/resources/static/` may change during the production build.

**Interfaces:**
- Verifies the complete current-list flow from UI criteria to backend exact filtering and candidate loading.

- [ ] **Step 1: Run the backend replay-database-comparison suite**

Run: `./mvnw -Dtest='com.axonlink.ai.replay.dbcompare.**' test`

Expected: all selected tests PASS.

- [ ] **Step 2: Run the complete frontend suite from the isolated worktree**

Run: `npm test`

Expected: all frontend tests PASS.

- [ ] **Step 3: Build the real-backend frontend bundle**

Run from `/Users/java/axon-link-frontend`: `VITE_USE_MOCK=0 npm run build`

Expected: Vite completes successfully and writes static assets into `/Users/java/axon-link-server/src/main/resources/static/`.

- [ ] **Step 4: Run whitespace and conflict checks**

Run:

```bash
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/axon-link-server diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 5: Verify the Mock page manually**

Open `http://127.0.0.1:5176/#replay-database-comparison-fields`; verify the filter panel anchors under “查询条件”, `status_cd` finds configured conditions, `全表` finds empty conditions, selecting multiple options filters rows correctly, and clearing the filter restores all 200 Mock rows.
