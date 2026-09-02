# No-Action Issue Type Allowlist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let “无需处理” issues use exactly “合理差异、规则性差异问题、外围问题”, defaulting to “合理差异”, while keeping “延后修复” locked to “迁移问题”.

**Architecture:** The backend remains the authority for valid status/type combinations and rejects invalid no-action types instead of silently rewriting them. The Vue edit modal derives its visible issue-type options from the selected status: three choices for no-action, one disabled value for deferred, and the full ordered list for other statuses. Mock options and update behavior mirror the real API.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, Vue 3, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- No-action accepts only `合理差异`, `规则性差异问题`, and `外围问题`.
- Switching into no-action defaults to `合理差异`; an already selected allowed no-action type remains unchanged while editing.
- Deferred remains forced to `迁移问题` and its type control remains disabled.
- Invalid status/type combinations return HTTP 400 and do not update the current row or append history.
- Historical data is not migrated.
- Preserve all existing uncommitted changes in both repositories and do not commit or push unless explicitly requested.

---

### Task 1: Backend status/type combination contract

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueUpdateRequest.issueStatus()` and `issueType()`.
- Produces: saved no-action rows retaining one of the three allowed types; invalid combinations throw `IllegalArgumentException`.

- [ ] **Step 1: Write failing service tests**

Add independent tests proving:

1. An ordinary user can save no-action with each of the three allowed types and receives `PENDING`.
2. A reviewer can save each allowed type and receives `APPROVED`.
3. “代码问题” with no-action is rejected without updating the row or adding history.
4. Deferred still normalizes any submitted type to “迁移问题”.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

`mvn -q -Dtest=ReplayIssueEditServiceTest test`

Expected: allowed “规则性差异问题/外围问题” cases fail because production currently rewrites every no-action type to “合理差异”; invalid “代码问题” is incorrectly accepted.

- [ ] **Step 3: Implement minimal backend validation**

Add a named immutable allowlist for no-action types. Validate the normalized issue type after the effective status has been resolved and before collaborator lookup or persistence. Preserve the submitted allowed type in `edited(...)`; keep the existing deferred normalization.

Use the user-facing error:

`无需处理的问题类型只能选择：合理差异、规则性差异问题、外围问题`

Also add “规则性差异问题” to the global fixed issue-type set and option list.

- [ ] **Step 4: Run focused backend tests and verify GREEN**

Run:

`mvn -q -Dtest=ReplayIssueEditServiceTest,ReplayIssueDaoTest test`

Expected: PASS.

### Task 2: Frontend dependent issue-type selector

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: edit draft status and the global ordered issue-type list.
- Produces: `editableIssueTypes` for the select and `issueTypeLocked` only for deferred status.

- [ ] **Step 1: Write failing component tests**

Assert that switching to no-action defaults to “合理差异”, keeps the type selector enabled, and exposes exactly the three allowed options in the specified order. Select “规则性差异问题” and “外围问题” in separate saves and assert the API payload preserves the chosen value. Assert deferred still shows “迁移问题” and disables the selector.

- [ ] **Step 2: Run the focused frontend test and verify RED**

Run:

`npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: FAIL because the current no-action selector is disabled and only “合理差异” can survive the status-change handler.

- [ ] **Step 3: Implement minimal reactive options**

Add the new global issue type, derive no-action options as the three-item allowlist, render `editableIssueTypes`, and change the lock condition to deferred only. The status-change handler defaults no-action to “合理差异” and continues forcing deferred to “迁移问题”.

- [ ] **Step 4: Run the focused frontend test and verify GREEN**

Run:

`npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: PASS.

### Task 3: Mock parity and regression verification

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces: Mock option response containing “规则性差异问题” and Mock update rejection/preservation behavior matching the backend.

- [ ] **Step 1: Extend Mock-facing fixtures**

Update the complete options fixture and Mock edit handler so allowed no-action choices are preserved and invalid choices return the same validation message.

- [ ] **Step 2: Run frontend full suite**

Run:

`npm test -- --run`

Expected: all tests pass.

- [ ] **Step 3: Run backend focused suite and builds**

Run:

`mvn -q -Dtest=ReplayIssueEditServiceTest,ReplayIssueDaoTest test`

Then:

`mvn -q -DskipTests package`

Expected: focused tests and package succeed.

Run:

`npm run build`

Expected: production assets build into the backend static-resource directory.

- [ ] **Step 4: Run repository integrity checks**

Run:

`git diff --check`

Run:

`git -C /Users/java/axon-link-frontend diff --check`

Expected: both commands exit successfully.
