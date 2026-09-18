# Replay Database Comparison Header Filter Multi-Select Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every registration-list header filter use ASCII parentheses, searchable display components, true exact multi-select, and an explicit empty option.

**Architecture:** Extend current and version query DTOs with exact-value arrays while retaining legacy keyword/range fields. Keep SQL column access allow-listed, represent logical blanks with `__EMPTY__`, and use the same label/search rules in current and version DAOs. The Vue page submits complete selected arrays and keeps same-column OR / cross-column AND semantics in both real and Mock modes.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, Vue 3, Vitest.

**Spec:** `/Users/java/obsidian/.worktrees/replay-filter-multiselect-docs/01 Engineering/axon-link-server/回放数据库比对字段登记-表头筛选多选-系统设计.md`

## Global Constraints

- All displayed grouping and count parentheses use ASCII `(` and `)`.
- `__EMPTY__` is interpreted only for allow-listed nullable filter columns.
- Same-column selections use OR; different columns use AND.
- Existing keyword and date-range request fields remain backward compatible.
- Do not commit application repositories unless the user explicitly requests it.

---

### Task 1: Exact Multi-Value Query Contracts

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareVersionQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareHeaderFilterRequest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/controller/ReplayDatabaseComparisonControllerTest.java`

**Interfaces:**
- Produces: immutable `List<String> tableNames`, `List<String> fieldNames`, and `List<LocalDate> registeredDates` accessors on all applicable request DTOs.
- Preserves: current overloaded constructors and `empty(page, size)` factories for existing Java callers.

- [ ] **Step 1: Write a failing JSON binding test**

Post a `/search` request containing two table names, two field names, and two dates; capture the service argument and assert all arrays survive binding.

- [ ] **Step 2: Run the controller test and verify RED**

Run: `JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" mvn -Dtest=ReplayDatabaseComparisonControllerTest test`

Expected: compilation or assertion failure because the exact arrays do not exist.

- [ ] **Step 3: Add immutable array fields and compatibility constructors**

Normalize null arrays to `List.of()` in compact constructors. Header-filter `query()` must transfer all exact arrays into `ReplayDbCompareQuery`.

- [ ] **Step 4: Re-run the controller test and verify GREEN**

Run the Task 1 command and expect zero failures.

### Task 2: Current Registration DAO Multi-Select and Empty Options

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDaoTest.java`

**Interfaces:**
- Consumes: Task 1 exact arrays.
- Produces: `EMPTY_FILTER_VALUE = "__EMPTY__"`, ASCII labels, searchable name/username/comment parts, exact OR filters, and empty personnel options.

- [ ] **Step 1: Write failing DAO tests for all six columns**

Seed records with two tables, two fields, two dates, multiple domains, populated personnel and blank personnel. Assert labels such as `acct_master(账户主表)`, `acct_no(账号)`, `张三(c-zhangs)`, `赵经理(c-zhaoj)` and count text inputs use ASCII parentheses; blank personnel returns `value=__EMPTY__`, `label=空`.

- [ ] **Step 2: Write failing exact multi-select tests**

Assert two table names OR together, two field names OR together, two dates OR together, `__EMPTY__` matches null/blank personnel, and domain plus personnel combine with AND.

- [ ] **Step 3: Run DAO tests and verify RED**

Run: `JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" mvn -Dtest=ReplayDatabaseComparisonDaoTest test`

- [ ] **Step 4: Implement allow-listed exact and empty predicates**

Add helpers that bind non-empty values with `IN (?,...)` and add a parenthesized blank predicate only when `__EMPTY__` is selected. Add table/field `EXISTS` predicates for exact arrays. Preserve legacy keyword/range predicates and exclude the target column when loading candidates.

- [ ] **Step 5: Re-run DAO tests and verify GREEN**

Run the Task 2 command and expect zero failures.

### Task 3: Metadata Status OR Semantics

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonServiceTest.java`

**Interfaces:**
- Consumes: exact `tableNames` and `metadataStatuses` from Task 1.
- Produces: table-name-column union semantics: ordinary selected table names OR selected live metadata statuses, then AND with all other columns.

- [ ] **Step 1: Write a failing mixed table/status test**

Seed one explicitly selected valid table and one non-selected table matching `MISSING_FIELDS`; assert both appear once with stable paging while unrelated tables do not.

- [ ] **Step 2: Run service tests and verify RED**

Run: `JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" mvn -Dtest=ReplayDatabaseComparisonServiceTest test`

- [ ] **Step 3: Implement the union before stable pagination**

Build candidates using all non-table filters, retain items whose table name is selected or whose live metadata status is selected, de-duplicate by registration ID, sort by existing list order, and paginate once.

- [ ] **Step 4: Re-run service tests and verify GREEN**

Run the Task 3 command and expect zero failures.

### Task 4: Version Snapshot Filter Parity

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonVersionDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonVersionDaoTest.java`

**Interfaces:**
- Consumes: Task 1 exact arrays and `__EMPTY__` sentinel.
- Produces: current/version parity for table, field, domain, reviser, group owner, and registration date labels, search, exact multi-select, and empty values.

- [ ] **Step 1: Write failing snapshot candidate and search tests**

Assert ASCII labels, username/comment substring search, empty personnel option, and exact table/field/date multi-select on immutable snapshots.

- [ ] **Step 2: Run version DAO tests and verify RED**

Run: `JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" mvn -Dtest=ReplayDatabaseComparisonVersionDaoTest test`

- [ ] **Step 3: Mirror the allow-listed current DAO rules**

Use version table/field aliases, snapshot usernames, exact arrays, and empty predicates without accessing BASE metadata.

- [ ] **Step 4: Re-run version DAO tests and verify GREEN**

Run the Task 4 command and expect zero failures.

### Task 5: Vue Header Filter Submission and Mock Parity

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.spec.js`

**Interfaces:**
- Consumes: exact arrays and candidate `{ value, label, count }` from Tasks 1–4.
- Produces: six-column true multi-select requests, ASCII option/count parentheses, display label search, and `空` candidate behavior in Mock mode.

- [ ] **Step 1: Write failing real-mode multi-select tests**

For each header column, select at least two checkboxes and assert `searchRegistrations` receives the complete relevant array: `tableNames`, `fieldNames`, `domains`, `reviserEmpNos`, `groupOwnerEmpNos`, or `registeredDates`.

- [ ] **Step 2: Write failing label/search/empty tests**

Assert option text uses ASCII parentheses, searching a username or Chinese comment returns the option, empty reviser/group owner displays `空`, and selecting it sends `__EMPTY__`.

- [ ] **Step 3: Run page tests and verify RED**

Run: `npx vitest run src/components/replay/ReplayDatabaseComparisonPage.spec.js --exclude '**/.worktrees/**'`

- [ ] **Step 4: Implement stable option formatting and exact criteria arrays**

Store server option labels, render `({{ option.count }})`, use `label` as the Mock/search text, keep every selected checkbox value, and map each column to its exact request array. Preserve metadata-status synthetic options.

- [ ] **Step 5: Re-run page tests and verify GREEN**

Run the Task 5 command and expect zero failures.

### Task 6: Full Regression and Build

**Files:**
- Verify all files modified in Tasks 1–5.

**Interfaces:**
- Produces: release evidence only; no new behavior.

- [ ] **Step 1: Run focused backend suite**

Run: `JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" mvn -Dtest=ReplayDatabaseComparisonDaoTest,ReplayDatabaseComparisonVersionDaoTest,ReplayDatabaseComparisonServiceTest,ReplayDatabaseComparisonControllerTest test`

- [ ] **Step 2: Run all frontend tests**

Run: `npx vitest run --exclude '**/.worktrees/**'`

- [ ] **Step 3: Build the frontend into backend static resources**

Run: `npm run build`

- [ ] **Step 4: Run all backend tests**

Run: `JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' PATH="$JAVA_HOME/bin:$PATH" mvn test`

- [ ] **Step 5: Check formatting and workspace state**

Run `git diff --check` and `git status --short` in both application repositories. Do not stage or commit unrelated existing changes.
