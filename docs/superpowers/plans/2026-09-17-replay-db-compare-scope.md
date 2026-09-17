# Replay Database Comparison Scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add validated two-level WHERE condition groups and optional deterministic row limits to replay database comparison registrations, immutable versions, and generated GaussDB A-mode configuration SQL.

**Architecture:** Persist the registration condition tree as canonical JSON plus an optional row limit, because the tree is hierarchical and is not queried relationally. A focused backend compiler validates the tree against live BASE metadata, emits a safe WHERE fragment, and supplies canonical data to registration auditing, version hashing, immutable snapshots, and script generation. The frontend uses a dedicated scope editor component and never accepts free-form SQL.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, Jackson, JUnit 5, H2 migration tests, Vue 3, Vite, Vitest, GaussDB A-compatible SQL.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-系统设计.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-数据模型.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-API接口.md`

## Global Constraints

- WHERE conditions and `compareLimit` are optional; both absent must preserve the current full-table SQL byte-for-byte.
- Conditions support exactly two levels: an outer `AND/OR` connector over groups and one `AND/OR` connector inside each group.
- Supported operators are `EQ`, `NE`, `GT`, `GE`, `LT`, `LE`, `LIKE`, `IN`, `BETWEEN`, `IS_NULL`, and `IS_NOT_NULL`.
- Condition columns may be outside the comparison-field list but must exist in the same BASE table.
- No free SQL, functions, comments, semicolons, subqueries, or user-configurable ordering are accepted.
- `compareLimit` is `null` for full table or an integer from `1` through `10000000`.
- A configured limit always produces `ORDER BY` using the live primary-key constraint order captured in the version snapshot, followed by `LIMIT`.
- Version generation returns the complete gate error list and stores no partial snapshot on any condition or ordering failure.
- Existing registrations, imports, and historical versions remain full-table configurations unless explicitly edited.
- Generated `orig` and `dest` queries differ only in their fixed aliases.
- Both repos currently contain unrelated uncommitted work. Never reset, clean, stash, overwrite, or broadly stage it; stage only task-owned files.

---

### Task 1: Add Schema Columns, Condition DTOs, and BASE Data Types

**Files:**
- Create: `src/main/resources/db/daoindex/V70__replay_db_compare_scope.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareConditionConnector.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareConditionOperator.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareCondition.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareConditionGroup.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareConditionTree.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayBaseColumnOption.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayBaseMetadataService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonMigrationTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayBaseMetadataServiceTest.java`

**Interfaces:**
- Produces condition DTOs and BASE column `dataType` used by later tasks.
- Produces registration `where_condition_json`/`compare_limit`, version-table `where_condition_json`/`where_sql`/`compare_limit`, and version-field `primary_key_order`.

- [ ] **Step 1: Write failing migration and metadata tests**

```java
assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_REGISTRATION",
        "WHERE_CONDITION_JSON", "COMPARE_LIMIT");
assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_TABLE",
        "WHERE_CONDITION_JSON", "WHERE_SQL", "COMPARE_LIMIT");
assertColumns(jdbc, "DII_REPLAY_DB_COMPARE_VERSION_FIELD", "PRIMARY_KEY_ORDER");
assertEquals("character varying", result.get(0).dataType());
```

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonMigrationTest,ReplayBaseMetadataServiceTest test
```

Expected: compilation or assertions fail because the new migration and `dataType` are absent.

- [ ] **Step 3: Add V70 and immutable condition records**

```sql
ALTER TABLE dii_replay_db_compare_registration
    ADD COLUMN where_condition_json LONGTEXT,
    ADD COLUMN compare_limit BIGINT;
ALTER TABLE dii_replay_db_compare_version_table
    ADD COLUMN where_condition_json LONGTEXT,
    ADD COLUMN where_sql LONGTEXT,
    ADD COLUMN compare_limit BIGINT;
ALTER TABLE dii_replay_db_compare_version_field
    ADD COLUMN primary_key_order INT;
```

Records defensively copy lists and normalize null lists to empty lists. Enums use exactly the names in Global Constraints.

- [ ] **Step 4: Return database types from both metadata queries**

Add this expression to single-table and batch metadata SQL and map it into `ReplayBaseColumnOption.dataType()`:

```sql
format_type(a.atttypid, a.atttypmod) AS data_type
```

- [ ] **Step 5: Run tests and checkpoint**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonMigrationTest,ReplayBaseMetadataServiceTest test
```

Expected: PASS. If commits are requested, stage only Task 1 files and commit `feat(replay): add comparison scope schema and metadata`.

---

### Task 2: Build the Canonical Condition Codec and Safe SQL Compiler

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareScopeValidationError.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareCompiledScope.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonScopeException.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConditionCodec.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonScopeCompiler.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConditionCodecTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonScopeCompilerTest.java`

**Interfaces:**
- Consumes Task 1 condition DTOs and `Collection<ReplayBaseColumnOption>`.
- Produces `ReplayDbCompareCompiledScope compile(ReplayDbCompareConditionTree tree, Long compareLimit, Collection<ReplayBaseColumnOption> columns)`.
- Produces canonical codec methods `encode(tree)` and `decode(json)`.

- [ ] **Step 1: Write failing grouping, escaping, arity, and complete-error tests**

```java
ReplayDbCompareCompiledScope result = compiler.compile(tree(
        group(OR, condition("status", EQ, "1"), condition("status", EQ, "2")),
        group(AND, condition("amount", GE, "100"))), 1000L, columns);

assertEquals("(status = '1' OR status = '2') AND (amount >= 100)", result.whereSql());
assertEquals("name = 'O''Brien'", escaped.whereSql());
assertEquals(2, exception.errors().size());
assertEquals("whereCondition.groups[0].conditions[1]", exception.errors().get(0).path());
```

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonConditionCodecTest,ReplayDatabaseComparisonScopeCompilerTest test
```

- [ ] **Step 3: Implement canonical JSON and ordered validation errors**

Normalize column names to lower case, preserve group/condition/value order, normalize an empty tree to `null`, reject more than two levels, and accumulate all errors before throwing `ReplayDatabaseComparisonScopeException`.

```java
public record ReplayDbCompareCompiledScope(
        ReplayDbCompareConditionTree conditionTree,
        String whereSql,
        Long compareLimit) {
}
```

- [ ] **Step 4: Implement typed literal compilation**

Use explicit families: text quotes and doubles single quotes; numbers parse with `BigDecimal`; dates require ISO `yyyy-MM-dd`; timestamps require ISO date-time; booleans accept only `true/false`. Scalar operators and `LIKE` require one value, `IN` at least one, `BETWEEN` exactly two, and null operators none. Resolve emitted identifiers through the metadata map, never from unchecked request text.

- [ ] **Step 5: Run tests and checkpoint**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonConditionCodecTest,ReplayDatabaseComparisonScopeCompilerTest test
```

Expected: PASS. If commits are requested, commit only Task 2 files as `feat(replay): compile validated comparison conditions`.

---

### Task 3: Persist Registration Scope and Audit Its Changes

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareRegistration.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareSaveRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareReregisterRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditDiff.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonImportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditDiffTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonImportServiceTest.java`

**Interfaces:**
- Consumes compiler and codec from Task 2.
- Produces registration/save/reregister models with `whereCondition` and `compareLimit`.

- [ ] **Step 1: Write failing DAO round-trip and service validation tests**

```java
ReplayDbCompareRegistration saved = dao.findById(id).orElseThrow();
assertEquals(EQ, saved.whereCondition().groups().get(0).conditions().get(0).operator());
assertEquals(1000L, saved.compareLimit());
```

Also assert create, update, and reregister return all missing-column, invalid-value, and limit errors without writes.

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonDaoTest,ReplayDatabaseComparisonServiceTest,ReplayDatabaseComparisonAuditDiffTest,ReplayDatabaseComparisonImportServiceTest test
```

- [ ] **Step 3: Extend DAO writes and all row mappers**

Encode `whereCondition` into `where_condition_json`, bind `compare_limit` as nullable BIGINT, and decode every registration read path. Compatibility constructors may default both values to null.

- [ ] **Step 4: Compile before interactive writes**

After loading complete BASE columns and before result-store mutations:

```java
ReplayDbCompareCompiledScope scope = scopeCompiler.compile(
        request.whereCondition(), request.compareLimit(), metadata.columns());
```

Persist only `scope.conditionTree()` and `scope.compareLimit()`. Never persist request-provided SQL or request-provided data types.

- [ ] **Step 5: Add readable audit details and import compatibility**

Use `whereCondition` and `compareLimit` field codes. Render empty condition as `未配置` and null limit as `全表`. New imports default both fields to null; merging imported fields into an existing registration preserves its existing scope.

- [ ] **Step 6: Run tests and checkpoint**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonDaoTest,ReplayDatabaseComparisonServiceTest,ReplayDatabaseComparisonAuditDiffTest,ReplayDatabaseComparisonImportServiceTest test
```

Expected: PASS. If commits are requested, commit Task 3 files as `feat(replay): persist comparison scope registrations`.

---

### Task 4: Expose Live Drift Status and Complete API Errors

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareMetadataValidation.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareListItem.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/controller/ReplayDatabaseComparisonController.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/controller/ReplayDatabaseComparisonControllerTest.java`

**Interfaces:**
- Produces `missingConditionFieldNames`, `whereConditionConfigured`, and `compareLimit` for UI.
- Produces HTTP 422 `COMPARISON_SCOPE_INVALID` with ordered `errors[{path,reason}]`.

- [ ] **Step 1: Write failing drift and controller tests**

```java
assertEquals(List.of("legacy_status"),
        item.metadataValidation().missingConditionFieldNames());
assertTrue(item.whereConditionConfigured());
assertEquals(1000L, item.compareLimit());
```

MockMvc must assert one response contains both a missing condition field and an invalid limit.

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonServiceTest,ReplayDatabaseComparisonControllerTest test
```

- [ ] **Step 3: Compute condition drift separately**

Collect condition field names from the decoded tree and compare with the same batch BASE snapshot. Populate `missingConditionFieldNames` without mixing them into comparison `missingFieldNames`. Preserve `TABLE_MISSING` and `UNAVAILABLE` precedence.

- [ ] **Step 4: Add the 422 handler**

```java
@ExceptionHandler(ReplayDatabaseComparisonScopeException.class)
ResponseEntity<?> handleScopeInvalid(ReplayDatabaseComparisonScopeException exception) {
    return ResponseEntity.unprocessableEntity().body(error(
            "COMPARISON_SCOPE_INVALID", exception.getMessage(),
            Map.of("errors", exception.errors())));
}
```

- [ ] **Step 5: Run tests and checkpoint**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonServiceTest,ReplayDatabaseComparisonControllerTest test
```

Expected: PASS. If commits are requested, commit Task 4 files as `feat(replay): report comparison condition drift`.

---

### Task 5: Snapshot Scope, Primary-Key Order, and Configuration Hashes

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareVersionField.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareVersionTableItem.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareVersionGateError.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonVersionDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigurationHasher.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonVersionDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigurationHasherTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionServiceTest.java`

**Interfaces:**
- Consumes compiled scope from Task 2 and persisted scope from Task 3.
- Produces immutable version `whereCondition`, `whereSql`, `compareLimit`, and field `primaryKeyOrder`.

- [ ] **Step 1: Write failing immutable-snapshot, hash, and full-gate tests**

```java
assertEquals("status = '1'", snapshot.whereSql());
assertEquals(1000L, snapshot.compareLimit());
assertEquals(2, snapshot.fields().get(1).primaryKeyOrder());
assertNotEquals(hash(base), hash(withDifferentLimit));
assertNotEquals(hash(base), hash(withDifferentCondition));
```

A gate test must prove all invalid tables and all reasons per table are returned and no version rows are inserted.

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonVersionDaoTest,ReplayDatabaseComparisonConfigurationHasherTest,ReplayDatabaseComparisonVersionServiceTest test
```

- [ ] **Step 3: Compile against fresh metadata during generation**

Compile every registration against the freshly loaded table snapshot and capture true `primaryKeyOrder`. If `compareLimit` is set and a complete ordered primary key is unavailable, add a gate reason instead of creating a version.

- [ ] **Step 4: Persist/read immutable scope fields and extend hashing**

Write canonical condition JSON, compiled WHERE SQL, nullable limit, and nullable PK order in the existing version transaction. Hash condition JSON, compiled SQL, limit presence/value, and PK order after existing table/field values. Pre-V70 snapshots naturally read null values.

- [ ] **Step 5: Run tests and checkpoint**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonVersionDaoTest,ReplayDatabaseComparisonConfigurationHasherTest,ReplayDatabaseComparisonVersionServiceTest test
```

Expected: PASS. If commits are requested, commit Task 5 files as `feat(replay): snapshot comparison scopes in versions`.

---

### Task 6: Generate Conditional and Deterministically Limited SQL

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigScriptGenerator.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareConfigScriptValidationError.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigScriptGeneratorTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigScriptServiceTest.java`

**Interfaces:**
- Consumes immutable version scope and PK order from Task 5.
- Produces `(select ... from ... [where ...] [order by ... limit ...]) orig|dest`.

- [ ] **Step 1: Write exact tests for all four query shapes**

Test: no condition/no limit, condition/no limit, no condition/limit, and condition/limit with composite-key order. Composite expected SQL:

```sql
(select a,b,c from txn where (status = '1' OR status = '2') order by b,a limit 1000) orig
```

Use `b.primaryKeyOrder=1` and `a.primaryKeyOrder=2` regardless of comparison order.

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonConfigScriptGeneratorTest,ReplayDatabaseComparisonConfigScriptServiceTest test
```

- [ ] **Step 3: Centralize SELECT construction**

```java
private String selectSql(ReplayDbCompareVersionTableItem table) {
    StringBuilder sql = new StringBuilder("(select ")
            .append(fields(table)).append(" from ").append(table.tableName());
    if (hasText(table.whereSql())) sql.append(" where ").append(table.whereSql());
    if (table.compareLimit() != null) {
        sql.append(" order by ").append(primaryKeyFields(table));
        sql.append(" limit ").append(table.compareLimit());
    }
    return sql.append(") ").toString();
}
```

Validate that limited snapshots have contiguous non-null primary-key order beginning at 1. Return every invalid table in the existing complete script-validation list.

- [ ] **Step 4: Run tests and checkpoint**

```bash
mvn -q -Dtest=ReplayDatabaseComparisonConfigScriptGeneratorTest,ReplayDatabaseComparisonConfigScriptServiceTest test
```

Expected: PASS. If commits are requested, commit Task 6 files as `feat(replay): generate scoped comparison SQL`.

---

### Task 7: Build a Focused Frontend Scope Editor

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/replay/replayDatabaseComparisonScope.js`
- Create: `/Users/java/axon-link-frontend/src/components/replay/replayDatabaseComparisonScope.spec.js`
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonScopeEditor.vue`
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonScopeEditor.spec.js`

**Interfaces:**
- Consumes complete BASE columns with `columnName`, `columnComment`, and `dataType`.
- Produces `update:modelValue` for the condition tree, `update:compareLimit`, and a read-only preview.

- [ ] **Step 1: Write failing helper and component tests**

```javascript
expect(buildScopePreview(tree, columns, 1000, ['acct_no']))
  .toBe("where (status = '1' or status = '2')\\norder by acct_no\\nlimit 1000")
```

Mount and test adding a group, changing connectors, selecting a field/operator, entering `IN` values, deleting a condition, and rendering a missing-field marker.

- [ ] **Step 2: Run tests and verify failure**

```bash
cd /Users/java/axon-link-frontend
npx vitest run src/components/replay/replayDatabaseComparisonScope.spec.js \
  src/components/replay/ReplayDatabaseComparisonScopeEditor.spec.js
```

- [ ] **Step 3: Implement pure helpers**

Export exact functions:

```javascript
export function emptyConditionTree()
export function operatorsForDataType(dataType)
export function normalizeConditionTree(tree)
export function validateScopeDraft(tree, compareLimit, columns)
export function buildScopePreview(tree, columns, compareLimit, primaryKeyColumns)
```

The preview is informative; backend validation remains authoritative.

- [ ] **Step 4: Implement the two-level UI**

Render group cards as visual parentheses, explicit outer/group connector selectors, English/Chinese field search, operator-specific values, “添加条件”, “添加条件组”, numeric limit, and read-only SQL preview. Disable controls in table-missing and no-primary-key modes.

- [ ] **Step 5: Run tests and checkpoint**

```bash
cd /Users/java/axon-link-frontend
npx vitest run src/components/replay/replayDatabaseComparisonScope.spec.js \
  src/components/replay/ReplayDatabaseComparisonScopeEditor.spec.js
```

Expected: PASS. If commits are requested, commit only Task 7 files as `feat(replay): add comparison scope editor`.

---

### Task 8: Integrate Scope into Registration, List, History, and API Handling

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayDatabaseComparison.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayDatabaseComparison.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonEditor.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonEditor.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonVersionHistory.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonVersionHistory.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/replayDatabaseComparisonMock.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/replayDatabaseComparisonMock.spec.js`

**Interfaces:**
- Consumes Task 7 component and Tasks 3-6 API contract.
- Produces save/reregister payloads, scope tags, drift rendering, and immutable history display.

- [ ] **Step 1: Write failing integration tests**

Assert save emits:

```javascript
{
  tableName: 'txn',
  fieldNames: ['id', 'name'],
  whereCondition: expectedTree,
  compareLimit: 1000,
}
```

Assert tags `已配置条件`, `限1000条`, and `条件字段母库中不存在`. Assert `COMPARISON_SCOPE_INVALID` keeps the editor open and maps error paths. Assert version history renders immutable WHERE and limit.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
cd /Users/java/axon-link-frontend
npx vitest run src/api/replayDatabaseComparison.spec.js \
  src/components/replay/ReplayDatabaseComparisonEditor.spec.js \
  src/components/replay/ReplayDatabaseComparisonPage.spec.js \
  src/components/replay/ReplayDatabaseComparisonVersionHistory.spec.js \
  src/components/replay/replayDatabaseComparisonMock.spec.js
```

- [ ] **Step 3: Insert scope editor as section 3**

Place it below field selection, renumber registration information to section 4, initialize from detail data for edit/reregister, and clear scope when changing to another unregistered table.

- [ ] **Step 4: Wire save and complete error handling**

Pass normalized scope to create/update/reregister. Preserve the current form and selected fields after 422. Map `details.errors[].path` into the scope editor instead of flattening errors into one generic message.

- [ ] **Step 5: Add compact list/history display and deterministic mocks**

Do not add wide columns. Render compact tags under table identity; show full immutable scope in view/history. Missing condition fields use the existing red-state color family with distinct text. Add mocks for full table, condition only, limit only, condition plus composite-key limit, and missing condition field.

- [ ] **Step 6: Run tests and checkpoint**

```bash
cd /Users/java/axon-link-frontend
npx vitest run src/api/replayDatabaseComparison.spec.js \
  src/components/replay/ReplayDatabaseComparisonEditor.spec.js \
  src/components/replay/ReplayDatabaseComparisonPage.spec.js \
  src/components/replay/ReplayDatabaseComparisonVersionHistory.spec.js \
  src/components/replay/replayDatabaseComparisonMock.spec.js
```

Expected: PASS. If commits are requested, commit only Task 8 files as `feat(replay): integrate comparison scope configuration`.

---

### Task 9: Full Regression and Embedded Frontend Build

**Files:**
- Generated: `src/main/resources/static/**`
- Verify: all files changed by Tasks 1-8.

**Interfaces:**
- Produces tested frontend assets embedded in backend resources.

- [ ] **Step 1: Run the complete backend dbcompare suite**

```bash
cd /Users/java/axon-link-server
mvn -q -Dtest='ReplayDatabaseComparison*Test,ReplayBase*Test' test
```

Expected: all dbcompare tests pass.

- [ ] **Step 2: Run the complete frontend suite**

```bash
cd /Users/java/axon-link-frontend
npm test
```

Expected: Vitest exits 0.

- [ ] **Step 3: Build against the real backend**

```bash
cd /Users/java/axon-link-frontend
VITE_USE_MOCK=0 npm run build
```

Expected: Vite writes `index.html` and hashed assets to `/Users/java/axon-link-server/src/main/resources/static`.

- [ ] **Step 4: Verify asset references and Maven packaging**

```bash
cd /Users/java/axon-link-server
test -f src/main/resources/static/index.html
mvn -q -DskipTests package
```

Expected: Maven packaging exits 0. Manually verify every `/assets/...` reference in `index.html` exists.

- [ ] **Step 5: Inspect final diffs without touching unrelated work**

```bash
git -C /Users/java/axon-link-server diff --check
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/axon-link-server status --short
git -C /Users/java/axon-link-frontend status --short
```

Confirm the feature did not modify authentication, report mail, unrelated replay issues, or deployment configuration. Do not create a source ZIP, clean worktrees, or commit unrelated pre-existing changes unless explicitly requested.

- [ ] **Step 6: Commit generated static assets only when requested**

If repository commits are requested, stage only `src/main/resources/static` and commit `build: embed replay comparison scope frontend`; otherwise leave generated assets uncommitted.
