# Replay Daily Report Lower Section Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the lower summary section visually separate current-batch data through issue total from previous-batch unresolved statistics.

**Architecture:** Keep all calculations and column order unchanged. Move the lower title split from column 16 to column 14, start yellow classification styling at column 14, and render lower-section issue total with the same current-batch styles as match pass rate.

**Tech Stack:** Java 17, Apache POI, JUnit 5.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Do not change report values, formulas, column order, other sheets, or snapshot persistence.
- Current-batch lower section is columns 0 through 13.
- Previous-batch lower section is columns 14 through 20.
- Do not commit or reset the user's existing working tree changes.

---

### Task 1: Correct Lower Summary Boundary and Colors

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriterTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriter.java`

**Interfaces:**
- Consumes: existing `CalculatedReport` values and fixed summary column order.
- Produces: the same workbook values with corrected lower title merges and style boundary.

- [x] **Step 1: Write failing POI assertions**

Assert that the lower title merges are `0..13` and `14..20`, the previous-batch title starts at column 14, lower issue-total headers use `F4CCCC`, lower issue-total detail uses `FFFFFF`, and its total uses `E2F0D9`.

- [x] **Step 2: Run the focused writer test and verify failure**

Run: `mvn -Dtest=ReplayDailyReportWorkbookWriterTest test`

Expected: FAIL because the current title split starts at column 16 and column 13 uses yellow classification styles.

- [x] **Step 3: Implement the minimal POI layout change**

Move the lower title split to column 14, initialize yellow headers from column 14, render the lower issue-total header with `lowerHeader`, and pass the ordinary lower integer style for column 13 values.

- [x] **Step 4: Verify focused and full backend tests**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Dtest=ReplayDailyReportWorkbookWriterTest test
mvn test
mvn -DskipTests package
```

Expected: writer test, full backend suite, and Java 17 package all succeed.
