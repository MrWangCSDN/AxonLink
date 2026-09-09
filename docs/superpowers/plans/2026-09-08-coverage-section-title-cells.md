# Coverage Section Title Cells Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Match the reference `回放交易覆盖情况` layout by placing each section title only in column A without horizontal merges and compressing the two summary sections.

**Architecture:** Preserve all section rows, data ordering, totals, formats, widths, filters, and freeze panes. Replace the three merged title bands with a single styled A-column title cell on the same row, and use dedicated compact heights for summary headers, detail rows, and totals without changing transaction-detail heights.

**Tech Stack:** Java 17, Apache POI, JUnit 5.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Do not change coverage values, formulas, row ordering, headers, or total-row semantics.
- Do not change the `汇总信息` or `接口比对明细` sheets.
- Preserve existing row indices, title text, field-header styles, detail styles, filter range, freeze pane, and transaction-detail row heights.
- Do not commit, reset, or clean the user's working tree.

---

### Task 1: Remove Coverage Section Title Merges

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriterTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriter.java`

**Interfaces:**
- Consumes: the existing three section titles and section row indices.
- Produces: title rows with only column A styled and no merged regions.

- [x] **Step 1: Write failing POI assertions**

Assert the coverage sheet has zero merged regions, each section title remains in column A with dark teal fill, each following field-header row remains a full dark teal band at 26 points, ordinary summary rows are 20 points, and total rows are 22 points.

- [x] **Step 2: Run focused writer tests and verify failure**

Run: `mvn -Dtest=ReplayDailyReportWorkbookWriterTest test`

Expected: FAIL first because the current coverage sheet contains three horizontally merged title regions, then fail on the compact height assertions until the height change is implemented.

- [x] **Step 3: Implement the minimal title-cell writer**

Add a helper that creates the title row at the existing height and writes only column A with `styles.detailHeader()`. Use it for both summary section titles and the transaction-detail title. Add a height-aware flat-header overload, use 26 points for both summary headers, 20 points for ordinary summary rows, and 22 points for totals while retaining existing transaction-detail heights.

- [x] **Step 4: Verify focused tests, full backend tests, packaging, and diff hygiene**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Dtest=ReplayDailyReportWorkbookWriterTest test
mvn test
mvn -DskipTests package
git diff --check
```

Expected: focused tests, all backend tests, Java 17 packaging, and diff validation succeed.
