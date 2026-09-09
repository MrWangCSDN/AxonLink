# Replay Coverage Screenshot Layout Compatibility Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import and regenerate the screenshot layout containing business-domain summary, group summary, and transaction detail sections.

**Architecture:** Extend coverage row types to retain summary dimension without changing the existing table schema. Parse both summary regions by their own header rows, preserve source order and totals, and render both regions before transaction details; legacy `DETAIL/TOTAL` rows remain compatible as business-domain rows.

**Tech Stack:** Java 17, Apache POI, Spring JdbcTemplate, JUnit 5.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`.

## Global Constraints

- Accept screenshot headers `业务领域`/`大组` and `全量清单交易数`.
- Persist both summary sections including both total rows.
- Keep existing transaction-detail parsing and issue-list merge behavior unchanged.
- Any malformed required section aborts the whole import before commit.
- Do not commit changes unless explicitly requested.

---

### Task 1: Reproduce Screenshot Layout

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParserTest.java`

- [x] Add a workbook fixture matching the screenshot's two summary sections and detail section.
- [x] Assert both dimensions, both totals, aliases, and detail rows are parsed.
- [x] Run the focused parser test and verify it fails with the current missing-header error.

### Task 2: Parse and Persist Both Summary Dimensions

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyRowType.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParser.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`

- [x] Add domain/group detail and total row types while retaining legacy values.
- [x] Parse each summary table independently and stop at the next section header.
- [x] Verify DAO round-trips all row types without a schema change.

### Task 3: Regenerate Screenshot Structure

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriter.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriterTest.java`

- [x] Write domain summary, group summary, then transaction detail using screenshot labels.
- [x] Keep source ordering, numeric formats, totals, borders, fills, and column widths.
- [x] Parse the generated workbook again to verify round-trip compatibility.

### Task 4: Documentation and Regression

**Files:**
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`

- [x] Record the two coverage-summary dimensions and legacy row-type compatibility.
- [x] Run parser, DAO, writer, import-service, and daily-report regression tests.
