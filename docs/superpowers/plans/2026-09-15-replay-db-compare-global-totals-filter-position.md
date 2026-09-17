# Replay Database Comparison Global Totals And Filter Position Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Always show full-database registration and comparison-field totals, and open header filters directly at their anchor without flashing at the top-left corner.

**Architecture:** Extend the existing list page response with unfiltered global counts while preserving filtered `total` for pagination. Mirror `ReplayIssuePage` filter opening order by positioning the panel before rendering it and loading options afterward.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, Vue 3, Vitest

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-列表全库统计-系统设计.md`

## Global Constraints

- `total` remains the filtered table count used by pagination.
- `globalTableCount` and `globalFieldCount` count all non-deleted registrations and fields and never change with filters or pages.
- The title summary reads `共 N 张表 · 共 M 个比对字段` and contains no `当前页` wording.
- The filter panel must be positioned before it becomes visible; option loading must not move it.

---

### Task 1: Backend Global Counts

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareGlobalCounts.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareListPage.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/controller/ReplayDatabaseComparisonControllerTest.java`

**Interfaces:**
- Produces: `ReplayDbCompareListPage(items, page, size, total, globalTableCount, globalFieldCount)`.
- Produces: `ReplayDatabaseComparisonDao.findGlobalCounts()` returning one unfiltered aggregate.

- [ ] Write DAO, service, and controller tests proving global counts ignore filters and deleted registrations.
- [ ] Run focused tests and verify they fail because the response fields and aggregate do not exist.
- [ ] Add the count record, one aggregate SQL query, and preserve counts through normal and metadata-status search paths.
- [ ] Run focused tests and verify they pass.

### Task 2: Frontend Full-Database Summary

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.spec.js`

**Interfaces:**
- Consumes: `globalTableCount` and `globalFieldCount` from list search.
- Mock mode computes both values from the complete immutable Mock registration collection.

- [ ] Write tests proving the summary contains both global totals, omits `当前页`, and stays unchanged after filtering or paging.
- [ ] Run the page test and verify RED.
- [ ] Store backend global counts separately from filtered pagination state and compute Mock totals from all Mock rows.
- [ ] Run the page test and verify GREEN.

### Task 3: Header Filter First-Paint Position

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.spec.js`

**Interfaces:**
- `openFilter(key, event)` positions from `event.currentTarget` before setting `activeFilterKey`.
- Real option loading occurs after the positioned panel is visible.

- [ ] Write a deferred-response test proving the visible panel is already anchored while header options are still loading.
- [ ] Run the page test and verify RED against the current `8px, 8px` first paint.
- [ ] Reorder `openFilter` to position first, render second, and load options last.
- [ ] Run the page test and verify GREEN.

### Task 4: Regression Verification

**Files:**
- Verify only.

- [ ] Run focused backend tests with JDK 17.
- [ ] Run `mvn test` with JDK 17.
- [ ] Run `npx vitest run --exclude '**/.worktrees/**'`.
- [ ] Run `npm run build`.
- [ ] Run `git diff --check` in both repositories and inspect status without mixing unrelated existing changes.
