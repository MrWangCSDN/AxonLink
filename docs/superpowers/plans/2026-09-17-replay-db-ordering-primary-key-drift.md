# Replay Database Ordering Primary-Key Drift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect primary-key drift only for registrations using `compareLimit`, display the warning in list/editor, expose an exact table-header filter, and block version generation until the snapshot is refreshed.

**Architecture:** Persist an ordered primary-key baseline beside `compare_limit`. Compare it with current BASE metadata during existing batch validation, while SQL preview and generation continue using current keys. Keep the warning orthogonal to missing-field status so both can coexist.

**Tech Stack:** Java 17, Spring JDBC, Flyway-style SQL migrations, Vue 3, Vitest, JUnit 5.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-系统设计.md`

## Global Constraints

- Only registrations with non-null `compareLimit` can report ordering-key drift.
- Existing ordinary primary-key changes remain silent.
- Generated SQL always uses current BASE primary keys.
- Version generation rejects initialized limited registrations whose ordered snapshot differs from current BASE keys.
- Legacy limited registrations initialize their baseline silently on synchronization.
- No commits are created unless the user explicitly requests them.

---

### Task 1: Persist the ordered sorting-key baseline

**Files:**
- Create: `src/main/resources/db/daoindex/V71__replay_db_compare_ordering_primary_key_snapshot.sql`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareRegistration.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonMigrationTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDaoTest.java`

- [ ] Add a nullable `order_by_primary_keys_json` column.
- [ ] Write failing migration and DAO round-trip tests for ordered JSON values.
- [ ] Run the focused tests and confirm the missing-column/field failure.
- [ ] Add DTO and JDBC read/write support with empty-list normalization.
- [ ] Run the focused tests and confirm they pass.

### Task 2: Capture, initialize, and compare the baseline

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareMetadataValidation.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareMetadataStatus.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`

- [ ] Write failing tests for A→A+B, A+B→A, and A+B→B+A with and without `compareLimit`.
- [ ] Write a failing legacy test proving null snapshots initialize without warning, version increment, or audit.
- [ ] Populate the snapshot from current `primaryKeyOrder` on create/update/reregister when limited.
- [ ] Compute `orderingPrimaryKeyChanged`, saved keys, and current keys during batch/detail validation.
- [ ] Extend synchronization to initialize only null legacy snapshots.
- [ ] Run service tests and confirm all drift scenarios pass.

### Task 3: Add the synthetic table-header filter

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareMetadataStatus.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/controller/ReplayDatabaseComparisonControllerTest.java`

- [ ] Write failing tests for the `排序主键已变更` option, count, keyword search, and combined status filtering.
- [ ] Add `ORDERING_PRIMARY_KEY_CHANGED` matching without replacing missing-field status.
- [ ] Return the synthetic option from table-name header filters.
- [ ] Run service/controller tests and confirm precise cross-page filtering.

### Task 4: Display the list label and editor warning

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonEditor.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/replayDatabaseComparisonMock.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.spec.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonEditor.spec.js`

- [ ] Write failing tests for the red list label, table-header option, combined labels, and editor old/current-key warning.
- [ ] Map `ORDERING_PRIMARY_KEY_CHANGED` to the table-name filter criteria.
- [ ] Add the red label without hiding missing-field labels.
- [ ] Add the scope-editor warning and clear it after a successful save refreshes the snapshot.
- [ ] Mirror changes into the existing frontend worktree and run focused Vitest suites.

### Task 5: Add ordering drift to version-generation gates

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionServiceTest.java`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.spec.js`

- [x] Write a failing backend test proving an initialized limited snapshot mismatch returns `ORDERING_PRIMARY_KEY_CHANGED` and writes no version.
- [x] Compare saved/current ordered keys after table/field/scope validation and append a gate error containing both orders.
- [x] Write a failing Mock UI test proving confirmation displays the ordering-drift row in the gate list.
- [x] Add the status label and Mock gate error while leaving ordinary non-limited key changes unblocked.
- [x] Run focused backend and frontend tests.

### Task 6: Verify and package the frontend

**Files:**
- Generated: `src/main/resources/static/**`

- [ ] Run database-comparison DAO, migration, service, and controller tests with Java 17.
- [ ] Run frontend scope, editor, and page test suites excluding `.worktrees/**`.
- [ ] Build with `VITE_USE_MOCK=0` directly into backend static resources.
- [ ] Run `git diff --check` in frontend, backend, and Obsidian workspaces.
