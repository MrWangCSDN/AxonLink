# Replay Header Filter Counts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show Excel-style candidate totals and per-value current issue counts in every replay issue header filter without breaking the existing candidate API.

**Architecture:** Add a dedicated counted-candidate endpoint backed by the same `ReplayIssueQuery` filters as the existing candidate endpoint. Aggregate single-value fields in SQL with `COUNT(DISTINCT i.id)` and preserve the existing split semantics for developer/bank-owner fields, then render structured `{ value, count }` items in the existing resizable Vue panel.

**Tech Stack:** Java 17, Spring MVC, JDBC, JUnit 5, Vue 3, Vitest, Vue Test Utils.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- The original `GET /header-filter-options` path and `List<String>` response remain unchanged.
- The new path is `GET /header-filter-option-counts` and accepts the same query parameters as the original endpoint.
- The caller excludes the active field's own confirmed values; all other base and header filters remain active.
- `candidateCount` is the number of returned candidates from `0` through `500`; `truncated=true` means a 501st candidate exists and the UI displays `500+`.
- Each item count is the number of distinct current issue rows matching the exact normalized value; join multiplicity must not inflate it.
- NULL and blank values normalize to `空`; split developer and bank-owner values retain their existing `、` semantics.
- Candidate search narrows candidates but a draft checkbox change does not trigger a recount.
- Candidate ordering remains `空` first and other values ascending; counts do not affect sort order.
- No database schema change, cache, persistence, or third-party UI dependency is allowed.

---

### Task 1: Count response types and DAO aggregation

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueHeaderFilterOption.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueHeaderFilterOptionResult.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces `ReplayIssueHeaderFilterOption(String value, long count)`.
- Produces `ReplayIssueHeaderFilterOptionResult(int candidateCount, boolean truncated, List<ReplayIssueHeaderFilterOption> items)`.
- Produces `ReplayIssueDao.headerFilterOptionCounts(String field, ReplayIssueQuery query, String keyword)`.

- [ ] **Step 1: Write failing DAO tests for ordinary fields**

Insert repeated transaction names, a blank value, and an occurrence-batch join with duplicate rows. Assert `账户查询` has the correct distinct issue count, `空` is normalized and first, keyword search returns only matching values, and duplicate join rows do not inflate counts.

- [ ] **Step 2: Run the DAO test and verify RED**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -Dtest=ReplayIssueDaoTest#headerFilterOptionCountsNormalizeSearchAndDeduplicateIssues test
```

Expected: test compilation fails because the result records and DAO method do not exist.

- [ ] **Step 3: Implement ordinary-field aggregation**

Create the two records. Extract the existing field-to-expression switch into one private whitelist method shared by both candidate methods. Build a normalized candidate expression that maps null/blank to `空`, aggregate `COUNT(DISTINCT i.id)`, apply `keyword`, sort `空` first, fetch at most 501 rows, and convert the first 500 into the result while setting `truncated` from the extra row.

- [ ] **Step 4: Run the focused DAO test and verify GREEN**

Run the command from Step 2 and require zero failures.

- [ ] **Step 5: Write failing split-field tests**

Create transaction-person rows containing `张三、李四`, duplicated joins, and blank负责人. Assert both people are independent candidates, the same issue counts once per person, keyword narrows the split values, and blank becomes `空`.

- [ ] **Step 6: Run the split test and verify RED**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -Dtest=ReplayIssueDaoTest#headerFilterOptionCountsSplitPeopleWithoutDuplicateIssues test
```

Expected: failure because ordinary SQL grouping treats the combined负责人 string as one value.

- [ ] **Step 7: Implement split-field aggregation**

For `developer` and `bankOwner`, query distinct issue id/raw value pairs under the same filters, split each raw value by `、`, normalize blank to `空`, aggregate each candidate with a set of issue ids, apply keyword after splitting, sort, cap at 500, and set `truncated` when more candidates exist.

- [ ] **Step 8: Run all DAO tests**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -Dtest=ReplayIssueDaoTest test
```

### Task 2: Compatible count endpoint

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes `ReplayIssueDao.headerFilterOptionCounts` from Task 1.
- Produces `GET /api/ai/parallel-replay/issues/header-filter-option-counts` with the documented response.

- [ ] **Step 1: Write a failing controller test**

Call the new endpoint with `field=transactionName`, a keyword, repeated filters from other columns, and fixture rows with repeated values. Assert `candidateCount`, `truncated`, `items[0].value`, and `items[0].count`; also call the old endpoint and assert it remains a string array.

- [ ] **Step 2: Run the controller test and verify RED**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -Dtest=ReplayIssueControllerTest#countedHeaderFilterEndpointPreservesLegacyCandidateResponse test
```

Expected: HTTP 404 for the new path.

- [ ] **Step 3: Implement the endpoint**

Add a controller method that accepts the same scalar and repeated parameters as `headerFilterOptions`, constructs the canonical `ReplayIssueQuery` with `safe(...)`, and returns the DAO result. Keep the existing endpoint method and response type unchanged.

- [ ] **Step 4: Run focused controller and DAO tests**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn '-Dtest=ReplayIssueControllerTest#countedHeaderFilterEndpointPreservesLegacyCandidateResponse,ReplayIssueDaoTest' test
```

### Task 3: Frontend counted-candidate API and panel

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces `getReplayIssueHeaderFilterOptionCounts(params)`.
- Consumes `{ candidateCount, truncated, items: Array<{ value, count }> }`.
- Keeps `headerFilterDraft` and applied header-filter values as `string[]`.

- [ ] **Step 1: Write failing API serialization test**

Assert repeated arrays are serialized against `/header-filter-option-counts` and the helper returns the response data unchanged.

- [ ] **Step 2: Write failing panel behavior tests**

Mock a result containing `空（3）` and a long candidate with count 8. Assert the action row shows `（2）`, each item renders its count, checkboxes still use raw string values, applying sends `string[]`, keyword search calls the count endpoint again, a truncated response displays `（500+）`, and changing a draft checkbox alone does not request another count.

- [ ] **Step 3: Run the frontend tests and verify RED**

```bash
cd /Users/java/axon-link-frontend && npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: missing API export and missing count text assertions fail.

- [ ] **Step 4: Implement the API helper and structured panel state**

Add the API function for the new path. Replace the panel option state with objects, set `candidateCount` and `truncated` from the response, render a dedicated count span, and change select-all/invert to map `option.value` while leaving the draft and request payload as strings.

- [ ] **Step 5: Implement restrained Excel-style count layout**

Place `（candidateCount）` or `（candidateCount+）` next to “反选”. Render each `（count）` in a muted, right-sticky column with an opaque dark background so it remains legible while the long candidate name scrolls horizontally. Preserve the resize handle and existing panel dimensions.

- [ ] **Step 6: Run focused frontend tests and verify GREEN**

Run the command from Step 3 and require zero failures.

### Task 4: Regression verification

**Files:**
- Verify only; do not change unrelated files.

- [ ] **Step 1: Run relevant backend tests**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn '-Dtest=ReplayIssueControllerTest#countedHeaderFilterEndpointPreservesLegacyCandidateResponse+fourLongTextHeaderFiltersSupportCandidateSearchCompositionAndExport,ReplayIssueDaoTest' test
```

- [ ] **Step 2: Compile all backend production code**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -DskipTests compile
```

- [ ] **Step 3: Run the complete frontend suite**

```bash
cd /Users/java/axon-link-frontend && npm test -- --run
```

- [ ] **Step 4: Build frontend production assets**

```bash
cd /Users/java/axon-link-frontend && npm run build
```

- [ ] **Step 5: Review diffs and requirements**

Run `git diff --check` in both repositories. Confirm the legacy endpoint remains unchanged, all count fields match the written API, no schema/configuration file changed, generated static assets have no unintended diff, and unrelated user-owned untracked files remain untouched.
