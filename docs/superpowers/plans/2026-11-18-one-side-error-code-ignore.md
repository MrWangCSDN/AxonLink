# One-Side Error Code Ignore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an independently persisted, reviewed sixth replay-ignore tab for one-sided failure error codes, with comma-split creation, `528`/`ccbs` ownership, and reviewed-only TXT export.

**Architecture:** Mirror the existing message-sequence ignore resource shape without reusing its table or DTOs. Add one business table and one columnar operation table, a no-version controller/service/DAO resource, a dedicated TXT exporter grouped by category and ownership, and a schema-driven Vue tab/API wrapper. The service-code remains only for reviewer mapping; uniqueness is category + ownership + error code.

**Tech Stack:** Java 17, Spring Boot JDBC, MySQL daoindex SQL, Vue 3, Vitest, Vite.

**Spec:** `C:\Users\hengj\Documents\ChatGPT\myjob-ai\obsidian-vault\01 Engineering\axon-link-server\并行回放忽略清单-系统设计.md`, `并行回放忽略清单-数据模型.md`, and `并行回放忽略清单-API接口.md` (Obsidian commit `3d376ca`).

## Global Constraints

- Do not execute backend code, Maven commands, or backend tests; perform backend verification with read-only searches and diff checks only.
- Frontend targeted/full Vitest and `npm run build` may run.
- Resource key is `one-side-error-code-ignores`; database tables are `dii_replay_one_side_error_code_ignore` and `dii_replay_one_side_error_code_ignore_operation`.
- Allowed `category` values are `QUERY` and `ACCOUNTING`; allowed `ownership` values are `528` and `ccbs`.
- New input `errorCodes` is comma-separated, trimmed, at most 100 codes, and all-or-nothing on blank, duplicate, or database conflict. Edit accepts one code only.
- Uniqueness is `(category, ownership, error_code)`; service code does not participate.
- Only reason-only updates preserve `review_status`; service code, category, ownership, or error code changes reset it to `0`.
- Export only `review_status=1`, UTF-8 BOM + CRLF TXT, fixed groups: 查询/528, 查询/ccbs, 账务/528, 账务/ccbs.
- Do not alter existing error-code-ignore behavior or existing message-sequence tables.

---

### Task 1: Migration And Backend Test Fixture

**Files:**
- Create: `src/main/resources/db/daoindex/V94__dii_replay_one_side_error_code_ignore.sql`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayConfigTestFixtures.java`

- [ ] **Step 1: Add failing fixture schema first**

Add business columns `id`, `service_code`, `category`, `ownership`, `error_code`, `ignore_reason`, `review_status`, `created_at`, `updated_at`; add matching operation columns for old/new values plus operation metadata. Use `utf8mb4_bin` for service/ownership/error text, nullable reason, and no `version`.

- [ ] **Step 2: Add V94 migration**

Create the two MySQL tables with indexes for category/ownership/error lookup and a unique key:

```sql
UNIQUE KEY uk_replay_one_side_error_category_owner_code(category, ownership, error_code)
```

The operation table must retain rows after business deletion and include `operation_type`, `operation_source`, `operator_username`, `operator_real_name`, `created_at`, old/new service code, category, ownership, error code, ignore reason, and review status.

- [ ] **Step 3: Static migration check and commit**

Run `rg -n "one_side_error|error_code|ownership"` against the migration and fixture, then `git diff --check`. Do not execute SQL or Java. Commit as `feat: add one-side error code schema`.

### Task 2: Backend DTO, DAO, Service, Controller

**Files:**
- Create DTOs under `src/main/java/com/axonlink/ai/replay/dto/` for row, create, update, draft, operation/history response.
- Create `ReplayOneSideErrorCodeIgnoreDao.java`, `ReplayOneSideErrorCodeIgnoreService.java`, and controller/resource wiring following `ReplayMessageSequenceIgnore*`.
- Modify the replay configuration controller/router and review snapshot/resource maps where resource types are enumerated.
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayOneSideErrorCodeIgnoreServiceTest.java` and DAO fixture tests as applicable.

- [ ] **Step 1: Write backend tests first without running them**

Cover: comma input creates one row per code; whitespace trimming; 100-code limit; blank/duplicate rejection; category + ownership uniqueness; reason-only update preserves review; service/category/ownership/code update resets review; review authorization uses service code; batch review/delete and operation history.

- [ ] **Step 2: Implement validation and DTO shapes**

Use request shapes:

```java
record ReplayOneSideErrorCodeIgnoreCreateRequest(
    String serviceCode, String category, String ownership,
    String errorCodes, String ignoreReason) {}
record ReplayOneSideErrorCodeIgnoreUpdateRequest(
    String serviceCode, String category, String ownership,
    String errorCode, String ignoreReason) {}
```

Rows expose `errorCode`, `ownership`, category, review/audit timestamps and the same reviewer enrichment fields as message sequence rows. Validate category/ownership against fixed enums and reason length against `MAX_IGNORE_REASON`.

- [ ] **Step 3: Implement DAO transaction semantics**

Implement list/count/export-approved/find/createAll/update/review/delete/batch operations and operation history. `createAll` must check input duplicates and existing `(category, ownership, error_code)` conflicts before inserting any row. Each inserted row gets a CREATE operation; updates write only changed old/new values; review and delete write their respective operations.

- [ ] **Step 4: Implement service/controller endpoints**

Expose the existing replay config endpoint family under `one-side-error-code-ignores`, without version parameters:

```text
GET    /config/one-side-error-code-ignores
POST   /config/one-side-error-code-ignores
PATCH  /config/one-side-error-code-ignores/{id}
POST   /config/one-side-error-code-ignores/{id}/review
POST   /config/one-side-error-code-ignores/batch-review
DELETE /config/one-side-error-code-ignores/{id}
POST   /config/one-side-error-code-ignores/batch-delete
GET    /config/one-side-error-code-ignores/{id}/operations
```

Reuse reviewer enrichment and `reviewableByMe`, `developedByMe`, `reasonEmpty`; add `ownership`, `errorCode`, and `category` filters. Keep operation source `MANUAL` for UI writes.

- [ ] **Step 5: Static backend verification and commit**

Run `rg` for resource key, DTO names, SQL columns, controller paths, and reviewer enrichment; run `git diff --check`. Do not run Maven, Java, Spring Boot, or backend tests. Commit as `feat: add one-side error code ignore resource`.

### Task 3: TXT Export

**Files:**
- Create or modify the replay export service/controller and API wrapper to add `exportReviewedOneSideErrorCodeIgnores()`.
- Test backend export formatting statically; frontend API test verifies request path and download filename.

- [ ] **Step 1: Write export tests first**

Assert only approved rows are included, rows are grouped in fixed category/ownership order, codes are comma-separated in creation order, empty groups remain present, and output uses UTF-8 BOM and CRLF.

- [ ] **Step 2: Implement exporter**

Use filename `单边失败错误码忽略-<yyyyMMddHHmmss>.txt`; output:

```text
查询：
528：E001,E002
ccbs：E003

账务：
528：E101
ccbs：
```

- [ ] **Step 3: Static check and commit**

Run `rg -n "one-side-error-code-ignores|单边失败错误码忽略|UTF-8|CRLF"`; do not execute backend export tests. Commit as `feat: export one-side error code ignores`.

### Task 4: Frontend Sixth Tab

**Files:**
- Modify: `D:\code\pf\AxonLink-frontend\src\components\replay\ReplayConfigPage.vue`
- Modify: `D:\code\pf\AxonLink-frontend\src\api\replayConfigs.js`
- Test: corresponding replay component/API specs.

- [ ] **Step 1: Add failing frontend tests**

Cover sixth tab presence, list columns with ownership before error code, category/ownership filters, create input splitting and payload, edit single-code validation, required reason, reviewer controls, empty-reason filter, export button/path, and reason ellipsis/title.

- [ ] **Step 2: Add schema and form**

Add tab key `one-side-error-code-ignores`; schema columns order: `category`, `serviceCode`, `ownership`, `errorCode`, `ignoreReason`, reviewer columns, `createdAt`, `updatedAt`. Add filters for category/serviceCode/ownership/errorCode/reviewStatus. Form order: serviceCode, category, ownership, errorCodes/errorCode, ignoreReason. New form uses textarea with comma hint; edit uses one-code text input.

- [ ] **Step 3: Add API wrappers and route behavior**

Add create/update/review/delete/list/history/batch wrappers and `exportReviewedOneSideErrorCodeIgnores`; reuse generic methods only where payload/path semantics remain exact. Ensure batch review/delete pass `{ ids }`, matching the no-version resource.

- [ ] **Step 4: Run focused frontend tests, fix, then run full tests/build**

Run the focused replay/API tests and then `npm test -- --run` and `npm run build`. Record any pre-existing unrelated failure; do not claim full pass if the known logo URL suite still fails.

- [ ] **Step 5: Commit frontend changes**

Commit as `feat: add one-side error code ignore tab`.

### Task 5: Cross-Repository Verification And Plan Evidence

- [ ] **Step 1: Verify contracts statically**

Check resource key, table names, enum values, endpoint paths, export labels, and absence of backend execution. Run `git diff --check` in both repositories and inspect statuses without reverting unrelated changes.

- [ ] **Step 2: Record evidence**

Append exact focused/full frontend test totals, build result, backend non-execution statement, and commit hashes in this plan.

- [ ] **Step 3: Commit plan evidence**

Commit as `docs: record one-side error code verification`. Do not push unless the user explicitly asks.
