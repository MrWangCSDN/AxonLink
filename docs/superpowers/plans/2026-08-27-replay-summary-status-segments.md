# Replay Summary Status Segments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand both replay summary projections to all seven formal statuses, expose pending/fixed segment totals, rank developers by total issues, and render both tables in a shared two-color column order.

**Architecture:** SQL remains the single source of all atomic and derived counts. DTOs expose `pendingTotalCount`, `fixedCount`, `fixedTotalCount`, and all-status `totalCount`; Vue renders the API fields in the approved order and assigns semantic pending/fixed column classes used by headers, body cells, and TSV copy.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, Vue 3, Vitest

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Only `新建`, `打开`, `重新打开`, `延后修复`, `修复待验证`, `无需处理`, and `已修复` participate.
- `pendingTotalCount` is the first five statuses.
- `fixedTotalCount` is `noActionCount + fixedCount`.
- `totalCount` is both segment totals and controls developer ranking.
- Visible/clipboard order is pending atomic statuses, pending total, fixed atomic statuses, fixed total.
- Both summary modals use identical status order and semantic colors.

---

### Task 1: Expand Backend Summary Contracts

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueGroupSummary.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePersonRanking.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`

- [x] Write failing DAO tests for all atomic counts, both derived totals, all-status total, unknown-status exclusion, and ranking by all-status total.
- [x] Write failing controller assertions for the new JSON fields.
- [x] Run the focused backend tests and verify the missing fields/fixed rows cause failure.
- [x] Expand records and SQL projections, filter to seven formal statuses, and order rankings by all-status `total_count`.
- [x] Run focused backend tests until green.

### Task 2: Render Two Semantic Column Segments

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`

- [x] Write failing frontend tests for both tables' exact column order, pending/fixed classes, absence of visible `totalCount`, and matching TSV order.
- [x] Update both column arrays and row fixtures with the new API fields.
- [x] Bind semantic classes to table headers/cells and add warm-orange pending plus green fixed styles with stronger total columns.
- [x] Update Mock payloads and run focused frontend tests until green.

### Task 3: Verify Integrated Delivery

**Files:**
- Generated: `/Users/java/axon-link-server/src/main/resources/static/**`

- [x] Run focused backend tests and the relevant controller suite.
- [x] Run frontend full tests and `npm run build`.
- [x] Run `git diff --check` in backend, frontend, and Obsidian worktrees.
- [x] Verify both modals, column order, segment colors, and group-specific copy in the local browser.
