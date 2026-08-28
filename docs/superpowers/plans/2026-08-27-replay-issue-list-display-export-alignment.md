# Replay Issue List Display and Export Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement screenshot optimization items 1–6 so the replay issue page and Excel export share the requested columns, order, date format, colors, and widths.

**Architecture:** Keep persistence and query projections unchanged. Apply visual-only rules in `ReplayIssuePage.vue`, and separately lock the same business column order in `ReplayIssueController.export`; focused tests prevent page/export drift without introducing a dynamic column-schema API.

**Tech Stack:** Vue 3, Vitest, Spring Boot MVC, Apache POI SXSSF, JUnit 5, MockMvc.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Implement optimization items 1–6 only; do not implement completion statistics item 7.
- Remove batch, import time, registered time, and historical occurrence count from both page and Excel export, but preserve their database fields and business usage.
- Keep first/last occurrence dates in `yyyy-MM-dd` on both page and export.
- Do not alter import, filtering, status-machine, tracking, or persistence behavior.
- Preserve all unrelated dirty-worktree changes.

---

### Task 1: Replay issue page columns and presentation

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`

**Interfaces:**
- Consumes: existing row fields returned by `listReplayIssues()`.
- Produces: `displayColumn(key, value, row)` date-only display; page column order matching the approved design.

- [ ] **Step 1: Write failing page tests**

Update `visibleColumnLabels` to remove `批次`, `导入时间`, `登记时间`, `历史出现次数`, move `需协同人` immediately after `问题类型`, and add assertions that:

```js
expect(wrapper.findAll('thead th').map(node => node.text())).toEqual(visibleColumnLabels)
expect(wrapper.get('[data-testid="plan-date-display-1"]').classes()).toContain('replay-plan-date-emphasis')
expect(wrapper.findAll('tbody td').at(visibleColumnLabels.indexOf('初步问题分析')).classes()).toContain('replay-detail-cell')
expect(wrapper.findAll('tbody td').at(visibleColumnLabels.indexOf('首次出现日期')).text()).toBe('2026-07-28')
```

Also assert the approved width pairs by reading each header's matching `<col>` style.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: FAIL because old columns/order/colors/date formatting remain.

- [ ] **Step 3: Implement the page adjustment**

In `ReplayIssuePage.vue`:

```js
function dateOnlyDisplay(value) {
  const text = value == null ? '' : String(value).trim()
  const match = text.match(/^(\d{4}-\d{2}-\d{2})/)
  return match ? match[1] : display(value)
}
```

Use it for `first_occurrence_date` and `last_occurrence_date`. Remove the four display-only columns, move `cooperation_person_username` after `issue_type`, set `domain`/`issue_id` to the same width, and set `is_sandbox`/`transaction_code`/`issue_level` to the same width. Replace the shared red manual-value rule with scoped red status/type/collaborator/plan-date classes and blue detail classes for analysis/solution/remark.

- [ ] **Step 4: Run the focused page test**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: PASS.

---

### Task 2: Excel export columns, order, and date values

**Files:**
- Modify: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`

**Interfaces:**
- Consumes: `ReplayIssueDao.listForExport(ReplayIssueQuery)` maps.
- Produces: `/api/ai/parallel-replay/issues/export` workbook with the approved header/value order.

- [ ] **Step 1: Write failing export assertions**

Extend the existing workbook test to assert:

```java
assertFalse(headers.contains("批次"));
assertFalse(headers.contains("导入时间"));
assertFalse(headers.contains("登记时间"));
assertFalse(headers.contains("历史出现次数"));
assertEquals(headers.indexOf("问题类型") + 1, headers.indexOf("需协同人"));
assertEquals("2026-07-28", data.getCell(headers.indexOf("首次出现日期")).getStringCellValue());
assertEquals("2026-07-31", data.getCell(headers.indexOf("上次出现日期")).getStringCellValue());
```

- [ ] **Step 2: Run the focused backend test and verify failure**

Run: `mvn -Dtest=ReplayIssueControllerTest#exportsPlannedCompletionDateInPageColumnOrder test`

Expected: FAIL on legacy columns, collaborator order, or date-time values.

- [ ] **Step 3: Implement the export contract**

Remove the four headers and matching values, move `personText(item)` immediately after `issue_type`, and add a helper:

```java
private static String dateOnlyText(Object value) {
    String text = text(value).trim();
    return text.matches("^\\d{4}-\\d{2}-\\d{2}.*$") ? text.substring(0, 10) : text;
}
```

Use the helper only for first/last occurrence date export values.

- [ ] **Step 4: Run focused export tests**

Run: `mvn -Dtest=ReplayIssueControllerTest test`

Expected: PASS.

---

### Task 3: Regression verification and production frontend packaging

**Files:**
- Generated: `/Users/java/axon-link-frontend/dist/**`
- Replace generated bundle: `/Users/java/axon-link-server/src/main/resources/static/**`

**Interfaces:**
- Consumes: completed frontend and backend changes.
- Produces: backend-served frontend containing the approved page layout.

- [ ] **Step 1: Run frontend regression tests**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js`

Expected: PASS.

- [ ] **Step 2: Run backend controller regression tests**

Run: `mvn -Dtest=ReplayIssueControllerTest test`

Expected: PASS.

- [ ] **Step 3: Build the production frontend**

Run: `npm run build`

Expected: Vite exits 0 and creates `/Users/java/axon-link-frontend/dist/index.html` plus hashed assets.

- [ ] **Step 4: Copy the built frontend into Spring Boot static resources**

Use a recoverable synchronization method that replaces only `/Users/java/axon-link-server/src/main/resources/static` contents with `/Users/java/axon-link-frontend/dist` contents; verify `index.html` references assets that exist in the copied directory.

- [ ] **Step 5: Package and smoke-check the backend**

Run: `mvn -DskipTests package`

Expected: BUILD SUCCESS and `/Users/java/axon-link-server/target/axon-link-server-1.0.0.jar` contains the new static assets.

- [ ] **Step 6: Inspect the local page**

Start or reuse the local backend/frontend service, open the replay issue page, and verify the approved layout with mock/current data: removed columns absent, collaborator follows issue type, colors are scoped, dates are date-only, and horizontal scrolling remains aligned.
