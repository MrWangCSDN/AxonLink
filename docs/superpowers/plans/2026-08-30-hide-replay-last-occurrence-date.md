# Hide Replay Last Occurrence Date Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove “上次出现日期” from the replay issue table and Excel export while preserving its stored value and list API field.

**Architecture:** Delete only the visible Vue column metadata and the corresponding workbook header/value entry. The DAO projection, DTO/map value, import/merge logic, database field, and list API response remain unchanged for compatibility.

**Tech Stack:** Vue 3, Vitest, Java 17, Spring MVC, Apache POI, JUnit 5

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- The page and Excel export do not contain “上次出现日期”.
- “首次出现日期” and “出现批次” remain adjacent visible/exported columns in that order.
- `last_occurrence_date` remains in storage, DAO results, and `GET /api/ai/parallel-replay/issues` responses.
- Import, merge, tracking, and date-calculation behavior do not change.
- Preserve unrelated uncommitted changes; do not commit, merge, push, reset, or clean either repository.

---

### Task 1: Remove the page column

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`

**Interfaces:**
- Consumes: existing `columns` metadata and list API rows containing `last_occurrence_date`.
- Produces: a table whose last visible columns are `首次出现日期`, `出现批次`; API row shape is unchanged.

- [ ] **Step 1: Write the failing component expectation**

Remove `上次出现日期` from `visibleColumnLabels`, delete the cell assertion for its formatted value, and keep:

```js
expect(headers.map(header => header.text())).toEqual(visibleColumnLabels)
expect(headers.map(header => header.text())).not.toContain('上次出现日期')
expect(cells.at(visibleColumnLabels.indexOf('首次出现日期')).text()).toBe('2026-07-28')
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: the rendered header list still contains “上次出现日期”.

- [ ] **Step 3: Apply the minimal Vue change**

Change the tail of the `columns` array from:

```js
['first_occurrence_date', '首次出现日期', '180px'], ['last_occurrence_date', '上次出现日期', '180px'],
['occurrence_rounds', '出现批次', '220px'],
```

to:

```js
['first_occurrence_date', '首次出现日期', '180px'],
['occurrence_rounds', '出现批次', '220px'],
```

Do not delete `dateOnlyDisplay` support for `last_occurrence_date`, because non-table compatible callers may still use the existing helper and API field.

- [ ] **Step 4: Run the focused component test and verify GREEN**

Run the Step 2 command. Expected: all focused frontend tests pass.

---

### Task 2: Remove the Excel export column and rebuild

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Verify generated output: `src/main/resources/static`
- Update: `/Users/java/obsidian/log.md`

**Interfaces:**
- Consumes: `GET /api/ai/parallel-replay/issues/export` and DAO maps containing `last_occurrence_date`.
- Produces: workbook without “上次出现日期”; list API remains unchanged.

- [ ] **Step 1: Write the failing export assertion**

In `exportsAllRowsMatchingQueryFilters`, replace the old data-cell assertion with:

```java
assertFalse(headers.contains("上次出现日期"));
assertEquals("2026-07-28", dataRow.getCell(headers.indexOf("首次出现日期")).getStringCellValue());
assertTrue(!dataRow.getCell(headers.indexOf("出现批次")).getStringCellValue().isBlank());
```

- [ ] **Step 2: Run the focused backend test and verify RED**

Run:

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn '-Dtest=ReplayIssueControllerTest#exportsAllRowsMatchingQueryFilters' test
```

Expected: `headers.contains("上次出现日期")` is still true.

- [ ] **Step 3: Remove the export header and matching value**

In `ReplayIssueController.export`, remove `"上次出现日期"` from `headers` and remove exactly `dateOnlyText(item.get("last_occurrence_date"))` from `values`. Keep `首次出现日期` immediately before `出现批次`, so every header still aligns with its value.

- [ ] **Step 4: Verify focused and full relevant tests**

Run:

```bash
cd /Users/java/axon-link-frontend && npm test
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn '-Dtest=ReplayIssueControllerTest#exportsAllRowsMatchingQueryFilters' test
```

Expected: frontend tests and the focused backend export test pass with zero failures.

- [ ] **Step 5: Rebuild backend static assets and compile**

Run:

```bash
cd /Users/java/axon-link-frontend && npm run build
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn -DskipTests compile
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/axon-link-server diff --check
```

Expected: production build, Java compilation, and both diff checks succeed.

- [ ] **Step 6: Append implementation evidence**

Append one `[IMPL]` line to `/Users/java/obsidian/log.md` with exact fresh test totals and build results. Do not change database schema, API row projection, or import logic.
