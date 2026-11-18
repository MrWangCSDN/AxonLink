# Ignore Field Chinese Names Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist required Chinese names for unconditional-ignore, conditional-ignore, and sort-field configurations; export them to Excel; remove the unconditional reason-import UI; and render all five reason columns as single-line ellipsis with full hover text.

**Architecture:** Add nullable `field_chinese_name` columns through a V93 result-database migration so historical rows remain readable, while application create/update validation requires a nonblank value of at most 256 characters. Carry the value through DTOs, services, JDBC persistence, operation history, review-reset detection, and Excel export; sort creation accepts separate old/new Chinese names and assigns them during suffix expansion. The Vue schema drives the three list/form additions and a dedicated ellipsis column style; the backend import endpoint remains unchanged for compatibility while all frontend access is deleted.

**Tech Stack:** Java 17, Spring Boot JDBC, MySQL migration SQL, JUnit 5/H2 fixtures, Apache POI, Vue 3, Vitest, Vite.

**Spec:** `C:\Users\hengj\Documents\ChatGPT\myjob-ai\obsidian-vault\01 Engineering\axon-link-server\并行回放忽略清单-系统设计.md` (Obsidian commit `9cae6e7`), plus the linked data-model and API documents.

## Global Constraints

- Do not execute backend code, Maven commands, or backend tests. Backend tests must be written first and reviewed statically, but remain unexecuted per user instruction.
- Frontend targeted/full Vitest suites and `npm run build` may be executed.
- Historical rows may keep `field_chinese_name = NULL`; create and update require a trimmed nonblank value of at most 256 characters.
- A Chinese-name change resets `review_status` to `0`; a reason-only change preserves the existing review status.
- Sort creation uses `oldSortFieldChineseName` for `&sop` and `newSortFieldChineseName` for `&soap` / `&bzjson`; ordinary rows and updates use `fieldChineseName`.
- Keep `POST /config/unconditional-ignores/import-ignore-reasons` and its backend parser/service tests unchanged; delete only the frontend API wrapper, modal, state, and tests.
- Do not modify historical migrations. Create `V93__dii_replay_config_field_chinese_name.sql` because V92 is the current highest migration number.
- Preserve user changes and unrelated working-tree files. Commit backend and frontend changes in their own repositories.

---

### Task 1: Database Migration And Test Schema Contract

**Files:**
- Create: `src/main/resources/db/daoindex/V93__dii_replay_config_field_chinese_name.sql`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayConfigTestFixtures.java`

**Interfaces:**
- Produces: nullable `field_chinese_name VARCHAR(256)` on three business tables.
- Produces: nullable `field_chinese_name` and `new_field_chinese_name` on the corresponding three operation tables.
- Consumed by: all JDBC statements and row mappers in Tasks 2 and 3.

- [x] **Step 1: Extend the H2 test schema before production persistence code**

Add `field_chinese_name VARCHAR(256)` immediately after each field-name column in `dii_replay_unconditional_ignore`, `dii_replay_conditional_rmove`, and `dii_replay_sort_field`. Add both audit columns immediately after the old/new field-name columns in each matching `_operation` fixture table:

```sql
field_chinese_name VARCHAR(256),
new_field_chinese_name VARCHAR(256),
```

This intentionally makes current DAO tests fail to compile or assert once the record constructors and expectations in Task 2 are added. Per the global constraint, do not run Maven to observe RED.

- [x] **Step 2: Add the exact V93 migration**

```sql
ALTER TABLE dii_replay_unconditional_ignore
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略字段中文名称' AFTER field_name;
ALTER TABLE dii_replay_unconditional_ignore_operation
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '原忽略字段中文名称' AFTER field_name,
    ADD COLUMN new_field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '新忽略字段中文名称' AFTER new_field_name;

ALTER TABLE dii_replay_conditional_rmove
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '忽略字段中文名称' AFTER field_rmove_name;
ALTER TABLE dii_replay_conditional_rmove_operation
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '原忽略字段中文名称' AFTER field_rmove_name,
    ADD COLUMN new_field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '新忽略字段中文名称' AFTER new_field_rmove_name;

ALTER TABLE dii_replay_sort_field
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '排序字段中文名称' AFTER orig_field_name;
ALTER TABLE dii_replay_sort_field_operation
    ADD COLUMN field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '原排序字段中文名称' AFTER orig_field_name,
    ADD COLUMN new_field_chinese_name VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '新排序字段中文名称' AFTER new_orig_field_name;
```

- [x] **Step 3: Perform static migration verification**

Run only read-only checks, not database execution:

```powershell
rg -n "field_chinese_name|new_field_chinese_name" src/main/resources/db/daoindex/V93__dii_replay_config_field_chinese_name.sql src/test/java/com/axonlink/ai/replay/ReplayConfigTestFixtures.java
git diff --check
```

Expected: six `ALTER TABLE` targets are present; fixture columns cover the same six tables; `git diff --check` reports no errors.

- [x] **Step 4: Commit the migration foundation**

```powershell
git add src/main/resources/db/daoindex/V93__dii_replay_config_field_chinese_name.sql src/test/java/com/axonlink/ai/replay/ReplayConfigTestFixtures.java
git commit -m "feat: add replay field chinese name schema"
```

### Task 2: Backend Chinese-Name Persistence And Review Semantics

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayConfigValidation.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayUnconditionalIgnoreCreateRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayUnconditionalIgnoreUpdateRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayUnconditionalIgnoreRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayUnconditionalIgnoreDraft.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayConditionalRmoveCreateRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayConditionalRmoveUpdateRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayConditionalRmoveRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayConditionalRmoveDraft.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplaySortFieldCreateRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplaySortFieldUpdateRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplaySortFieldRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplaySortFieldDraft.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayUnconditionalIgnoreService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayConditionalRmoveService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplaySortFieldService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayUnconditionalIgnoreDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayConditionalRmoveDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplaySortFieldDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayUnconditionalIgnoreServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayConditionalRmoveServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplaySortFieldServiceTest.java`

**Interfaces:**
- Produces: `ReplayConfigValidation.requireFieldChineseName(String)` and `MAX_FIELD_CHINESE_NAME = 256`.
- Produces: `fieldChineseName` on ordinary create/update/row/draft records.
- Produces: `oldSortFieldChineseName` and `newSortFieldChineseName` only on `ReplaySortFieldCreateRequest`.
- Consumed by: controllers through existing JSON binding, export service in Task 3, and frontend payloads in Task 4.

- [x] **Step 1: Write backend regressions first without running them**

For each of the three service test classes, update existing constructors and add assertions covering:

```java
assertEquals("账号", created.fieldChineseName());
assertThrows(IllegalArgumentException.class, () -> service.create(
        new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", " ", REASON), OPERATOR));
assertThrows(IllegalArgumentException.class, () -> service.create(
        new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", "中".repeat(257), REASON), OPERATOR));
```

Use the corresponding full conditional and sort request constructors in their own test classes; do not introduce a shared test helper:

```java
new ReplayConditionalRmoveCreateRequest(CODE, "accountNo", "账号", 1, null, null, "测试原因")
new ReplaySortFieldCreateRequest("6208", "accounts.accountNo", "老核心账号",
        "accounts.accountNo", "新核心账号", "排序字段忽略原因")
```

For an already-reviewed row, assert reason-only update preserves status, then assert Chinese-name-only update resets status and appears in operation history:

```java
assertEquals(1, reasonUpdated.reviewStatus());
ReplayUnconditionalIgnoreRow renamed = service.update(reasonUpdated.id(),
        new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNo", "客户账号", "新原因",
                reasonUpdated.version()), OPERATOR);
assertEquals(0, renamed.reviewStatus());
assertTrue(service.operations(renamed.id(), 10, 0).items().get(0).changes().stream()
        .anyMatch(change -> "field_chinese_name".equals(change.field())));
```

In `ReplaySortFieldServiceTest`, create with `"老核心账号"` and `"新核心账号"`; assert `&sop` has the old value and both `&soap` and `&bzjson` have the new value. Do not execute these tests.

- [x] **Step 2: Add the shared validation contract**

Add to `ReplayConfigValidation`:

```java
public static final int MAX_FIELD_CHINESE_NAME = 256;

public static String requireFieldChineseName(String value) {
    String trimmed = requireText(value, "中文名称");
    if (trimmed.length() > MAX_FIELD_CHINESE_NAME) {
        throw new IllegalArgumentException("中文名称不能超过 256 个字符");
    }
    return trimmed;
}
```

- [x] **Step 3: Extend DTOs with one consistent field contract**

Use these complete record shapes:

```java
ReplayUnconditionalIgnoreCreateRequest(String tranCode, String fieldName,
        String fieldChineseName, String ignoreReason)
ReplayUnconditionalIgnoreUpdateRequest(String tranCode, String fieldName,
        String fieldChineseName, String ignoreReason, Integer version)
ReplayUnconditionalIgnoreDraft(String tranCode, String fieldName,
        String fieldChineseName, String ignoreReason)

ReplayConditionalRmoveCreateRequest(String origTrcd, String fieldRmoveName,
        String fieldChineseName, Integer fieldFileFlag, String origFieldCond,
        String destFieldCond, String ignoreReason)
ReplayConditionalRmoveUpdateRequest(String origTrcd, String fieldRmoveName,
        String fieldChineseName, Integer fieldFileFlag, String origFieldCond,
        String destFieldCond, String ignoreReason, Integer version)
ReplayConditionalRmoveDraft(String origTrcd, String fieldRmoveName,
        String fieldChineseName, int fieldFileFlag, String origFieldCond,
        String destFieldCond, String ignoreReason)

ReplaySortFieldCreateRequest(String tranCode, String oldSortField,
        String oldSortFieldChineseName, String newSortField,
        String newSortFieldChineseName, String ignoreReason)
ReplaySortFieldUpdateRequest(String origTrcd, String origArryName,
        String origFieldName, String fieldChineseName, String ignoreReason, Integer version)
ReplaySortFieldDraft(String origTrcd, String origArryName,
        String origFieldName, String fieldChineseName, String ignoreReason)

ReplayUnconditionalIgnoreRow(long id, String tranCode, String fieldName,
        String fieldChineseName, String ignoreReason, int enableFlag,
        LocalDateTime createdAt, LocalDateTime updatedAt, int version, int reviewStatus,
        String oldTransactionCode, String developer, String bankOwner, boolean canReview,
        String reviewDisabledReason, String domain)
ReplayConditionalRmoveRow(long id, String origTrcd, String fieldRmoveName,
        String fieldChineseName, int fieldFielState, int fieldFileIndx, int fieldFileFlag,
        String origFieldCond, String destFieldCond, String ignoreReason,
        LocalDateTime createdAt, LocalDateTime updatedAt, int version, int reviewStatus,
        String oldTransactionCode, String developer, String bankOwner, boolean canReview,
        String reviewDisabledReason, String domain)
ReplaySortFieldRow(long id, String origTrcd, String origArryName, String origFieldName,
        String fieldChineseName, String ignoreReason, int tranMode, LocalDateTime createdAt,
        LocalDateTime updatedAt, int version, int reviewStatus, String oldTransactionCode,
        String developer, String bankOwner, boolean canReview, String reviewDisabledReason,
        String domain)
```

- [x] **Step 4: Validate and propagate Chinese names in services**

Call `requireFieldChineseName` in every create, batch-create, and update path. In sort creation, validate the two input names independently and pass the selected name into suffix expansion:

```java
addDraft(drafts, seen, base + "&sop", oldSortField, oldSortFieldChineseName, ignoreReason);
addDraft(drafts, seen, base + "&soap", newSortField, newSortFieldChineseName, ignoreReason);
addDraft(drafts, seen, base + "&bzjson", newSortField, newSortFieldChineseName, ignoreReason);
```

Change `addDraft` to accept `fieldChineseName` and construct `ReplaySortFieldDraft` with it. Do not add Chinese names to error-code or message-sequence DTOs.

- [x] **Step 5: Persist, map, and audit the field in all three DAOs**

For each DAO:

- add `field_chinese_name` to `SELECT_COLUMNS` and `mapRow`;
- add it to insert/update SQL and method parameters;
- pass it through `createAll` drafts;
- include `field_chinese_name` / `new_field_chinese_name` in every operation insert;
- include old values on DELETE and null values on REVIEW;
- add history label `中文名称` in `mapOperation`;
- include Chinese-name inequality in `businessChanged`, so it resets review status;
- preserve current import-reason behavior and operation source.

The update decision must be equivalent to:

```java
boolean chineseNameChanged = changed(current.fieldChineseName(), newFieldChineseName);
boolean businessChanged = tranChanged || fieldChanged || chineseNameChanged;
int nextReviewStatus = businessChanged ? 0 : current.reviewStatus();
```

For conditional-ignore, include service-code, technical-field, flag, generated-index, and both condition changes in the same expression. For sort-field, include service-code, array-name, and technical-field changes.

- [x] **Step 6: Perform backend static verification only**

```powershell
rg -n "fieldChineseName|field_chinese_name|new_field_chinese_name|oldSortFieldChineseName|newSortFieldChineseName" src/main/java src/test/java
rg -n "Replay(UnconditionalIgnore|ConditionalRmove|SortField)(CreateRequest|UpdateRequest|Row|Draft)" src/main/java src/test/java
git diff --check
```

Expected: every constructor call reflects the new signatures; all three DAO operation mappers label the field; there are no whitespace errors. Do not run `mvn`, Java, Spring Boot, or backend tests.

- [x] **Step 7: Commit the backend contract**

```powershell
git add src/main/java/com/axonlink/ai/replay src/test/java/com/axonlink/ai/replay
git commit -m "feat: persist replay field chinese names"
```

### Task 3: Excel Export Chinese-Name Columns

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayConfigExportService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayConfigExportServiceTest.java`

**Interfaces:**
- Consumes: `fieldChineseName()` from the three row records introduced in Task 2.
- Produces: three updated sheet layouts; error-code sheet remains byte-for-byte column-compatible.

- [x] **Step 1: Update export assertions before implementation**

Change the expected headers and row offsets to these exact business sections:

```java
List.of("忽略字段", "中文名称")
List.of("忽略字段", "中文名称", "字段索引", "字段标识", "主系统字段忽略条件", "备系统字段忽略条件")
List.of("对象/数组名称", "排序字段", "中文名称")
```

Assert the Chinese value in the cell immediately after `忽略字段` or `排序字段`. Keep the error-code header assertion unchanged. Do not execute the backend test.

- [x] **Step 2: Add headers and values in the same positions**

Update `writeSheet` calls and business-value lists so headers and row data stay aligned:

```java
List.of(text(row.fieldName()), text(row.fieldChineseName()))
List.of(text(row.fieldRmoveName()), text(row.fieldChineseName()), String.valueOf(row.fieldFileIndx()),
        flagText(row.fieldFileFlag()), text(row.origFieldCond()), text(row.destFieldCond()))
List.of(text(row.origArryName()), text(row.origFieldName()), text(row.fieldChineseName()))
```

Historical null names must pass through the existing `text(row.fieldChineseName())` handling and export as empty cells.

- [x] **Step 3: Verify statically and commit**

```powershell
rg -n "中文名称|fieldChineseName" src/main/java/com/axonlink/ai/replay/service/ReplayConfigExportService.java src/test/java/com/axonlink/ai/replay/service/ReplayConfigExportServiceTest.java
git diff --check
git add src/main/java/com/axonlink/ai/replay/service/ReplayConfigExportService.java src/test/java/com/axonlink/ai/replay/service/ReplayConfigExportServiceTest.java
git commit -m "feat: export replay field chinese names"
```

Do not run the export test or any backend code.

### Task 4: Frontend Forms, Columns, Ellipsis, And Import Removal

**Files:**
- Modify: `D:\code\pf\AxonLink-frontend\src\components\replay\ReplayConfigPage.vue`
- Modify: `D:\code\pf\AxonLink-frontend\src\components\replay\ReplayConfigPage.spec.js`
- Modify: `D:\code\pf\AxonLink-frontend\src\api\replayConfigs.js`
- Modify: `D:\code\pf\AxonLink-frontend\src\api\replayConfigs.spec.js`

**Interfaces:**
- Consumes/produces JSON `fieldChineseName` for unconditional, conditional, and ordinary sort rows/updates.
- Produces sort-create JSON `oldSortFieldChineseName` and `newSortFieldChineseName`.
- Deletes frontend symbol `importUnconditionalIgnoreReasons`; does not change the backend endpoint.

- [x] **Step 1: Replace import tests with new UI contract tests**

Delete API and component tests for `importUnconditionalIgnoreReasons` and assert the button/modal no longer exists. Add component tests that:

```javascript
expect(wrapper.find('[data-testid="open-reason-import"]').exists()).toBe(false)
```

- locate `fieldChineseName` inputs for unconditional, conditional, and sort edit forms;
- verify empty values block submit with `中文名称不能为空`;
- verify `maxlength="256"`;
- assert create/update payloads contain `fieldChineseName`;
- assert sort-create payload contains both `oldSortFieldChineseName` and `newSortFieldChineseName`;
- assert reason cells across all five tabs have the ellipsis class and `title` contains the complete multiline/long reason.

- [x] **Step 2: Run targeted frontend tests to observe RED**

```powershell
npm test -- --run src/components/replay/ReplayConfigPage.spec.js src/api/replayConfigs.spec.js
```

Expected: FAIL because Chinese-name controls and ellipsis class do not exist, and the import UI/API still exist.

- [x] **Step 3: Remove all frontend reason-import access**

Delete from `ReplayConfigPage.vue` the import button, modal markup, import API import, four reactive state values, input ref, and `openReasonImport` / `closeReasonImport` / `submitReasonImport`. Delete `importUnconditionalIgnoreReasons` from `replayConfigs.js` and its API test/import. A repository search must return no matches:

```powershell
rg -n "importUnconditionalIgnoreReasons|open-reason-import|reason-import|reasonImport" src
```

Expected: exit code 1 with no output.

- [x] **Step 4: Add the three schema-driven Chinese-name columns and forms**

Insert this column immediately after each technical field column:

```javascript
{ key: 'fieldChineseName', label: '中文名称' }
```

Insert this required form field immediately after each technical field input:

```javascript
{ key: 'fieldChineseName', label: '中文名称', kind: 'text', required: true, maxlength: 256 }
```

For `SORT_CREATE_FORM`, add two separate required inputs:

```javascript
{ key: 'oldSortFieldChineseName', label: '老核心中文名称', kind: 'text', required: true, maxlength: 256 },
{ key: 'newSortFieldChineseName', label: '新核心中文名称', kind: 'text', required: true, maxlength: 256 },
```

Place each directly after its matching sort-field input. Add `fieldChineseName` to confirm-field definitions so review dialogs show the value.

- [x] **Step 5: Give only reason columns single-line ellipsis behavior**

Replace `long: true` with `ellipsis: true` only for each `ignoreReason` column in all five schemas. Keep long wrapping for conditions and message sequence values. Update the cell binding:

```vue
<td
  v-for="column in schema.columns"
  :key="column.key"
  :class="{ 'replay-cell-long': column.long, 'replay-cell-ellipsis': column.ellipsis }"
  :title="displayValue(column, row)"
>
```

Add stable single-line styling without changing the existing long-cell rule:

```css
.replay-cell-ellipsis {
  width: 240px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```

- [x] **Step 6: Run frontend targeted tests, full tests, and build**

```powershell
npm test -- --run src/components/replay/ReplayConfigPage.spec.js src/api/replayConfigs.spec.js
npm test
npm run build
```

Expected: all Vitest tests pass and Vite build succeeds. Record exact test totals and build output in the handoff; do not copy build output into the backend unless separately requested during execution.

- [x] **Step 7: Commit frontend changes**

```powershell
git add src/components/replay/ReplayConfigPage.vue src/components/replay/ReplayConfigPage.spec.js src/api/replayConfigs.js src/api/replayConfigs.spec.js
git commit -m "feat: add replay field chinese names"
```

### Task 5: Cross-Repository Static Verification And Delivery Notes

**Files:**
- Modify: `docs/superpowers/plans/2026-11-17-ignore-field-chinese-names.md` (check boxes and evidence only)
- Review: all files changed in Tasks 1-4.

**Interfaces:**
- Verifies: migration ↔ DTO ↔ DAO ↔ service ↔ export ↔ frontend property-name consistency.
- Produces: implementation evidence without executing backend code or tests.

- [x] **Step 1: Check exact property and column coverage**

From the backend repository:

```powershell
rg -n "fieldChineseName|oldSortFieldChineseName|newSortFieldChineseName" src/main/java src/test/java
rg -n "field_chinese_name|new_field_chinese_name" src/main/resources/db/daoindex/V93__dii_replay_config_field_chinese_name.sql src/main/java src/test/java
git diff --check HEAD~3 HEAD
git status --short
```

From the frontend repository:

```powershell
rg -n "fieldChineseName|oldSortFieldChineseName|newSortFieldChineseName|replay-cell-ellipsis" src
rg -n "importUnconditionalIgnoreReasons|open-reason-import|reason-import|reasonImport" src
git diff --check HEAD~1 HEAD
git status --short
```

Expected: all three property names are present at their intended boundaries; import search has no output; both worktrees are clean except pre-existing unrelated changes.

- [x] **Step 2: Review the four behavioral invariants**

Inspect diffs and confirm explicitly:

1. A reason-only update never contributes to `businessChanged`.
2. A Chinese-name-only update contributes to `businessChanged` and writes both operation columns.
3. Sort suffix expansion assigns old name only to `&sop`, new name only to `&soap` and `&bzjson`.
4. Error-code and message-sequence persistence/export contracts did not acquire Chinese-name fields.

- [x] **Step 3: Record verification evidence in this plan**

Append a `## Verification Evidence` section containing frontend targeted/full test totals, build result, static-check results, and the exact statement: `Backend code and tests were not executed per user instruction.` Do not claim backend runtime success.

- [x] **Step 4: Commit plan evidence**

```powershell
git add docs/superpowers/plans/2026-11-17-ignore-field-chinese-names.md
git commit -m "docs: record replay chinese name verification"
```

- [x] **Step 5: Report delivery state without pushing**

Report backend and frontend commit hashes, the migration path, frontend test/build evidence, and the unexecuted backend verification limitation. Do not push either repository or the Obsidian commit until the user explicitly authorizes pushing.

## Verification Evidence

- Frontend targeted Vitest: 2 files, 48 tests passed.
- Frontend full Vitest: 36 files passed, 677 tests passed; the pre-existing `src/views/TransactionAnalysis.spec.js` suite failed during module setup because the test environment rejected `file:///spd-bank-logo.png` as a filename. No replay-config test failed.
- Frontend production build: `npm run build` succeeded; Vite transformed 5,651 modules and emitted the static bundle.
- Static checks: `git diff --check` passed; frontend search confirms the reason-import symbols and UI test IDs are absent; backend property/migration references were reviewed across DTOs, services, DAOs, tests, and V93.
- Backend code and tests were not executed per user instruction.
