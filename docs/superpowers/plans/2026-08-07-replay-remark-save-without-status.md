# Replay Remark Save Without Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to edit remarks and other manual fields without changing a system-managed issue status, while displaying the backend's actual validation message for HTTP errors.

**Architecture:** The frontend sends `issueStatus: null` when no new manual status was selected. The backend resolves a null requested status to the locked current row's status, but continues to reject explicit transitions to system-managed statuses; the shared frontend request helper parses JSON error envelopes before throwing.

**Tech Stack:** Java 17, Spring Boot, Spring JDBC, JUnit 5, Vue 3, Vitest, Vue Test Utils.

## Global Constraints

- Preserve the rule that only `延后修复` and `修复待验证` can be selected as new manual statuses.
- Preserve the existing status when the request omits `issueStatus`.
- Continue rejecting edits to rows whose current status is `已修复`.
- Keep current-row update and history insertion in the existing transaction.
- Do not change import or full-update behavior.
- Preserve unrelated dirty worktree changes and do not commit.

---

### Task 1: Preserve Current Status for Remark-Only Saves

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueUpdateRequest.issueStatus()` which may be null.
- Produces: `ReplayIssueEditService.update(...)` that retains the locked row status for null requests.

- [x] **Step 1: Write a failing service test.**

Seed an `打开` row, submit a request with `issueStatus == null` and a changed remark, then assert the result remains `打开`, the remark is updated, and one `人工保存` history event exists.

- [x] **Step 2: Run the service test and verify RED.**

```bash
mvn -q -Dtest=ReplayIssueEditServiceTest test
```

Expected: the new test fails with `该问题状态不能手工选择`.

- [x] **Step 3: Implement status resolution after row locking.**

Keep request/text/operator validation before the transaction. After loading and fixed-row validation, resolve null to `before.issueStatus()`; reject only a non-null requested status that is not manually selectable. Pass the resolved status into the updated row.

- [x] **Step 4: Run the service test and verify GREEN.**

```bash
mvn -q -Dtest=ReplayIssueEditServiceTest test
```

Expected: all edit service tests pass, including fixed-row validation and transaction rollback.

### Task 2: Submit an Unchanged Status and Preserve HTTP Error Messages

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/api/index.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Test: `/Users/java/axon-link-frontend/src/api/index.spec.js`

**Interfaces:**
- Consumes: the existing `updateReplayIssue(id, payload)` helper and backend `R` JSON envelope.
- Produces: remark-only saves with `issueStatus: null`, and `ApiError.message` populated from non-401 JSON error responses.

- [x] **Step 1: Write failing frontend tests.**

Add a component test whose row status is `打开`, change only the remark, save, and assert the modal closes and the saved result is visible after refresh. Add a request-helper test for an HTTP 400 JSON response `{ code: 400, message: "该问题状态不能手工选择" }` and assert that exact message is thrown.

- [x] **Step 2: Run the focused frontend tests and verify RED.**

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/api/index.spec.js
```

Expected: the component sends an empty status and the request helper throws only `HTTP 400: <path>`.

- [x] **Step 3: Implement the minimal frontend changes.**

Normalize `editDraft.issueStatus` to `null` in the update payload. For non-401 HTTP errors, attempt to parse the JSON envelope and use `json.message` before falling back to `HTTP <status>: <url>`.

- [x] **Step 4: Run focused frontend tests and verify GREEN.**

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/api/index.spec.js
```

Expected: all focused tests pass.

### Task 3: Verify and Package

**Files:**
- Regenerate: `src/main/resources/static/`
- Regenerate: `target/axon-link-server-1.0.0.jar`

- [x] **Step 1: Run the complete frontend suite.**

```bash
npm test -- --run
```

- [x] **Step 2: Build the frontend into backend static resources.**

```bash
npm run build
```

- [x] **Step 3: Run focused backend replay tests and package the JAR.**

```bash
mvn -q -Dtest=ReplayIssueEditServiceTest,ReplayIssueControllerTest test
mvn -q -DskipTests package
```

- [x] **Step 4: Run `git diff --check` in both repositories and report modified files.**
