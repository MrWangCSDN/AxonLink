# Replay Priority Task Labels and Summary Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorder the lifecycle summary cards and rename all user-visible “本周任务” wording to “优先任务” without changing statistics, API contracts, Java identifiers, or database storage.

**Architecture:** Keep the existing `summaryCards` data structure and weekly-task persistence/query implementation. Change only the front-end card ordering and visible copy, plus the server-side Excel header and user-facing failure message; preserve `/weekly-task`, `weeklyTask`, `weekly_task`, Java class names, and `dii_replay_weekly_task_batch`.

**Tech Stack:** Vue 3, Vitest, Java 17, Spring Boot, JUnit 5, Apache POI

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Preserve all unrelated changes in the dirty server and frontend worktrees.
- Use `apply_patch` for source and test edits.
- Do not rename compatibility-facing paths, parameters, response fields, Java types, CSS/test identifiers, or database tables.
- Do not change summary values, filters, aggregation formulas, or modal table column order.
- Do not commit or push unless the user explicitly requests it.

---

### Task 1: Lock the required summary-card order with a frontend test

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`

**Interfaces:**
- Consumes: existing `summaryCards` array entries with `key`, `label`, and `valueKey`.
- Produces: rendered lifecycle cards ordered as total, new, open, reopened, deferred, noAction, pendingVerification, fixed.

- [ ] **Step 1: Write the failing test**

Add an assertion that `.replay-summary-card > span` labels equal:

```js
[
  '问题总数（全部状态）',
  '问题新建总数',
  '问题打开总数',
  '问题重新打开总数',
  '问题延后修复总数',
  '问题无需处理总数',
  '问题待验证总数',
  '问题已修复总数',
]
```

- [ ] **Step 2: Run the targeted test and verify RED**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: the order assertion fails because `noAction` currently precedes `reopened` and `deferred`.

- [ ] **Step 3: Apply the minimal order change**

Move the existing `noAction` card after the existing `deferred` card. Do not change labels, `valueKey` values, or computed statistics.

- [ ] **Step 4: Run the targeted frontend test and verify GREEN**

Run the command from Step 2. Expected: all `ReplayIssuePage.spec.js` tests pass.

### Task 2: Rename all visible priority-task copy while preserving internal compatibility

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify only if it contains a visible failure message: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/service/ReplayIssueWeeklyTaskService.java`

**Interfaces:**
- Consumes: existing `/weekly-task` endpoint, `weeklyTask` query parameter, `weekly_task` row projection, and shared trigger token.
- Produces: visible “配置优先任务”, “仅看优先任务”, “优先任务” badge/modal copy, Excel header “优先任务”, and failure message “优先任务配置失败，原配置未改变”.

- [ ] **Step 1: Write failing frontend copy assertions**

Assert that the toolbar button, task-only checkbox label, task badge, and opened configuration modal contain the new wording and that rendered page text does not contain `本周任务`.

- [ ] **Step 2: Write failing backend export/error-copy assertions**

In controller coverage, assert the first Excel export header is `优先任务`. If a deterministic service failure test already exists, change its expected failure copy to `优先任务配置失败，原配置未改变`.

- [ ] **Step 3: Run targeted tests and verify RED**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/components/replay/ReplayIssuePage.spec.js

cd /Users/java/axon-link-server
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin \
mvn -Dtest=ReplayIssueControllerTest test
```

Expected: assertions fail only on old visible “本周任务” copy.

- [ ] **Step 4: Apply the minimal copy changes**

Replace user-visible template strings, modal copy, Excel header, and relevant user-facing failure message. Keep the following exact internal identifiers unchanged:

```text
/weekly-task
weeklyTask
weekly_task
ReplayIssueWeeklyTask*
dii_replay_weekly_task_batch
```

- [ ] **Step 5: Run targeted tests and verify GREEN**

Run the commands from Step 3. Expected: all targeted tests pass.

### Task 3: Regression, production assets, and delivery verification

**Files:**
- Generated: `/Users/java/axon-link-server/src/main/resources/static/**`
- Generated: `/Users/java/axon-link-server/target/axon-link-server-1.0.0.jar`
- Modify: `/Users/java/obsidian/log.md`

**Interfaces:**
- Consumes: completed source changes from Tasks 1 and 2.
- Produces: verified production frontend assets embedded in the backend and a rebuilt executable JAR.

- [ ] **Step 1: Run the full frontend suite**

```bash
cd /Users/java/axon-link-frontend
npm test -- --run
```

Expected: all frontend tests pass.

- [ ] **Step 2: Run relevant backend tests**

```bash
cd /Users/java/axon-link-server
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin \
mvn -Dtest=ReplayIssueControllerTest,ReplayIssueWeeklyTaskServiceTest test
```

Expected: relevant backend tests pass; do not mask unrelated failures.

- [ ] **Step 3: Build frontend into backend static resources**

```bash
cd /Users/java/axon-link-frontend
npm run build
```

Expected: Vite succeeds and writes hashed assets under `/Users/java/axon-link-server/src/main/resources/static`.

- [ ] **Step 4: Build the backend JAR**

```bash
cd /Users/java/axon-link-server
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin \
mvn -DskipTests package
```

Expected: `/Users/java/axon-link-server/target/axon-link-server-1.0.0.jar` is rebuilt successfully.

- [ ] **Step 5: Verify compatibility names and diff hygiene**

Search production code to confirm `/weekly-task`, `weeklyTask`, `weekly_task`, and `dii_replay_weekly_task_batch` still exist, while new built assets contain `优先任务`. Run `git diff --check` for edited source and test files.

- [ ] **Step 6: Append implementation evidence to the Obsidian log**

Record the completed order, visible-copy scope, compatibility boundary, test counts, frontend production build, and backend package result in `/Users/java/obsidian/log.md`.

## Execution Result

- Tasks 1–3 completed on 2026-08-27 using RED-GREEN TDD.
- Frontend full suite: 153 tests passed.
- Backend priority-task controller/service regression: 7 tests passed.
- Production frontend assets rebuilt into backend `static`; compatibility identifiers remained unchanged.
- Backend executable JAR rebuilt after the final controller error-copy change.
