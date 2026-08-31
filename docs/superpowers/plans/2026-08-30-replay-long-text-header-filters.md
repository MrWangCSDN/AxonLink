# Replay Long-Text Header Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Excel-style header filters for transaction name, field name, issue description, and issue key, with a resizable long-text filter panel whose size persists until page refresh.

**Architecture:** Extend the existing `ReplayIssueQuery` contract so list, count, candidate lookup, and export share the same exact-value filters. Reuse the existing Vue header-filter state and API serializer, then add a pointer-driven resize handle and viewport clamping without a new dependency.

**Tech Stack:** Java 17, Spring MVC, JDBC, JUnit 5, Vue 3, Vitest, Vue Test Utils.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Candidate search uses `%keyword%`; checked values use exact matching.
- Multiple values in one field are OR; filters across fields are AND.
- List, count, candidate scope, and Excel export use the same query object.
- Empty database values remain available as the candidate `空`.
- The panel defaults to `280×360px`, never becomes smaller than `280×260px`, and stays within a 12px viewport margin.
- Panel size persists only for the current component instance; page refresh restores defaults.
- Long candidates stay on one line and scroll horizontally.
- Do not add a UI dependency or alter table column widths.

---

### Task 1: Backend query and DAO contract

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces query accessors `transactionNames()`, `fieldNames()`, `issueDescriptions()`, and `issueKeys()` of type `List<String>`.
- Produces candidate fields `transactionName`, `fieldName`, `issueDescription`, and `issueKey`.

- [ ] **Step 1: Write failing DAO tests**

Add rows with distinct and blank long-text values, assert fuzzy candidate lookup for all four fields, and construct a query containing two transaction names plus one value in each remaining field. Assert list/count only return rows satisfying same-field OR and cross-field AND.

- [ ] **Step 2: Run the DAO tests and verify RED**

Run:

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -Dtest=ReplayIssueDaoTest test
```

Expected: compilation or assertion failure because the four query fields and candidate mappings do not exist.

- [ ] **Step 3: Implement the minimal DAO support**

Append the four lists to `ReplayIssueQuery`, preserve all existing convenience constructors with empty defaults, map candidate expressions to `i.transaction_name`, `i.field_name`, `i.issue_description`, and `i.issue_key`, and call `appendIn` for each expression in `appendFilters`.

- [ ] **Step 4: Run the DAO tests and verify GREEN**

Run the command from Step 2 and require zero failures.

### Task 2: Controller request binding and export parity

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes the four query accessors from Task 1.
- Accepts repeated query parameters `transactionNames`, `fieldNames`, `issueDescriptions`, and `issueKeys` on list, candidate, and export endpoints.

- [ ] **Step 1: Write failing controller tests**

Capture the query passed to the DAO/export path and assert all four repeated parameters are preserved. Verify candidate lookup excludes its active field while retaining the other three filters.

- [ ] **Step 2: Run the controller tests and verify RED**

Run:

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -Dtest=ReplayIssueControllerTest test
```

Expected: request parameters are ignored or query accessors remain empty.

- [ ] **Step 3: Implement request binding**

Add four optional `List<String>` request parameters to list, header candidate, and export endpoints, normalize them with `safe`, and pass them to the canonical query constructor in the same order.

- [ ] **Step 4: Run controller and DAO tests and verify GREEN**

Run:

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -Dtest=ReplayIssueControllerTest,ReplayIssueDaoTest test
```

### Task 3: Frontend long-text filters

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Sends `transactionNames`, `fieldNames`, `issueDescriptions`, and `issueKeys` through the existing array query serializer.

- [ ] **Step 1: Write failing filter tests**

Assert the four headers show filter buttons, candidate requests use the four singular field names, selections appear in later list requests under the four plural parameter names, and clearing a filter removes only that field.

- [ ] **Step 2: Run the focused frontend tests and verify RED**

Run:

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: one or more of the four headers have no filter button or send no plural parameter.

- [ ] **Step 3: Add the four config mappings**

Add `transaction_name`, `field_name`, `issue_description`, and `issue_key` to `headerFilterConfig`; reuse `filterParams` and `headerFilterParams` unchanged.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2 and require zero failures.

### Task 4: Resizable filter panel

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces a resize handle with `data-testid="header-filter-resize-handle"`.
- Keeps `{ width, height }` reactive state for the component lifetime.

- [ ] **Step 1: Write failing resize and layout tests**

Assert long labels use the no-wrap class, the options viewport scrolls both axes, pointer movement updates panel width/height, size clamps to minimum and viewport maximum, closing/reopening and switching fields preserve size, and unmount removes window listeners.

- [ ] **Step 2: Run the focused frontend tests and verify RED**

Run the command from Task 3 Step 2 and expect the resize handle or style assertions to fail.

- [ ] **Step 3: Implement pointer resizing and viewport positioning**

Add default/minimum/margin constants, reactive size state, pointer start/move/end cleanup, explicit width/height in `headerFilterPanelStyle`, current-size positioning, the resize handle, and CSS for no-wrap options plus horizontal/vertical overflow.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Task 3 Step 2 and require zero failures.

### Task 5: Regression verification

**Files:**
- Verify only; do not change unrelated files.

- [ ] **Step 1: Run backend focused tests**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home /opt/homebrew/bin/mvn -Dtest=ReplayIssueControllerTest,ReplayIssueDaoTest test
```

- [ ] **Step 2: Run the full frontend suite**

```bash
cd /Users/java/axon-link-frontend && npm test -- --run
```

- [ ] **Step 3: Build the frontend**

```bash
cd /Users/java/axon-link-frontend && npm run build
```

- [ ] **Step 4: Review diffs and requirement coverage**

Confirm only the planned source/test/docs files changed, inspect `git diff --check` in both repositories, and verify every Global Constraint has a test or direct code evidence.
