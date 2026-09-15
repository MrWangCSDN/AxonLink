# Replay Database Comparison Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有“回放数据库比对字段登记”前端 Mock 升级为可连接固定 BASE 母库、持久化登记、允许所有登录用户维护，并永久保存逐字段审计的生产功能。

**Architecture:** 后端新增独立 `com.axonlink.ai.replay.dbcompare` 模块，使用只读 Hikari 数据源查询固定 Schema 元数据，使用现有 DII 结果库保存登记、当前字段、审计事件和审计明细。前端保留已验收的列表与字段穿梭交互，替换 Mock 数据源并新增全局审计查询；删除使用逻辑删除并清空当前字段，重新登记复用永久业务键但必须重新选择当前母库字段。

**Tech Stack:** Java 17、Spring Boot 3.1、Spring JDBC、HikariCP、Apache POI、MySQL/H2-compatible DDL、Vue 3、Vite、Vitest。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-系统设计.md`、`/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-数据模型.md`、`/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-API接口.md`

## Global Constraints

- 执行前使用 `superpowers:using-git-worktrees`；后端基于当前 `HEAD` 创建 `/Users/java/axon-link-server/.worktrees/replay-db-comparison-fields`，不得带入或覆盖主工作区现有日报、周报未提交改动。
- 前端继续使用 `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields` 和分支 `codex/replay-db-comparison-fields`，保留已完成的 Mock 页面改动。
- BASE URL、Schema、账号和密码只在后端配置；只读系统元数据，不读取业务表记录，不向响应或日志暴露连接信息和原始 SQL 异常。
- `(schema_name, table_name)` 永久唯一。删除后正常列表不可见，但审计永久可查；重新登记复用原登记 ID，重新选择字段，不自动恢复旧字段。
- 所有已登录用户均可新增、编辑、删除和重新登记；修订人仅表示最近一次实际修改者，不参与权限判断。初始化导入仍要求管理员和 `X-DII-Trigger-Token`。
- 每次有实际变化的保存只生成一个审计事件，每个属性或比对字段变化生成一条审计明细。登记、当前字段、审计事件和明细必须同事务提交或回滚。
- 不改变回放问题清单、Excel 问题导入、状态流转、日报、周报和全量交易人员清单行为。
- 后端测试使用 Java 17：

```bash
export JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

---

### Task 1: Frontend Reviser Label and Audit Interaction Mock

**Files:**
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonPage.spec.js`
- Create: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonAuditDialog.vue`
- Create: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonAuditDialog.spec.js`

**Interfaces:**
- `ReplayDatabaseComparisonPage` exposes list field `reviser` instead of `owner`.
- `ReplayDatabaseComparisonAuditDialog` props: `tableName?: string`, `events: ReplayAuditEvent[]`, `loading: boolean`; emits `search`, `loadDetails`, and `close`.
- Mock audit event shape: `{ id, tableName, operation, operatorName, operatedAt, changeCount, details }`.

- [ ] **Step 1: Write failing list semantics tests**

Assert the header and filter label use `修订人`, no `负责人` header remains, every row always renders edit/delete actions, saving a row sets the current Mock login as reviser, and no permission-derived disabled state exists.

```javascript
expect(wrapper.get('[data-column-key="reviser"]').text()).toContain('修订人')
expect(wrapper.get('[data-testid="edit-registration-kdpa_cb_acct_fzn_cntl_inf"]').attributes('disabled')).toBeUndefined()
expect(wrapper.get('[data-testid="delete-registration-kdpa_cb_acct_fzn_cntl_inf"]').attributes('disabled')).toBeUndefined()
```

- [ ] **Step 2: Run focused list tests and verify RED**

Run:

```bash
npm test -- src/components/replay/ReplayDatabaseComparisonPage.spec.js
```

Expected: FAIL because the page still uses `负责人` and `owner`.

- [ ] **Step 3: Implement reviser naming and all-user actions**

Rename row/filter/display state from `owner` to `reviser`, keep compact ellipsis/title/copy behavior, and update Mock save/delete handlers so every save records the current Mock user without checking row ownership or group ownership.

- [ ] **Step 4: Write failing audit-dialog tests**

Cover the row-level audit button pre-filling the table name, global `审计查询` opening without a table, newest-first events, exact `yyyy-MM-dd HH:mm:ss` display, collapsed detail groups, copy-all details, and deleted-table events remaining searchable.

```javascript
expect(wrapper.findAll('[data-testid="audit-event"]')[0].attributes('data-event-id')).toBe('103')
expect(wrapper.get('[data-testid="audit-detail-103-1"]').text())
  .toContain('周皓 修改 领域 存款组 公共组 2026-09-12 14:36:08')
```

- [ ] **Step 5: Implement the audit dialog Mock**

Add toolbar button `审计查询`. Render filters for table English name, operator, operation, and time range. Render event headers newest first and detail columns `谁 / 动作 / 字段 / 修改前 / 修改后 / 时间`; use red `DELETE` and green `REREGISTER` badges. Keep backdrop non-dismissible and close only via the upper-right `X` or explicit close button.

- [ ] **Step 6: Run focused tests and commit**

```bash
npm test -- src/components/replay/ReplayDatabaseComparisonPage.spec.js src/components/replay/ReplayDatabaseComparisonAuditDialog.spec.js
git add src/components/replay/ReplayDatabaseComparisonPage.vue src/components/replay/ReplayDatabaseComparisonPage.spec.js src/components/replay/ReplayDatabaseComparisonAuditDialog.vue src/components/replay/ReplayDatabaseComparisonAuditDialog.spec.js
git commit -m "feat(replay): add comparison field audit mock"
```

---

### Task 2: Fixed Read-Only BASE Metadata Access

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/config/ReplayDatabaseComparisonProperties.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayBaseDataSourceRegistry.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayBaseMetadataService.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayBaseDatabaseUnavailableException.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayBaseMetadataInvalidException.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayBaseTableOption.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayBaseColumnOption.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayBaseValidatedTable.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/config/ReplayDatabaseComparisonPropertiesTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayBaseDataSourceRegistryTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayBaseMetadataServiceTest.java`

**Interfaces:**
- `ReplayBaseDataSourceRegistry.requireDataSource(): DataSource`.
- `ReplayBaseMetadataService.searchTables(String keyword, int limit): List<ReplayBaseTableOption>`.
- `ReplayBaseMetadataService.listColumns(String tableName, String keyword): List<ReplayBaseColumnOption>`.
- `ReplayBaseMetadataService.requireTableWithColumns(String tableName, Collection<String> fieldNames): ReplayBaseValidatedTable`.

- [ ] **Step 1: Write failing properties and registry tests**

Use `ApplicationContextRunner`. Assert binding under `replay-database-comparison`, blank BASE URL allowing application startup, configured Hikari `readOnly=true`, maximum pool size `3`, timeout `5000`, Schema identifier validation, and sanitized failure when `requireDataSource()` is called without configuration.

- [ ] **Step 2: Run properties tests and verify RED**

```bash
mvn -Dtest='ReplayDatabaseComparisonPropertiesTest,ReplayBaseDataSourceRegistryTest' test
```

Expected: missing-class compilation failures.

- [ ] **Step 3: Implement lazy read-only configuration**

Bind these exact keys:

```yaml
replay-database-comparison:
  base-datasource:
    url: ${REPLAY_BASE_DB_URL:}
    username: ${REPLAY_BASE_DB_USERNAME:}
    password: ${REPLAY_BASE_DB_PASSWORD:}
    driver-class-name: ${REPLAY_BASE_DB_DRIVER:}
    schema: ${REPLAY_BASE_DB_SCHEMA:}
    maximum-pool-size: ${REPLAY_BASE_DB_POOL_SIZE:3}
    connection-timeout-ms: ${REPLAY_BASE_DB_TIMEOUT_MS:5000}
  admin-employee-nos: ${REPLAY_DB_COMPARE_ADMINS:}
  import-enabled: ${REPLAY_DB_COMPARE_IMPORT_ENABLED:true}
```

Only log configured state, Schema, and pool size.

- [ ] **Step 4: Write failing metadata tests**

Prove keyword minimum two characters, limit clamp to 50, prepared-statement parameters for Schema/table/keyword, openGauss table and column comments, primary-key detection including composite keys, stable ordinal order, complete missing-field reporting, and sanitized connection errors.

- [ ] **Step 5: Implement metadata queries**

Query `pg_class`, `pg_namespace`, `pg_attribute`, `pg_constraint`, `obj_description`, and `col_description`. Do not construct SQL against the selected business table. Normalize identifiers with `Locale.ROOT` and verify table membership in the fixed Schema before returning columns.

- [ ] **Step 6: Run focused tests and commit**

```bash
mvn -Dtest='ReplayDatabaseComparisonPropertiesTest,ReplayBaseDataSourceRegistryTest,ReplayBaseMetadataServiceTest' test
git add src/main/java/com/axonlink/ai/replay/dbcompare src/main/resources/application.yml src/test/java/com/axonlink/ai/replay/dbcompare
git commit -m "feat(replay): add read-only BASE metadata access"
```

---

### Task 3: Registration and Append-Only Audit Persistence

**Files:**
- Create: `src/main/resources/db/daoindex/V62__dii_replay_database_comparison_fields.sql`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonField.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonRegistration.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonQuery.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonPage.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonAuditOperation.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonChangeType.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonAuditEvent.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonAuditDetail.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonAuditQuery.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonAuditPage.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonMigrationTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDaoTest.java`

**Interfaces:**
- DAO reads: `search`, `count`, `findByIdIncludingDeleted`, `findBySchemaAndTable`, `findFields`, `searchAuditEvents`, `countAuditEvents`, `findAuditDetails`.
- DAO writes: `insertRegistration`, `updateRegistration`, `markDeleted`, `replaceFields`, `clearFields`, `insertAuditEvent`, `insertAuditDetails`.
- Audit order is always `operated_at DESC, id DESC`; no DAO update/delete methods exist for audit tables.

- [ ] **Step 1: Write and run a failing V62 migration test**

Assert four tables, permanent `(schema_name, table_name)` unique key, optimistic-lock version, unique comparison order, audit business-key index, event-to-detail order uniqueness, and absence of cascade deletion from registration to audit.

```bash
mvn -Dtest=ReplayDatabaseComparisonMigrationTest test
```

Expected: V62 resource is missing.

- [ ] **Step 2: Implement V62 and fixture loading**

Create exactly:

```text
dii_replay_db_compare_registration
dii_replay_db_compare_field
dii_replay_db_compare_audit_event
dii_replay_db_compare_audit_detail
```

Use `BIGINT` identifiers, `DATETIME(3)`, `LONGTEXT` audit values, `TINYINT(1)` logical deletion, and semantic foreign keys without physical cascade constraints. Add V62 to `ReplayIssueTestFixtures` migration loading.

- [ ] **Step 3: Write failing DAO tests**

Cover active-list pagination, field preview/count, reviser filters, permanent uniqueness after logical delete, optimistic update returning zero for stale version, field replacement order, clearing current fields on delete, finding a deleted row by business key, audit retrieval after deletion, global audit filters, and stable newest-first event order when timestamps tie.

- [ ] **Step 4: Implement DTOs and fixed SQL DAO**

Use allow-listed query clauses only. Required page records:

```java
public record ReplayDatabaseComparisonPage(
        List<ReplayDatabaseComparisonRegistration> items,
        int page, int size, long total) {}

public record ReplayDatabaseComparisonAuditPage(
        List<ReplayDatabaseComparisonAuditEvent> items,
        int page, int size, long total) {}
```

Use generated keys for registration and audit event inserts. Batch-insert audit details in `detail_order` order.

- [ ] **Step 5: Run focused tests and commit**

```bash
mvn -Dtest='ReplayDatabaseComparisonMigrationTest,ReplayDatabaseComparisonDaoTest' test
git add src/main/resources/db/daoindex/V62__dii_replay_database_comparison_fields.sql src/main/java/com/axonlink/ai/replay/dbcompare src/test/java/com/axonlink/ai/replay
git commit -m "feat(replay): persist comparison fields and audit events"
```

---

### Task 4: Field-Level Difference Engine

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonState.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonAuditDetailDraft.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditDiff.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditDiffTest.java`

**Interfaces:**
- Produces `ReplayDatabaseComparisonAuditDiff.compare(ReplayDatabaseComparisonState before, ReplayDatabaseComparisonState after, ReplayDatabaseComparisonAuditOperation operation): List<ReplayDatabaseComparisonAuditDetailDraft>`.
- `before` is `null` only for `CREATE`; `after` is `null` only for `DELETE`.

- [ ] **Step 1: Write failing table-attribute difference tests**

Assert `tableComment`, `domainName`, `groupOwner`, `registeredDate`, and `deleted` use stable field codes and Chinese labels; unchanged normalized values produce no detail.

```java
assertEquals("domainName", details.get(0).fieldCode());
assertEquals(ReplayDatabaseComparisonChangeType.MODIFY, details.get(0).changeType());
assertEquals("存款组", details.get(0).beforeValue());
assertEquals("公共组", details.get(0).afterValue());
```

- [ ] **Step 2: Write failing comparison-field difference tests**

Cover field `ADD`, `DELETE`, comment `MODIFY`, order `REORDER`, create producing one `ADD` per selected field, delete producing one `DELETE` per current field, and deterministic detail order: table attributes first, then field order/name.

- [ ] **Step 3: Run tests and verify RED**

```bash
mvn -Dtest=ReplayDatabaseComparisonAuditDiffTest test
```

Expected: missing-type compilation failures.

- [ ] **Step 4: Implement pure difference calculation**

Represent fields by normalized `columnName`. Emit `comparisonFields.<columnName>` codes. For `REORDER`, store decimal order strings in before/after values. The calculator must have no database, clock, JSON, or security dependencies.

- [ ] **Step 5: Run focused tests and commit**

```bash
mvn -Dtest=ReplayDatabaseComparisonAuditDiffTest test
git add src/main/java/com/axonlink/ai/replay/dbcompare/dto src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditDiff.java src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditDiffTest.java
git commit -m "feat(replay): calculate field-level registration audits"
```

---

### Task 5: Transactional CRUD, Delete, and Re-registration

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonSaveRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonDeleteRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonReregisterRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonOptions.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionConflictException.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`

**Interfaces:**
- Consumes Task 2 metadata service, Task 3 DAO, Task 4 audit diff, existing `ReplayIssueOperator`, and `SysUserDao`.
- Produces `create`, `update`, `delete`, `reregister`, `search`, `detail`, `auditEvents`, `auditDetails`, `searchAudits`, and `options`.

- [ ] **Step 1: Write failing all-user maintenance tests**

Prove user B can edit and delete a record created by user A; group owner membership is irrelevant; blank operator is rejected as unauthenticated; trusted metadata replaces client comments; selected group owner resolves through `SysUserDao`; successful changes set reviser to the current operator.

- [ ] **Step 2: Write failing atomic audit tests**

Assert one save creates one event plus all detail rows, no-op save changes neither version nor reviser and writes no audit, stale version writes nothing, delete writes status and per-field delete details before clearing current fields, and any audit-detail insert failure rolls back registration/fields/event.

- [ ] **Step 3: Write failing re-registration tests**

Prove a deleted business key cannot use normal create, `reregister` requires the deleted row version, ignores historical fields, validates only newly selected current BASE fields, reuses the original registration ID, resets `deleted=false`, writes fresh fields, sets the new reviser/date, and appends `REREGISTER` with field `ADD` details.

- [ ] **Step 4: Run service tests and verify RED**

```bash
mvn -Dtest=ReplayDatabaseComparisonServiceTest test
```

Expected: service types and behavior are missing.

- [ ] **Step 5: Implement validation before result transaction**

Normalize table/field identifiers, reject duplicate or blank fields, load trusted BASE metadata, and resolve the group owner before opening the result-database transaction. Use `TransactionTemplate(new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()))` for each write.

- [ ] **Step 6: Implement one atomic write pipeline**

Inside the transaction re-read current state, check version, compute target state and audit details, return unchanged data when details are empty, otherwise update registration, replace/clear fields, insert one event, and insert all details. Required signatures:

```java
ReplayDatabaseComparisonRegistration update(
        long id, ReplayDatabaseComparisonSaveRequest request, ReplayIssueOperator operator);

ReplayDatabaseComparisonRegistration reregister(
        long id, ReplayDatabaseComparisonReregisterRequest request, ReplayIssueOperator operator);
```

- [ ] **Step 7: Run focused tests and commit**

```bash
mvn -Dtest='ReplayDatabaseComparisonAuditDiffTest,ReplayDatabaseComparisonServiceTest' test
git add src/main/java/com/axonlink/ai/replay/dbcompare src/test/java/com/axonlink/ai/replay/dbcompare
git commit -m "feat(replay): manage comparison registrations transactionally"
```

---

### Task 6: Atomic Excel Initialization Import

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonImportError.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonImportResult.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonExcelParser.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonImportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonExcelParserTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonImportServiceTest.java`

**Interfaces:**
- `ReplayDatabaseComparisonExcelParser.parse(InputStream): ParsedImport` groups rows by normalized table name with row provenance.
- `ReplayDatabaseComparisonImportService.importFile(InputStream, ReplayIssueOperator): ReplayDatabaseComparisonImportResult`.
- Import writes one `IMPORT` audit event per changed table with field-level details.

- [ ] **Step 1: Write failing in-memory workbook tests**

Cover arbitrary column order, required headers `领域/表英文名/字段英文名/负责人`, blank rows, same-table aggregation, duplicate-field removal, ignored sequence/group/date columns, conflicting table-level values, and one-based Sheet/row/table/field errors.

- [ ] **Step 2: Run parser tests and verify RED**

```bash
mvn -Dtest=ReplayDatabaseComparisonExcelParserTest test
```

- [ ] **Step 3: Implement parser with `DataFormatter`**

Search the first 20 rows for the header. Preserve historical `负责人` as the selected group-owner display value; do not trust Excel table/field comments and do not write Excel registration dates.

- [ ] **Step 4: Write failing import orchestration tests**

Prove all user and BASE validations precede writes, all errors are collected, one invalid row writes zero tables, existing active rows merge fields, deleted rows are not silently reactivated, unchanged rows create no audit/version change, changed rows create one `IMPORT` event, and a final-table failure rolls back every table/event/detail.

- [ ] **Step 5: Implement validate-then-write import**

Resolve every historical owner to exactly one active `sys_user`. Validate every table and field without holding result-database transactions, then execute one result-database transaction for the complete batch. Reject deleted-table collisions with an explicit error requiring interactive re-registration.

- [ ] **Step 6: Run focused tests and commit**

```bash
mvn -Dtest='ReplayDatabaseComparisonExcelParserTest,ReplayDatabaseComparisonImportServiceTest' test
git add src/main/java/com/axonlink/ai/replay/dbcompare src/test/java/com/axonlink/ai/replay/dbcompare
git commit -m "feat(replay): import comparison fields with audits"
```

---

### Task 7: Backend HTTP Contracts and Global Audit Query

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/controller/ReplayDatabaseComparisonController.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonSearchRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonHeaderFilterRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonHeaderFilterResult.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDatabaseComparisonAuditSearchRequest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/controller/ReplayDatabaseComparisonControllerTest.java`

**Interfaces:**
- Produces every endpoint under `/api/ai/parallel-replay/database-comparison-fields` from the API spec.
- Uses existing `UserPrincipalResolver` to create `ReplayIssueOperator`.
- CRUD/delete/reregister require only authenticated operator; import additionally checks configured admins and `X-DII-Trigger-Token`.

- [ ] **Step 1: Write failing MockMvc tests**

Cover list and header-filter arrays, `reviser` filter allow-list, metadata keyword limits, create/update/delete/reregister by unrelated logged-in users, unauthenticated 401, version 409, missing metadata 422, BASE unavailable 503, row audit, global audit including deleted tables, detail retrieval, newest-first ordering, wrong import token before file parsing, non-admin import 403, and sanitized unexpected errors.

- [ ] **Step 2: Run controller tests and verify RED**

```bash
mvn -Dtest=ReplayDatabaseComparisonControllerTest test
```

- [ ] **Step 3: Implement controller and error mapping**

Expose:

```text
POST   /search
POST   /header-filter-options
GET    /{id}
GET    /{id}/audits
POST   /audits/search
GET    /audits/{auditEventId}/details
GET    /options
GET    /metadata/tables
GET    /metadata/tables/{tableName}/columns
POST   /
PUT    /{id}
DELETE /{id}
POST   /{id}/reregister
POST   /import
```

Use existing `R<T>` wrappers and the API spec error codes. Format audit timestamps as `yyyy-MM-dd HH:mm:ss` in DTO serialization while retaining millisecond database precision.

- [ ] **Step 4: Run backend replay regression tests and commit**

```bash
mvn -Dtest='ReplayDatabaseComparison*Test,ReplayIssueControllerTest,ReplayIssueImportServiceTest,ReplayWeeklyReportServiceTest' test
git add src/main/java/com/axonlink/ai/replay/dbcompare src/test/java/com/axonlink/ai/replay/dbcompare
git commit -m "feat(replay): expose comparison field and audit APIs"
```

---

### Task 8: Frontend API Integration and Re-registration

**Files:**
- Create: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/api/replayDatabaseComparison.js`
- Create: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/api/replayDatabaseComparison.spec.js`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonPage.spec.js`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonEditor.vue`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonEditor.spec.js`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonAuditDialog.vue`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields/src/components/replay/ReplayDatabaseComparisonAuditDialog.spec.js`

**Interfaces:**
- API exports `searchRegistrations`, `loadRegistration`, `loadHeaderFilterOptions`, `searchBaseTables`, `loadBaseColumns`, `createRegistration`, `updateRegistration`, `deleteRegistration`, `reregisterRegistration`, `searchAudits`, `loadRegistrationAudits`, `loadAuditDetails`, `loadOptions`, and `importInitialExcel`.
- Page stores server `reviserEmpNo/reviserName`; editor never submits a reviser.

- [ ] **Step 1: Write failing API tests**

Assert exact paths, JSON request bodies, encoded table names, delete body, reregister body, audit query paging, audit detail path, and multipart import token. Use direct `fetch` for multipart so the browser sets the boundary; use shared `request` for JSON.

```javascript
await searchAudits({ page: 0, size: 50, tableKeyword: 'kdpa' })
expect(fetch.mock.calls[0][0]).toBe('/api/ai/parallel-replay/database-comparison-fields/audits/search')
```

- [ ] **Step 2: Implement API helpers and verify GREEN**

```bash
npm test -- src/api/replayDatabaseComparison.spec.js
```

- [ ] **Step 3: Write failing page integration tests**

Mock API functions and cover initial loading, real server pagination/filter payloads, detail loading, save refresh without losing filters, any logged-in user editing/deleting, 409 refresh prompt, deleted row removal, global audit query, row audit prefilter, lazy audit details, and audit data surviving a deleted registration.

- [ ] **Step 4: Write failing re-registration editor tests**

When table search returns `DELETED`, assert mode becomes `重新登记`, old fields are not selected automatically, valid historical fields appear only in an optional suggestion panel, missing historical fields cannot be selected, and save calls `reregisterRegistration` with the deleted record ID/version plus newly selected fields.

- [ ] **Step 5: Replace Mock persistence with API state**

Keep the existing 200-row Mock generator only behind `import.meta.env.DEV && import.meta.env.VITE_REPLAY_DB_COMPARE_MOCK === 'true'`; default development and production paths use backend APIs. Preserve accepted fixed header, pagination, ellipsis, hover title/copy, field expansion, missing-field highlighting, and non-dismissible dialogs.

- [ ] **Step 6: Integrate audit dialog and error states**

Load event summaries on search and details only when an event expands. Preserve current dialog/filter state on network error. Display backend 401/409/422/503 messages, and never infer permission from reviser or group owner.

- [ ] **Step 7: Run focused tests and commit**

```bash
npm test -- src/api/replayDatabaseComparison.spec.js src/components/replay/ReplayDatabaseComparisonPage.spec.js src/components/replay/ReplayDatabaseComparisonEditor.spec.js src/components/replay/ReplayDatabaseComparisonAuditDialog.spec.js
git add src/api/replayDatabaseComparison.js src/api/replayDatabaseComparison.spec.js src/components/replay/ReplayDatabaseComparisonPage.vue src/components/replay/ReplayDatabaseComparisonPage.spec.js src/components/replay/ReplayDatabaseComparisonEditor.vue src/components/replay/ReplayDatabaseComparisonEditor.spec.js src/components/replay/ReplayDatabaseComparisonAuditDialog.vue src/components/replay/ReplayDatabaseComparisonAuditDialog.spec.js
git commit -m "feat(replay): connect comparison registration workflows"
```

---

### Task 9: Cross-Repository Verification and Browser Acceptance

**Files:**
- Modify only files required by failures directly caused by Tasks 1–8.

**Interfaces:**
- Verifies the complete production flow without changing API contracts.

- [ ] **Step 1: Run full backend tests**

```bash
cd /Users/java/axon-link-server/.worktrees/replay-db-comparison-fields
export JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```

Expected: zero failures and zero errors.

- [ ] **Step 2: Run full frontend tests and production build**

```bash
cd /Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields
npm test
npm run build -- --outDir /tmp/axon-link-replay-db-comparison-build
```

Expected: all Vitest tests pass and Vite exits 0.

- [ ] **Step 3: Verify migration compatibility**

Apply V62 through `ReplayIssueTestFixtures` and run existing replay migration/DAO tests. Confirm the migration only creates the four new tables/indexes and does not alter replay issue/report tables.

- [ ] **Step 4: Run browser acceptance with Mock and API modes**

At desktop and 760px widths verify: `修订人` label, unrestricted edit/delete buttons, compact cell containment/copy, add/edit/delete, deleted-table re-registration without automatic old-field restore, row audit, global deleted-table audit query, newest-first events, second-precision timestamps, detail expansion/copy, and complete action-column horizontal scrolling. Save screenshots under `output/playwright/`.

- [ ] **Step 5: Check both patches**

```bash
cd /Users/java/axon-link-server/.worktrees/replay-db-comparison-fields && git diff --check && git status --short
cd /Users/java/axon-link-frontend/.worktrees/replay-db-comparison-fields && git diff --check && git status --short
```

Expected: no whitespace errors and only planned files changed.

- [ ] **Step 6: Commit verification-only fixes when present**

If Step 1–5 required source changes, commit only those files in their owning repository with `fix(replay): refine comparison field production flow`. If no source changes were required, do not create an empty commit.
