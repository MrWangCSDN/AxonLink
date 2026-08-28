# Replay No-action Review Permission Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow ordinary users to edit pending no-action issues, while restricting approved issues and approval actions to the issue's technology owners or configured YAML group reviewers.

**Architecture:** Keep authorization in `ReplayIssueReviewService` and evaluate it against the concrete issue (`transactionCode` plus `groupName`). Expose reviewable transaction codes and merged reviewer contacts for UI affordances, but retain backend checks on both edit and approve writes. No database migration is required.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, JUnit 5, Vue 3, Vitest.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Current user identity resolves from username to active `ccbs_ai_sys_user.emp_no`.
- Technology owners come from `dii_replay_transaction_person.bank_owner_emp_nos` for the issue transaction code.
- YAML group reviewers remain scoped by normalized `group_name`; sandbox and non-sandbox share the list.
- Pending review does not lock editing; approved review does.
- Backend authorization is authoritative; frontend permissions only control affordances and messages.
- Preserve unrelated changes in the dirty worktrees and do not commit.

---

### Task 1: Problem-level reviewer service

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayTransactionPersonDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueReviewPermissions.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueReviewService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueReviewServiceTest.java`

**Interfaces:**
- Produces: `boolean isReviewer(ReplayIssueRow issue, ReplayIssueOperator operator)`.
- Produces: `ReplayIssueReviewPermissions.reviewableTransactionCodes()`.
- Produces: deduplicated `reviewerNames(ReplayIssueRow issue)` used in forbidden messages.

- [x] **Step 1: Write failing tests** for a technology owner approving its transaction, cross-transaction rejection, transaction-code permissions, and merged/deduplicated real-name contacts.
- [x] **Step 2: Run** `mvn -Dtest=ReplayIssueReviewServiceTest test` and verify failures are caused by the missing problem-level behavior.
- [x] **Step 3: Implement minimal DAO/service/DTO changes**, splitting stored employee-number strings with the established person delimiter rules and matching trimmed exact employee numbers.
- [x] **Step 4: Re-run the service test** and verify it passes.

### Task 2: Edit authorization lifecycle

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueReviewService.isReviewer(ReplayIssueRow, ReplayIssueOperator)`.

- [x] **Step 1: Write failing tests** proving an ordinary user can edit `PENDING`, cannot edit `APPROVED`, and a transaction technology owner can edit `APPROVED` and directly approve a newly selected no-action status.
- [x] **Step 2: Run** `mvn -Dtest=ReplayIssueEditServiceTest test` and verify the pending case fails under the current broad lock.
- [x] **Step 3: Restrict only `APPROVED` rows** and replace group-only checks with problem-level checks in both authorization and review-state derivation.
- [x] **Step 4: Re-run the edit service test** and verify it passes.

### Task 3: List projection and frontend behavior

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `reviewableGroups`, `reviewableTransactionCodes`, `reviewersByGroup`.
- Produces: list fields `matched_bank_owner` and `matched_bank_owner_emp_nos` for contact display and diagnostics.

- [x] **Step 1: Write failing frontend tests** for ordinary pending edit, ordinary approved lock, technology-owner approved edit/approval, and deduplicated combined contact text.
- [x] **Step 2: Run** the targeted Vitest file and verify the assertions fail for the current group-only implementation.
- [x] **Step 3: Implement `canReviewIssue(row)`** as group permission OR transaction-code permission; make `canEditIssue` lock only approved no-action rows; merge technology-owner names with YAML contacts and deduplicate for titles/messages.
- [x] **Step 4: Re-run the targeted Vitest file** and verify it passes.

### Task 4: Controller contract and regression verification

**Files:**
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Verifies: review-permissions JSON includes `reviewableTransactionCodes` and list rows expose technology-owner employee numbers.

- [x] **Step 1: Add or update contract assertions** for the permissions payload and list projection.
- [x] **Step 2: Run focused backend tests** for review service, edit service, DAO, and controller.
- [x] **Step 3: Run the full frontend suite** and report any pre-existing failures separately from this change.
- [x] **Step 4: Review `git diff`** to ensure no schema migration or unrelated file changes were introduced by this task.
