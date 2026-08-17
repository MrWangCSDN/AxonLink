# Replay Issue Round Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record every formal import round, each issue's appearance and import-time status transition, and associate subsequent manual edit history with the active round.

**Architecture:** Add an import-round aggregate and immutable per-issue round records. Formal import creates the round and all affected records in the existing replay transaction; manual saves reference the latest successful formal round without mutating import facts. Queries join the round tables for historical membership and return a round-grouped tracking projection to the existing drawer.

**Tech Stack:** Java 17, Spring MVC, JdbcTemplate, Flyway SQL, MySQL/H2 tests, Vue 3, Vitest.

## Global Constraints

- Formal eight-sheet import is the only workflow that creates an active round.
- Temporary full refresh stays in the base-data group and does not create a formal round.
- Import records are immutable; manual saves remain individual history rows.
- Existing ignored appearances that were never persisted cannot be reconstructed.
- Preserve all unrelated dirty-worktree changes and do not create commits without user authorization.

---

### Task 1: Round Schema And Migration

**Files:**
- Create: `src/main/resources/db/daoindex/V41__dii_replay_issue_round_tracking.sql`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`

**Interfaces:**
- Produces tables `dii_replay_import_round`, `dii_replay_issue_round` and nullable `dii_replay_issue_history.context_round_id`.
- Preserves existing `coverage_round` columns during compatibility rollout; application queries stop treating them as the source of truth.

- [ ] Add a migration with unique `round_code`, unique `(round_id, issue_key_hash)`, appeared/status/action/source fields and supporting indexes.
- [ ] Backfill known round codes and known current issue membership without inventing missing ignored appearances.
- [ ] Extend H2 fixtures with equivalent schema.
- [ ] Run replay DAO tests and confirm the migration-compatible schema works.

### Task 2: Round Persistence API

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayImportRound.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRoundEntry.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces `long insertImportRound(...)`, `void updateImportRoundStats(...)`, `void insertIssueRound(...)`, `Long findLatestImportRoundId()`, `List<Map<String,Object>> listImportRounds()`, and grouped round-tracking query methods.
- `ReplayIssueQuery.coverageRound` resolves through `dii_replay_issue_round` with `appeared=1`.

- [ ] Add failing DAO tests for historical round membership, latest active round and grouped tracking data.
- [ ] Implement parameterized DAO operations and round-based filter/options queries.
- [ ] Run `ReplayIssueDaoTest` and verify all cases pass.

### Task 3: Formal Import Round Recording

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`

**Interfaces:**
- Formal merge creates one round, records every incoming key including ignored rows, and records missing deferred/pending issues as `appeared=false` automatic repairs.
- System history created during import receives the same `context_round_id`.

- [ ] Add failing tests for new, ignored, reopened, repeated and automatic-repair round records.
- [ ] Create the round inside the merge transaction before issue processing.
- [ ] Write immutable before/after/action records for every affected issue and finalize round statistics.
- [ ] Run merge/import service tests.

### Task 4: Manual History Round Context

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Manual save history uses `findLatestImportRoundId()` at transaction time and passes it to history insert.
- Multiple edits remain separate rows; round summary derives count and final state from their order.

- [ ] Add tests for multiple manual edits under one round and base-data edits with no round.
- [ ] Extend history insertion and mapping with `context_round_id`.
- [ ] Run edit service and DAO history tests.

### Task 5: Round APIs

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRoundTrackingGroup.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- `GET /api/ai/parallel-replay/issues/rounds` returns round summaries.
- `GET /api/ai/parallel-replay/issues/{id}/round-tracking` returns ordered round groups with import outcome and manual events.

- [ ] Add controller tests for round list, grouped path and unknown issue behavior.
- [ ] Implement response mapping without changing the existing raw history endpoint.
- [ ] Run controller tests.

### Task 6: Frontend Round Membership And Tracking Hierarchy

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Filter keeps the `coverageRound` request parameter for compatibility but options come from round summaries.
- Last column displays known occurrence rounds.
- Tracking drawer groups import outcome and manual edits under round headings, with a base-data group for null context.

- [ ] Add failing component tests for occurrence-round display, round filter and grouped tracking.
- [ ] Add API functions and render the hierarchy with expandable manual history.
- [ ] Run replay frontend tests.

### Task 7: Compatibility, Build And Documentation Verification

**Files:**
- Modify the three Obsidian replay design pages only if implementation details differ from the approved design.
- Regenerate `src/main/resources/static` through the frontend production build.

**Interfaces:**
- Existing imports, filters, export and raw history remain compatible.

- [ ] Run all replay backend tests.
- [ ] Run relevant frontend regression tests and production build.
- [ ] Run backend package and `git diff --check` in backend, frontend and Obsidian workspaces.
- [ ] Verify migration ordering and report the unrecoverable historical ignored-appearance limitation.
