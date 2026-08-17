# Replay Owner Columns Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split replay issue developer and technology owners into independent display, filter, and export columns while retaining legacy issue owner data only as historical storage.

**Architecture:** Keep the existing transaction-code left join to `dii_replay_transaction_person` as the only live owner source. Replace the combined `transactionOwner` query contract with independent `developer` and `bankOwner` parameters; keep the ranking aggregation scoped only to the full developer string.

**Tech Stack:** Java 17, Spring MVC, JdbcTemplate, Apache POI, Vue 3, Vitest.

## Global Constraints

- Do not delete or clear `dii_replay_issue.transaction_owner`; it remains historical data.
- Do not display, filter, export, or aggregate using `dii_replay_issue.transaction_owner`.
- Resolve both live owner columns through `transaction_code = old_transaction_code`.
- Developer ranking continues to group only by the complete `developer` string; do not split multiple people.
- Preserve unrelated dirty-worktree changes and do not create a Git commit.

---

### Task 1: Independent Backend Owner Filters

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- List/export consume optional `developer` and `bankOwner` parameters.
- The removed `transactionOwner` parameter is not accepted or forwarded.

- [ ] Add failing DAO tests proving `developer` and `bankOwner` filter independently and together as an intersection.
- [ ] Add failing controller tests proving the two request parameters reach list filtering.
- [ ] Replace `ReplayIssueQuery.transactionOwner` with `developer` and `bankOwner`.
- [ ] Apply separate `tp.developer LIKE ?` and `tp.bank_owner LIKE ?` predicates.
- [ ] Run `ReplayIssueDaoTest,ReplayIssueControllerTest` and verify green.

### Task 2: Split Excel Export And Developer Ranking Label

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Export columns contain adjacent `开发负责人` and `科技负责人` cells.
- `/stats/person-ranking` response remains developer-only.

- [ ] Add a failing export test for two separate owner headers and values with no combined ampersand cell.
- [ ] Update export headers and row projection to output `matched_developer` and `matched_bank_owner` independently.
- [ ] Strengthen ranking response test so bank owner does not alter developer grouping.
- [ ] Run focused backend tests and verify green.

### Task 3: Split Frontend Columns, Filters, And Ranking Copy

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Visible columns are `开发负责人` and `科技负责人`.
- Request/export parameters are `developer` and `bankOwner`.
- Hover entry/title reads `各组开发负责人问题排名`.
- Ranking TSV header reads `排名\t分组\t开发负责人\t打开\t延后修复\t重新打开\t修复待验证\t总数`.

- [ ] Add failing component tests for split values, filter parameters, reset behavior, ranking title, and TSV header.
- [ ] Replace the combined display formatter and column with two direct matched-field columns.
- [ ] Replace the combined filter input/state with independent developer and bank owner controls.
- [ ] Rename the ranking entry, panel title, and copy header without changing its endpoint or grouping behavior.
- [ ] Run focused frontend tests and verify green.

### Task 4: Regression, Build, And Packaging

**Files:**
- Regenerate: `src/main/resources/static/**`

- [ ] Run replay backend regression tests.
- [ ] Run the complete frontend test suite.
- [ ] Build the frontend into backend static resources.
- [ ] Run `mvn clean package -q -DskipTests` and verify current static chunks are present in the JAR.
- [ ] Run `git diff --check` in backend, frontend, and Obsidian workspaces.
- [ ] Visually verify desktop and 390px layouts keep filters and owner columns coherent.
