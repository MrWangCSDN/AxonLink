# Interface Comparison Single Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the generated `接口比对明细` sheet match the reference template with exactly one field-header row and data beginning on row 2.

**Architecture:** Keep the existing 18-column schema, row ordering, stored totals, alternating detail styles, formats, widths, and print setup. Remove only the merged sheet title and grouped parent headers, then move header, data, freeze pane, and auto-filter row indices upward.

**Tech Stack:** Java 17, Apache POI, JUnit 5.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Do not change interface comparison values, column order, total-row semantics, or database reads.
- Do not change the `汇总信息` or `回放交易覆盖情况` sheets.
- Preserve the dark teal field header and alternating detail-row styles.
- Do not commit, reset, or clean the user's working tree.

---

### Task 1: Replace Three-Level Interface Header with One Field Header

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriterTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriter.java`

**Interfaces:**
- Consumes: `INTERFACE_HEADERS` and `List<ReplayInterfaceComparisonRow>` ordered by `sourceRow`.
- Produces: `接口比对明细` with row 0 as the only header, row 1 onward as stored data, and filter/freeze anchored after row 0.

- [x] **Step 1: Write failing POI assertions**

Assert row 0 equals all 18 field names, no merged regions exist on the sheet, the first two detail rows are rows 1 and 2, the total row is row 3, and the auto-filter range starts at row 0.

- [x] **Step 2: Run the focused test and verify failure**

Run: `mvn -Dtest=ReplayDailyReportWorkbookWriterTest test`

Expected: FAIL because row 0 currently contains `接口比对明细`, the sheet has five merged regions, and data begins on row 3.

- [x] **Step 3: Implement the minimal POI layout change**

Call `writeFlatHeader(sheet, 0, INTERFACE_HEADERS, styles.detailHeader())`, write ordered data at `index + 1`, and call `configureInterfaceSheet(sheet, 0)`. Remove only the title and grouped-header creation calls.

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
