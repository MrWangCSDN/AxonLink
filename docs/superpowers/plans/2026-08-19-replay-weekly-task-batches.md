# Replay Weekly Task Batches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow administrators to configure one or more occurrence batches as the current weekly task, highlight every matching replay issue, and optionally query/export only those issues.

**Architecture:** Store only the current configured batch-name set in a dedicated table. A focused DAO/service owns validation and atomic replacement, while the existing issue DAO derives `weekly_task` and filters with `EXISTS` against occurrence-batch membership. The Vue page reads the current configuration, provides a token-protected replacement dialog, and renders task rows with persistent visual emphasis.

**Tech Stack:** Java 17, Spring Boot, Spring JDBC, Flyway, JUnit 5, H2/MySQL-compatible SQL, Vue 3, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Configuration is batch-based, supports one or many batches, and matching issues use set union semantics.
- Configuration never expires automatically; replacement and clearing are manual operations.
- `PUT /weekly-task` replaces the complete set; `batchNames: []` clears it.
- Setting and clearing require `X-DII-Trigger-Token`; reads do not.
- The issue list is server-paginated; `weeklyTask=true` must compose with all existing query and header filters.
- The dirty working tree contains user-owned changes; do not reset, clean, commit, or overwrite unrelated files.

---

### Task 1: Persist and atomically replace the current configuration

**Files:**
- Create: `src/main/resources/db/daoindex/V48__dii_replay_weekly_task_batch.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueWeeklyTaskConfig.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueWeeklyTaskUpdateRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueWeeklyTaskDao.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueWeeklyTaskService.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueWeeklyTaskServiceTest.java`

**Interfaces:**
- Produces: `ReplayIssueWeeklyTaskConfig current()` and `ReplayIssueWeeklyTaskConfig replace(List<String> batchNames)`.
- Produces: DAO methods `currentBatchNames()`, `availableBatchNames()`, `replaceBatchNames(List<String>)`, `currentIssueCount()`.

- [ ] **Step 1: Write failing service tests** for trim/de-duplication, unknown-batch rejection, full replacement, empty-list clearing, and issue-union count.
- [ ] **Step 2: Run** `./mvnw -Dtest=ReplayIssueWeeklyTaskServiceTest test` and confirm failure because the feature types do not exist.
- [ ] **Step 3: Add migration and minimal DAO/service implementation.** The table is `dii_replay_weekly_task_batch(batch_name VARCHAR(128) PRIMARY KEY)`. Validate every normalized requested name against occurrence batches before a transactional delete/insert replacement.
- [ ] **Step 4: Re-run the focused test** and confirm all cases pass.

### Task 2: Add task projection, filtering, and export semantics

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `dii_replay_weekly_task_batch` from Task 1.
- Produces: `ReplayIssueQuery.weeklyTask()` and list row field `weekly_task`.

- [ ] **Step 1: Write failing DAO tests** proving configured rows project `weekly_task=true`, unconfigured rows project false, `weeklyTask=true` composes with existing filters, and multi-batch matches do not duplicate rows or inflate totals.
- [ ] **Step 2: Run** `./mvnw -Dtest=ReplayIssueDaoTest test` and confirm the new assertions fail for missing projection/filtering.
- [ ] **Step 3: Implement minimal SQL.** Project a boolean `EXISTS` expression and append the same `EXISTS` predicate only when `query.weeklyTask()` is true.
- [ ] **Step 4: Add failing controller/export tests** for `weeklyTask=true` forwarding and an exported first column named `本周任务` containing `是` or `-`.
- [ ] **Step 5: Add the request parameter to list, header-option context, and export query creation; update export columns.**
- [ ] **Step 6: Run** `./mvnw -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest test` and confirm green.

### Task 3: Expose current configuration and token-protected replacement

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `ReplayIssueWeeklyTaskService.current()` and `.replace(...)`.
- Produces: `GET /api/ai/parallel-replay/issues/weekly-task` and `PUT /api/ai/parallel-replay/issues/weekly-task`.

- [ ] **Step 1: Write failing MVC tests** for GET payload, valid replacement, empty clearing, wrong-token 401, and unknown-batch 400 while retaining the previous configuration.
- [ ] **Step 2: Run** `./mvnw -Dtest=ReplayIssueControllerTest test` and confirm 404/missing-behavior failures.
- [ ] **Step 3: Implement GET/PUT endpoints** using the existing batch-trigger token comparison and service validation.
- [ ] **Step 4: Re-run focused MVC tests** and confirm green.

### Task 4: Add frontend API and page interactions

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: GET/PUT weekly-task endpoints and list/export `weeklyTask=true`.
- Produces: `getReplayWeeklyTask()` and `replaceReplayWeeklyTask(batchNames, token)`.

- [ ] **Step 1: Write failing API tests** for endpoint paths, JSON body, and token header.
- [ ] **Step 2: Run** `npm test -- --run src/api/replayIssues.spec.js` in `/Users/java/axon-link-frontend` and confirm missing exports fail.
- [ ] **Step 3: Implement the two API helpers.**
- [ ] **Step 4: Write failing page tests** for opening configuration, searching/selecting multiple batches, issue-count preview, token replacement/clearing, `仅看本周任务` query composition, row badge/highlight, and export query composition.
- [ ] **Step 5: Run** `npm test -- --run src/components/replay/ReplayIssuePage.spec.js` and confirm the missing UI behavior fails.
- [ ] **Step 6: Implement the confirmed UI.** Add the toolbar button, compact checkbox filter, task marker column, amber row class/badge, configuration dialog, token input, and replace/clear actions. Refresh list/config after save.
- [ ] **Step 7: Re-run both frontend focused test files** and confirm green.

### Task 5: Provide local mock data and verify delivery

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `src/main/resources/static/**` through the existing Vite production build output.

**Interfaces:**
- Consumes: frontend API contracts from Task 4.
- Produces: 100 deterministic mock issues across multiple occurrence batches, including at least 30 highlighted task issues.

- [ ] **Step 1: Extend the mock server** with GET/PUT weekly-task behavior, token rejection, union counting, `weeklyTask` filtering, and `weekly_task` row projection.
- [ ] **Step 2: Run focused backend tests** with `./mvnw -Dtest=ReplayIssueWeeklyTaskServiceTest,ReplayIssueDaoTest,ReplayIssueControllerTest test`.
- [ ] **Step 3: Run focused frontend tests** with `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js`.
- [ ] **Step 4: Run production frontend build** with `npm run build` so assets are copied into `src/main/resources/static`.
- [ ] **Step 5: Run Java 17 packaging** with `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home ./mvnw clean package`.
- [ ] **Step 6: Start the local mock service and visually verify** configuration, clearing, persistent highlighting, combined filters, pagination, and 100-row data volume.

## Self-Review

- Spec coverage: persistence, replacement/clear semantics, token protection, union matching, row projection, combined filtering, export, visual highlight, mock data, and build delivery all map to Tasks 1–5.
- Placeholder scan: no deferred implementation placeholders remain.
- Type consistency: `batchNames`, `availableBatchNames`, `issueCount`, `weeklyTask`, and `weekly_task` are consistent across Java, JSON, Vue, and tests.
- Repository policy: no commits are included because the user did not request commits and the worktree already contains unrelated user changes.
