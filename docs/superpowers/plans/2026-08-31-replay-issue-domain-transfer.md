# Replay Issue Domain Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an independently maintained issue domain with permission-checked three-transfer auditing, preserve it across Excel imports, align page/export columns, and enforce the first-occurrence-plus-seven-days plan-date limit.

**Architecture:** Store the current issue domain on `dii_replay_issue` and each real change in a dedicated transfer table. A focused domain service owns validation, permissions, locking, history, and DTOs; list responses carry only the derived count while tooltip history loads lazily. Existing import merge paths initialize new keys from `group_name` and preserve old keys, while plan-date validation remains in its existing service.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, Flyway-style SQL migrations, JUnit 5, Vue 3, Vite, Vitest, Apache POI.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section “问题所属领域与转组控制（2026-08-31）”, with matching data-model and API documents.

## Global Constraints

- Allowed issue domains are exactly `存款组、贷款组、公共组、结算组、迁移组、平台组`.
- New issue keys initialize from `group_name`; existing issue keys never have `issue_domain` overwritten by Excel imports.
- Only a maintainer of the current `issue_domain` may transfer it; identities use `emp_no`, falling back to `username` only when `emp_no` is blank.
- A real domain change is limited to three per issue; same-value blur is a no-op.
- Any non-null `defect_repair_date` locks both domain transfer and planned-date editing.
- Non-empty planned dates must be real `yyyy-MM-dd` dates no later than `first_occurrence_date + 7 days`; historical values are not migrated.
- Do not reset, clean, overwrite, commit, or merge unrelated user changes in either repository.

---

### Task 1: Persist issue domain and transfer audit

**Files:**
- Create: `src/main/resources/db/daoindex/V52__dii_replay_issue_domain_transfer.sql`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces `ReplayIssueRow.issueDomain()`.
- Produces DAO methods `updateIssueDomain(long,String)`, `countIssueDomainTransfers(long)`, `insertIssueDomainTransfer(...)`, and `listIssueDomainTransfers(long)`.
- List/export row maps expose `issue_domain` and `issue_domain_transfer_count`.

- [ ] Write DAO tests proving stored domain retrieval, initial fallback, ordered transfer history, and exact transfer counts.
- [ ] Run `mvn -Dtest=ReplayIssueDaoTest test` and confirm the new assertions fail before implementation.
- [ ] Add the migration, fixture schema, record field, DAO projection, persistence methods, and history mapping.
- [ ] Run `mvn -Dtest=ReplayIssueDaoTest test` and confirm all targeted tests pass.

### Task 2: Preserve issue domain through all imports

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueFullRefreshServiceTest.java`

**Interfaces:**
- New inserts persist `incoming.issueDomain() == null ? incoming.groupName() : incoming.issueDomain()`.
- Every existing-key merge constructs the resulting row with `current.issueDomain()`.

- [ ] Add failing tests for a new key initialized from its group and an old key retaining a manually changed domain for both query and accounting imports.
- [ ] Run `mvn -Dtest=ReplayIssueMergeServiceTest,ReplayIssueFullRefreshServiceTest test` and confirm the preservation tests fail.
- [ ] Thread `issueDomain` through merge copies and persistence without changing existing lifecycle/manual-field behavior.
- [ ] Re-run the same tests and confirm they pass.

### Task 3: Add permission-checked domain transfer APIs

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDomainProperties.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDomainService.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDomainForbiddenException.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueDomainPermissions.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueDomainUpdateRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueDomainTransferEntry.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueDomainTransfers.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDomainServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- `GET /issue-domain-permissions -> ReplayIssueDomainPermissions(editableDomains)`.
- `PATCH /{id}/issue-domain` consumes `{issueDomain}` and returns the updated row.
- `GET /{id}/issue-domain-transfers -> ReplayIssueDomainTransfers(transferCount,items)`.

- [ ] Write service/controller tests for six values, current-domain permissions, emp-no/username identity, permission shift after transfer, repair-date lock, same-value idempotency, ordered history, and the three-transfer limit.
- [ ] Run `mvn -Dtest=ReplayIssueDomainServiceTest,ReplayIssueControllerTest test` and confirm the new tests fail.
- [ ] Implement properties, service transaction, DTOs, controller mappings, error statuses/messages, YAML defaults, and general tracking snapshot insertion.
- [ ] Re-run the targeted tests and confirm they pass.

### Task 4: Enforce the seven-natural-day plan-date boundary

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- `ReplayIssuePlanDateService.update` accepts a non-empty date only when `firstOccurrenceDate` parses and the value is at most `plusDays(7)`.
- Clearing a date keeps existing behavior and does not require a valid first-occurrence date.

- [ ] Add failing tests for exact `+7`, rejected `+8`, invalid/empty first occurrence, impossible input dates, and unchanged historical values.
- [ ] Run `mvn -Dtest=ReplayIssuePlanDateServiceTest,ReplayIssueControllerTest test` and confirm boundary failures.
- [ ] Add strict parsing and the two exact Chinese error messages from the API spec.
- [ ] Re-run the targeted tests and confirm they pass.

### Task 5: Align Excel export columns

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Export local order becomes `问题描述、优先任务、领域、问题所属领域、计划验证日期、缺陷修复日期`.
- No transfer-count or transfer-history column is exported.

- [ ] Add a failing workbook assertion for exact headers, order, migrated domain value, and absence of transfer-count headers.
- [ ] Run the focused export test and confirm it fails.
- [ ] Reorder header/value arrays together and write only `issue_domain`.
- [ ] Re-run the focused export test and confirm it passes.

### Task 6: Implement page editing, badge history, and date feedback

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- API functions: `getReplayIssueDomainPermissions()`, `updateReplayIssueDomain(id,issueDomain)`, `getReplayIssueDomainTransfers(id)`.
- The combined cell uses `issue_domain`, `issue_domain_transfer_count`, and lazy tooltip items.

- [ ] Add failing API and component tests for the new order, equal width, six-option selector, blur save, unchanged no-op, repair/permission/three-count disabling, icon visibility, newest-first tooltip, and +7 plan-date client validation.
- [ ] Run `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js` in `/Users/java/axon-link-frontend` and confirm new tests fail.
- [ ] Implement API calls, permissions load, combined domain cell, tooltip states, locking messages, and front-end date bound feedback.
- [ ] Re-run the focused front-end tests and confirm they pass.

### Task 7: Extend local Mock and perform end-to-end verification

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/obsidian/log.md`

**Interfaces:**
- Mock rows expose varied domains and transfer counts `0..3`.
- Mock endpoints implement permissions, real transfers, history, repair locks, max-count rejection, and date boundary validation.

- [ ] Add representative Mock rows covering no history, one/two/three transfers, all six domains, editable/locked states, and newest-first operator/time details.
- [ ] Implement the three Mock API paths and mutate row state on successful transfer.
- [ ] Run the full focused backend suite, full frontend suite, and `npm run build`.
- [ ] Start or reuse the local Vite Mock server, open `/#replay-issues`, and visually verify the combined cell, badge tooltip, selector states, column order, and date errors.
- [ ] Append an implementation result entry to Obsidian `log.md` with actual test/build evidence and any known unrelated baseline failures.
