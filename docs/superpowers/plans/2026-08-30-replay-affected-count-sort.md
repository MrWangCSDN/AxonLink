# Replay Affected Transaction Count Sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single three-state table-header control that sorts the full replay issue result by “该问题出现在的交易笔数” as a number.

**Architecture:** Keep filtering in `ReplayIssueQuery` and model this one approved ordering as a typed enum passed separately to DAO list/export methods. The DAO appends only a fixed SQL fragment, sorts invalid values last, and retains the existing business order as a stable tie-breaker. Vue owns the default/ascending/descending state and Lucide icon; the Vite Mock applies the same ordering before pagination.

**Tech Stack:** Java 17, Spring MVC, Spring JDBC, H2/MySQL-compatible SQL, JUnit 5, Vue 3, lucide-vue-next, Vite, Vitest

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- The interaction cycle is exactly default → `ASC` → `DESC` → default.
- Default and descending use `ArrowDownWideNarrow`; ascending uses `ArrowUpNarrowWide`; only active sorting is highlighted.
- Sorting applies after all filters and before `limit/offset`; filter state remains unchanged and a sort change resets `offset` to 0.
- Only the dedicated values `ASC`, `DESC`, or absent are accepted; never concatenate a caller-provided SQL field or direction.
- Blank, whitespace, negative, decimal, and nonnumeric values stay after valid nonnegative integer values in both directions.
- Equal numeric values use `i.group_name, i.is_sandbox, i.row_order, i.id` as deterministic tie-breakers.
- The legacy no-sort order remains `i.group_name, i.is_sandbox, i.row_order, i.id`.
- List, Excel export, and Vite Mock use the same order.
- Preserve unrelated uncommitted changes in both repositories; do not commit, merge, push, reset, or clean the worktrees.

---

### Task 1: Add typed server-side numeric ordering

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueAffectedTransactionCountOrder.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `enum ReplayIssueAffectedTransactionCountOrder { ASC, DESC }`.
- Produces: `dao.list(ReplayIssueQuery, ReplayIssueAffectedTransactionCountOrder)` and `dao.listForExport(ReplayIssueQuery, ReplayIssueAffectedTransactionCountOrder)` while preserving existing overloads.
- Consumes at HTTP boundary: optional query parameter `affectedTransactionCountOrder=ASC|DESC`.

- [ ] **Step 1: Write DAO tests for numeric order, invalid-value placement, and default order**

Insert four rows, then set `affected_transaction_count` to `10`, `2`, `''`, and `bad`. Assert:

```java
assertEquals(List.of("T-2", "T-10", "T-BLANK", "T-BAD"),
        dao.list(ALL, ReplayIssueAffectedTransactionCountOrder.ASC).stream()
                .map(row -> row.get("transaction_code")).toList());
assertEquals(List.of("T-10", "T-2", "T-BLANK", "T-BAD"),
        dao.list(ALL, ReplayIssueAffectedTransactionCountOrder.DESC).stream()
                .map(row -> row.get("transaction_code")).toList());
assertEquals(List.of("T-10", "T-2", "T-BLANK", "T-BAD"),
        dao.list(ALL).stream().map(row -> row.get("transaction_code")).toList());
```

Use row order to make the default/tie order deterministic. The last assertion’s expected transaction codes must reflect the inserted `row_order`, not numeric value.

- [ ] **Step 2: Write Controller tests for valid list/export ordering and invalid enum input**

Assert the first list item under `affectedTransactionCountOrder=DESC` has count `10`, the first exported data row has count `10`, and `affectedTransactionCountOrder=SIDEWAYS` returns HTTP 400.

- [ ] **Step 3: Run focused backend tests and verify RED**

Run:

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn \
'-Dtest=ReplayIssueDaoTest#affectedTransactionCountOrderingIsNumericStableAndKeepsInvalidValuesLast,ReplayIssueControllerTest#affectedTransactionCountOrderAppliesToListAndExport+invalidAffectedTransactionCountOrderIsRejected' test
```

Expected: test compilation fails because the enum and DAO overloads do not exist.

- [ ] **Step 4: Add the enum and fixed DAO order fragments**

Create:

```java
package com.axonlink.ai.replay.dto;

public enum ReplayIssueAffectedTransactionCountOrder {
    ASC,
    DESC
}
```

Keep existing methods delegating with `null`. In the private list method, append the default order for `null`; otherwise append a fixed validity expression and a fixed direction selected by enum comparison:

```java
String numericCount = "CASE WHEN TRIM(COALESCE(i.affected_transaction_count,'')) "
        + "REGEXP '^[0-9]+$' THEN CAST(TRIM(i.affected_transaction_count) AS DECIMAL(30,0)) ELSE NULL END";
if (order == null) {
    sql.append(" ORDER BY i.group_name, i.is_sandbox, i.row_order, i.id");
} else {
    sql.append(" ORDER BY CASE WHEN ").append(numericCount).append(" IS NULL THEN 1 ELSE 0 END, ")
            .append(numericCount)
            .append(order == ReplayIssueAffectedTransactionCountOrder.ASC ? " ASC" : " DESC")
            .append(", i.group_name, i.is_sandbox, i.row_order, i.id");
}
```

- [ ] **Step 5: Bind the typed optional parameter on list and export**

Add to both controller methods:

```java
@RequestParam(required = false)
ReplayIssueAffectedTransactionCountOrder affectedTransactionCountOrder
```

Pass it to the new DAO overloads. Spring’s enum conversion rejects every other value with HTTP 400 before DAO execution.

- [ ] **Step 6: Run focused backend tests and verify GREEN**

Run the Step 3 command. Expected: all selected tests pass with zero failures.

---

### Task 2: Add the Vue three-state header control

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: optional request field `affectedTransactionCountOrder: 'ASC' | 'DESC' | undefined`.
- Produces: `data-testid="affected-transaction-count-sort"` with `data-sort="default|asc|desc"`.

- [ ] **Step 1: Write component tests for the full state cycle and request composition**

Locate the “该问题出现在的交易笔数” header and assert the sort button starts with `data-sort="default"`. Click three times and after each `flushPromises()` assert the last list request contains respectively:

```js
{ affectedTransactionCountOrder: 'ASC', offset: 0 }
{ affectedTransactionCountOrder: 'DESC', offset: 0 }
{ affectedTransactionCountOrder: undefined, offset: 0 }
```

Preselect one header filter before clicking and assert it remains in every request. Also assert active states have `is-active`, default does not, and the accessible label describes the next action.

- [ ] **Step 2: Run the focused component test and verify RED**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: the new sort button cannot be found.

- [ ] **Step 3: Implement the icon, state, parameters, and reset behavior**

Import `ArrowDownWideNarrow` and `ArrowUpNarrowWide`. Add:

```js
const affectedTransactionCountOrder = ref('')
const affectedTransactionCountSortState = computed(() =>
  affectedTransactionCountOrder.value ? affectedTransactionCountOrder.value.toLowerCase() : 'default')

async function toggleAffectedTransactionCountSort() {
  affectedTransactionCountOrder.value = affectedTransactionCountOrder.value === ''
    ? 'ASC'
    : affectedTransactionCountOrder.value === 'ASC' ? 'DESC' : ''
  page.value = 0
  await loadList()
}
```

Add `affectedTransactionCountOrder: affectedTransactionCountOrder.value || undefined` to `filterParams()` so list and export share it. Set it to `''` in `resetFilters()`.

Render one button only for `affected_transaction_count`; show `ArrowUpNarrowWide` for `ASC`, otherwise `ArrowDownWideNarrow`. Keep it outside the filter button and use CSS spacing so the controls cannot overlap.

- [ ] **Step 4: Run the focused component test and verify GREEN**

Run the Step 2 command. Expected: all component tests pass.

---

### Task 3: Align Vite Mock and finish regression verification

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`
- Verify generated output: `src/main/resources/static`
- Update implementation log: `/Users/java/obsidian/log.md`

**Interfaces:**
- Consumes: Mock query `affectedTransactionCountOrder`.
- Produces: numeric full-result sorting before Mock pagination.

- [ ] **Step 1: Write a Mock regression test**

Add varied values to Mock rows, including at least `1`, `2`, `10`, `''`, and `bad`. Assert descending starts `10, 2, 1`, ascending starts `1, 2, 10`, invalid values remain at the end, and the original input order is unchanged when the parameter is absent.

- [ ] **Step 2: Run the Mock test and verify RED**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run mock/daoIndexMockServer.spec.js
```

Expected: Mock output remains in original order.

- [ ] **Step 3: Implement Mock sorting before pagination**

Add a pure helper that copies the filtered array, recognizes only `/^\d+$/`, sorts valid `Number` values in the requested direction, keeps invalid values last, and uses original Mock `id` order as a tie-breaker. Apply it before `rows.slice(offset, offset + limit)`.

- [ ] **Step 4: Run focused Mock and component tests**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run mock/daoIndexMockServer.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: zero failures.

- [ ] **Step 5: Run full verification and rebuild backend static assets**

Run:

```bash
cd /Users/java/axon-link-frontend && npm test && npm run build
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn test
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn -DskipTests compile
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/axon-link-server diff --check
```

Expected: frontend tests, production build, backend tests, compile, and both diff checks succeed.

- [ ] **Step 6: Verify the local Mock interaction**

On `http://127.0.0.1:5173/#replay-issues`, confirm the icon is separated from filter controls, cycles default/ASC/DESC/default, resets to page one, preserves filters, and changes values numerically across pages.

- [ ] **Step 7: Append the implementation result to the Obsidian log**

Append one `[IMPL]` line containing the modified layers and the exact fresh test totals. Do not change the already approved design unless implementation evidence requires a design correction.
