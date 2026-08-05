# Parallel Replay Issue Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the parallel replay issue list from a replace-all Excel snapshot into an `issue_key`-based issue lifecycle with atomic five-field editing and a complete before/after tracking timeline.

**Architecture:** Keep `dii_replay_issue` as the current issue projection and add `dii_replay_issue_history` as an append-only audit stream of full JSON snapshots. Excel import, system state transitions, and page edits run in one result-database transaction with history insertion; the Vue table adds constrained editors and a mode-A right-side tracking drawer.

**Tech Stack:** Java 17, Spring Boot, Spring JDBC/JdbcTemplate, MySQL-compatible SQL migrations, Apache POI, Vue 3, Vitest, Vue Test Utils, lucide-vue-next.

## Global Constraints

- Process only the eight configured sheets: 公共组、存款组、贷款组、结算组、沙箱-公共组、沙箱-存款组、沙箱-贷款组、沙箱-结算组.
- Match rows globally by non-blank `issue_key`; reject blank keys and duplicate keys within one workbook before changing the database.
- System statuses `打开`, `重新打开`, `已修复` are displayed but cannot be selected manually; user-selectable statuses are `分析中`, `延后修复`, `修复待验证`.
- One save submits `问题状态`, `问题类型`, `初步问题分析`, `最终处理方案`, and `需协同人` together in one transaction and one history event.
- `问题类型` options are 迁移问题、防腐问题、代码问题、新核心下线、其他问题; new rows initialize it empty.
- `初步问题分析` and `最终处理方案` are each limited to 500 characters.
- `需协同人` searches `ccbs_ai_sys_user` by `username` and `real_name` and displays `real_name(username)`.
- `修复待验证` missing from a new import becomes `已修复` and receives the local import date as `缺陷修复日期`.
- Reappearing `修复待验证` becomes `重新打开`; reappearing `已修复` becomes `打开` and updates `导入时间`; all four manual fields are retained while Excel-origin fields are refreshed.
- Every insert, state change, ignored duplicate decision, and manual save writes full before/after snapshots; system events use operator `SYSTEM` / `系统`.
- Do not expose `解决日期` as a current page/API field; keep legacy Excel parsing compatibility only where required by the source workbook.
- Preserve unrelated dirty worktree changes and do not reset or checkout user files.
- `ReplayIssueOperator` is the immutable record `(String username, String realName)` used by import and edit history events; system imports use `("SYSTEM", "系统")`.

---

### Task 1: Add Current-Projection and History Schema Models

**Files:**
- Create: `src/main/resources/db/daoindex/V34__dii_replay_issue_tracking.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueStatus.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueHistoryEntry.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueUpdateRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueUserOption.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRow.java`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`
- Test: `src/test/java/com/axonlink/ai/replay/dto/ReplayIssueStatusTest.java`

**Interfaces:**
- `ReplayIssueStatus` exposes `OPEN`, `ANALYZING`, `DEFERRED`, `PENDING_VERIFICATION`, `REOPENED`, `FIXED` with exact Chinese values and a method identifying manually selectable values.
- `ReplayIssueUpdateRequest` carries `issueStatus`, `issueType`, `initialAnalysis`, `finalSolution`, and `cooperationPersonUsername`.
- `ReplayIssueHistoryEntry` carries event id, issue identity, operation metadata, current status, manual-field snapshot, source metadata, and before/after snapshot strings.

- [x] **Step 1: Write failing enum and request validation tests.** Assert the six exact display values, exactly three manual statuses, and rejection of analysis/solution strings of length 501.
- [x] **Step 2: Run the focused test to verify RED, implement the migration/models, run focused tests, and commit `363025b`.** Focused status/parser tests: 11/11 passed; replay suite: 33/33 passed.

### Task 2: Implement `issue_key` Import Merge and Automatic State Transitions

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueExcelParser.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueOperator.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueImportResult.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueExcelParserTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`

**Interfaces:**
- `ReplayIssueMergeService.merge(ParsedWorkbook workbook, LocalDate importDate, ReplayIssueOperator operator)` returns counts for input, created, updated, ignored, auto-repaired, and rejected rows.
- DAO exposes a transaction-scoped current-row lookup by `issue_key`, current-row upsert/update, missing-pending-verification query, and history insert.

- [ ] **Step 1: Add failing tests for the complete transition matrix.** Cover new→打开, 打开/分析中/延后修复 duplicate ignore, 修复待验证→重新打开 with manual fields retained, 已修复→打开 with `import_date` updated and defect date cleared, missing 修复待验证→已修复, blank-key rejection, workbook duplicate-key rejection, and rollback when history insert fails.
- [ ] **Step 2: Run the merge tests and verify they fail.** Run `mvn -q -Dtest=ReplayIssueMergeServiceTest,ReplayIssueImportServiceTest test`; expected failures against the current replace-all implementation.
- [ ] **Step 3: Implement parsing and merge behavior.** Keep required source-header compatibility, ignore removed page fields, reject blank keys and in-workbook duplicates before the transaction, load current rows by key, refresh only Excel-origin fields on reappearance, retain the four manual fields, and add one history snapshot for every decision.
- [ ] **Step 4: Implement missing-key auto-repair.** In the same transaction, find current `修复待验证` rows absent from the incoming key set and set `issue_status=已修复` plus `defect_repair_date=importDate`; append the system history event.
- [ ] **Step 5: Run all replay backend tests.** Run `mvn -q -Dtest='com.axonlink.ai.replay.**' test`; expected PASS with old replace-all assertions updated to the new merge counts.
- [ ] **Step 6: Commit the merge engine.** Run `git add src/main/java/com/axonlink/ai/replay src/test/java/com/axonlink/ai/replay && git commit -m "feat: merge replay issues by issue key"`.

### Task 3: Add Atomic Manual Edit and User Lookup APIs

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Create: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueUserController.java`
- Modify: `src/main/java/com/axonlink/ai/user/persistence/SysUserDao.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- `PATCH /api/ai/parallel-replay/issues/{id}` accepts `ReplayIssueUpdateRequest` and returns the updated current row.
- `GET /api/ai/parallel-replay/issues/users?keyword=&limit=` returns `ReplayIssueUserOption` values formatted as `real_name(username)`.
- Edit service requires the authenticated operator, validates only the three manual statuses, fixed issue types, and 500-character limits, then updates current row and history in one transaction.

- [ ] **Step 1: Write failing service/controller tests.** Assert all five fields are updated atomically, one history row is created, a history failure rolls back the current row, system statuses are rejected in PATCH, 500-character limits are enforced, and user search matches either username or real_name.
- [ ] **Step 2: Run focused tests and verify RED.** Run `mvn -q -Dtest=ReplayIssueEditServiceTest,ReplayIssueControllerTest test`; expected failure because PATCH and user lookup endpoints do not exist.
- [ ] **Step 3: Implement edit transaction.** Lock the current row, validate the complete request, resolve and snapshot the selected user, update all five fields, insert one before/after history event, and commit or roll back as one unit.
- [ ] **Step 4: Implement user lookup.** Add a bounded fuzzy-search method to `SysUserDao` and expose only username, real name, and display name; preserve existing user APIs.
- [ ] **Step 5: Run replay controller and service tests.** Run `mvn -q -Dtest='com.axonlink.ai.replay.**' test`; expected PASS.
- [ ] **Step 6: Commit edit and user APIs.** Run `git add src/main/java/com/axonlink/ai/replay src/main/java/com/axonlink/ai/user/persistence/SysUserDao.java src/test/java/com/axonlink/ai/replay && git commit -m "feat: add atomic replay issue editing and user lookup"`.

### Task 4: Add Status Filtering and Tracking History Query

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueFilterOptions.java`
- Create: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueHistoryDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- `GET /api/ai/parallel-replay/issues` accepts optional `issueStatus` and returns current status/date/collaborator fields.
- `GET /api/ai/parallel-replay/issues/{id}/tracking` returns newest-first history entries, with full snapshots available in each entry detail.
- `GET /options` returns fixed issue types plus current group/level/status filter values.

- [ ] **Step 1: Write failing DAO/controller tests.** Cover exact status filtering, stable newest-first tracking order, full before/after snapshot retrieval, pagination preservation, and fixed issue-type options.
- [ ] **Step 2: Run focused tests and verify RED.** Run `mvn -q -Dtest=ReplayIssueDaoTest,ReplayIssueHistoryDaoTest,ReplayIssueControllerTest test`; expected failure for the missing status parameter and history endpoint.
- [ ] **Step 3: Implement parameterized status filtering and history query.** Keep existing keyword filters parameterized, add status filtering, normalize response keys, and limit history page size to 200.
- [ ] **Step 4: Run replay persistence/controller tests.** Run `mvn -q -Dtest='com.axonlink.ai.replay.**' test`; expected PASS.
- [ ] **Step 5: Commit query and tracking APIs.** Run `git add src/main/java/com/axonlink/ai/replay src/test/java/com/axonlink/ai/replay && git commit -m "feat: expose replay issue status and tracking history"`.

### Task 5: Implement the Mode-A Vue Editing and Tracking Drawer

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- API helpers: `updateReplayIssue(id, payload)`, `searchReplayIssueUsers(keyword)`, and `getReplayIssueTracking(id)`.
- The page submits `issueStatus`, `issueType`, `initialAnalysis`, `finalSolution`, and `cooperationPersonUsername` together on one save action.

- [ ] **Step 1: Write failing Vue tests.** Assert final 25-column order, removed columns, status placement, three-option status editor, five-option issue type editor with empty default, 500-character validation, collaborator fuzzy-search display, one update request containing all five fields, and the right-side tracking drawer rendering operation time/operator/current state.
- [ ] **Step 2: Run frontend tests and verify RED.** Run `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`; expected failures against the current read-only 26-column table.
- [ ] **Step 3: Implement API helpers and page columns.** Add the new helpers, update the table order/labels, render import date and defect repair date, remove sequence/cooperation-group/resolved-date columns, and add the final-solution tooltip text.
- [ ] **Step 4: Implement one-save row editor.** Use select/textarea/autocomplete controls, enforce the allowed statuses and 500-character limits, keep manual values across imports, and submit one PATCH request per save.
- [ ] **Step 5: Implement mode-A drawer.** Open tracking from the selected row without changing page/scroll state; show compact timeline fields and expandable full before/after snapshots; close with the existing icon-button conventions.
- [ ] **Step 6: Run frontend tests and build.** Run `npm test -- --run` and `npm run build`; expected all tests PASS and Vite production build exit 0.
- [ ] **Step 7: Commit the frontend change.** Run `git add /Users/java/axon-link-frontend/src/api/replayIssues.js /Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue /Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js && git commit -m "feat: edit replay issues and show tracking drawer"`.

### Task 6: Integrate, Package, and Verify

**Files:**
- Modify: `/Users/java/axon-link-server/src/main/resources/static/` (generated frontend production output only)
- Modify: `docs/superpowers/plans/2026-08-05-replay-issue-tracking.md` (task ledger/checklist only)
- Test: backend replay suite and frontend full suite

**Interfaces:**
- The packaged backend serves the same Vue application from `src/main/resources/static`.
- No unrelated dirty files may be reverted or staged.

- [x] **Step 1: Build the frontend into backend static resources.** Built the production bundle in an isolated output directory, copied it into the backend static resource directory, and verified the generated index references existing assets.
- [x] **Step 2: Run fresh backend verification.** Replay backend suite passed with 48 tests, 0 failures, and 0 errors.
- [x] **Step 3: Run fresh frontend verification.** Frontend suite passed with 26 tests; isolated production build passed because the repository Vite config points at a fixed sibling output path.
- [x] **Step 4: Run the backend package build.** Maven package passed and the bootable jar contains `BOOT-INF/classes/static/index.html` plus 104 static resources.
- [x] **Step 5: Inspect the final diff and update the plan ledger.** Checked status, whitespace, and final diff scope; unrelated worktree changes were left untouched.
- [x] **Step 6: Commit only integration output and plan status.** Integration output and this plan were committed as `build: package replay issue tracking frontend`.
