# Replay Database Audit Query Condition Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw condition JSON and separate compare-limit audit rows with one readable “查询条件” row containing the complete `WHERE + ORDER BY + LIMIT` before/after text, while preserving readable access to legacy audit records.

**Architecture:** Add one backend formatter as the single source of truth for audit query-condition text. New audit events compare and persist one `queryCondition` detail built from the normalized condition tree, saved ordered primary-key snapshot, and compare limit; the service read path adapts legacy `whereCondition` / `compareLimit` rows without rewriting history. The existing Vue audit dialog continues rendering backend values unchanged.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Maven, Vue 3/Vitest for existing UI regression coverage.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-系统设计.md`

## Global Constraints

- A scope change produces exactly one detail with `fieldCode=queryCondition` and `fieldLabel=查询条件`.
- Text line order is `where ...`, `order by ...`, `limit ...`; no configured scope is `全表`.
- New audit details never expose condition-tree JSON and never persist separate `whereCondition` or `compareLimit` rows.
- Legacy details are transformed only at read time; database audit rows remain append-only.
- Missing historical ordering information must not be fabricated from current BASE metadata.
- Do not commit changes unless the user explicitly requests a commit.

---

### Task 1: Query Condition Audit Formatter and Unified New Audit Detail

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditQueryConditionFormatter.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareState.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditDiff.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonImportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditDiffTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonAuditQueryConditionFormatterTest.java`

**Interfaces:**
- Produces: `String ReplayDatabaseComparisonAuditQueryConditionFormatter.format(ReplayDbCompareConditionTree condition, List<String> orderingPrimaryKeyNames, Long compareLimit)`.
- Changes: `ReplayDbCompareState` gains immutable `List<String> orderingPrimaryKeyNames` after `compareLimit`.
- Consumes: `ReplayDatabaseComparisonConditionLabeler.label(...)` for the same SQL-style condition text used by list filters.

- [ ] **Step 1: Write formatter tests that describe complete scope text**

Add tests asserting:

```java
assertEquals("全表", formatter.format(null, List.of(), null));
assertEquals("where cst_id = '22'", formatter.format(singleEq("cst_id", "22"), List.of(), null));
assertEquals("where cst_id = '22'\norder by dbcard_cardnum,cst_acnum\nlimit 100",
        formatter.format(singleEq("cst_id", "22"),
                List.of("dbcard_cardnum", "cst_acnum"), 100L));
```

- [ ] **Step 2: Write failing audit-diff tests for a single merged row**

Update the scope-change test so condition, ordered primary keys, and limit change together and assert:

```java
assertEquals(List.of("queryCondition"),
        details.stream().map(ReplayDbCompareAuditDetailDraft::fieldCode).toList());
assertEquals("查询条件", details.get(0).fieldLabel());
assertEquals("全表", details.get(0).beforeValue());
assertEquals("where cst_id = '22'\norder by acct_no,cust_no\nlimit 1000",
        details.get(0).afterValue());
```

Also update create/reregister expectations to contain `queryCondition` once and to exclude `whereCondition` and `compareLimit`.

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Dtest=ReplayDatabaseComparisonAuditQueryConditionFormatterTest,ReplayDatabaseComparisonAuditDiffTest test
```

Expected: compilation/test failures because the formatter does not exist and the audit diff still emits two legacy rows.

- [ ] **Step 4: Implement the formatter and state extension**

Implement `format(...)` with these rules:

```java
List<String> lines = new ArrayList<>();
if (condition != null && !condition.groups().isEmpty()) {
    lines.add("where " + readableCondition(condition));
}
if (compareLimit != null && orderingPrimaryKeyNames != null && !orderingPrimaryKeyNames.isEmpty()) {
    lines.add("order by " + String.join(",", orderingPrimaryKeyNames));
}
if (compareLimit != null) {
    lines.add("limit " + compareLimit);
}
return lines.isEmpty() ? "全表" : String.join("\n", lines);
```

`readableCondition(...)` removes the single redundant outer pair of parentheses using the same one-group/one-condition rule already used by `ReplayDatabaseComparisonService.conditionFilterLabel(...)`.

Extend all `ReplayDbCompareState` construction sites to pass `registration.orderingPrimaryKeyNames()`; compatibility constructors pass `List.of()`.

- [ ] **Step 5: Replace separate scope diffs with one `queryCondition` diff**

Inject the formatter into `ReplayDatabaseComparisonAuditDiff`. For update compare formatted before/after strings once; for create/reregister add one `queryCondition` row. Remove production calls that emit `whereCondition` and `compareLimit` details and delete the JSON/limit helper methods that become unused.

- [ ] **Step 6: Run the focused tests and verify GREEN**

Run the Task 1 command again. Expected: all formatter and audit-diff tests pass.

### Task 2: Legacy Audit Detail Read Adapter

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonLegacyAuditDetailAdapter.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonLegacyAuditDetailAdapterTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`

**Interfaces:**
- Produces: `List<ReplayDbCompareAuditDetail> adapt(List<ReplayDbCompareAuditDetail> details)`.
- Consumes: `ReplayDatabaseComparisonConditionCodec.decode(String)` and the Task 1 formatter with an empty ordering-key list.
- Changes: `ReplayDatabaseComparisonService.auditDetails(long)` returns `legacyAuditDetailAdapter.adapt(dao.findAuditDetails(auditEventId))`.

- [ ] **Step 1: Write failing adapter tests for legacy JSON and limit rows**

Cover these cases with real DTOs:

1. Legacy `whereCondition` plus `compareLimit` becomes one `queryCondition` row at the first legacy row position.
2. Raw JSON becomes readable `where ...`; `1000` becomes `limit 1000`; `未配置` and `全表` become absence of that clause.
3. Unrelated details retain order and values.
4. A new `queryCondition` detail passes through unchanged.
5. Malformed legacy JSON is preserved as readable fallback text instead of failing the entire audit request.

Expected merged value example:

```java
assertEquals("where cst_id = '22'\nlimit 1000", merged.afterValue());
```

- [ ] **Step 2: Run the adapter test and verify RED**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Dtest=ReplayDatabaseComparisonLegacyAuditDetailAdapterTest test
```

Expected: compilation failure because the adapter does not exist.

- [ ] **Step 3: Implement deterministic legacy merging**

Collect legacy rows whose codes are `whereCondition` or `compareLimit`. Build before/after text independently, insert one synthetic detail using the first legacy row's `id`, `auditEventId`, `detailOrder`, `changeType`, and `createdAt`, then omit the consumed rows. Use `fieldCode=queryCondition` and `fieldLabel=查询条件`.

Decode only values that look like JSON. Treat null, blank, `未配置`, and `全表` as absent. If decode fails, return `where ` plus the original nonblank value so the endpoint stays available and no data disappears.

- [ ] **Step 4: Add service integration assertion**

Insert legacy audit-detail rows through the existing test JDBC setup, call `service.auditDetails(eventId)`, and assert the service returns one readable `queryCondition` detail and no legacy field codes.

- [ ] **Step 5: Run adapter and service tests and verify GREEN**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Dtest=ReplayDatabaseComparisonLegacyAuditDetailAdapterTest,ReplayDatabaseComparisonServiceTest test
```

Expected: both test classes pass.

### Task 3: Regression Verification

**Files:**
- Verify: `src/components/replay/ReplayDatabaseComparisonAuditDialog.vue` in `/Users/java/axon-link-frontend`
- Test: existing backend and frontend suites

**Interfaces:**
- Consumes: the unchanged audit detail response fields `fieldLabel`, `beforeValue`, and `afterValue`.
- Produces: no new frontend API or component contract.

- [ ] **Step 1: Verify the Vue dialog preserves multiline backend text**

Confirm the existing audit row renders `displayValue(detail.beforeValue)` and `displayValue(detail.afterValue)` without JSON parsing. Add a component assertion only if existing tests do not cover newline-containing values.

- [ ] **Step 2: Run backend regression tests**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn test
```

Expected: full backend suite passes.

- [ ] **Step 3: Run frontend regression tests and production build**

Run from `/Users/java/axon-link-frontend`:

```bash
npm test -- --run
npm run build
```

Expected: all Vitest tests pass and Vite production build succeeds.

- [ ] **Step 4: Check repository diffs**

Run in both repositories:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended source, tests, plan, and generated backend static assets are changed.
