# Replay Issue ID, Domain, and Sandbox Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `issue_id` fuzzy condition after the sandbox condition and add server-side Excel-style header filters for domain and sandbox without breaking pagination, export, counts, or filter composition.

**Architecture:** Extend the existing `ReplayIssueQuery` contract with `issueId`, `groupNames`, and `sandboxes`, then route the same query object through list, count, export, and header-option SQL. The Vue page reuses the existing header-filter popover and array serialization, while every applied filter remains server-side so pagination totals reflect the combined conditions.

**Tech Stack:** Java 17, Spring Boot MVC, Spring JDBC, JUnit 5, Vue 3, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Preserve all unrelated dirty-worktree changes in `/Users/java/axon-link-server` and `/Users/java/axon-link-frontend`.
- Do not change database schema.
- `issueId` uses trimmed `LIKE %value%` matching.
- Values inside `groupNames` or `sandboxes` use OR semantics; different fields and top conditions use AND semantics.
- Sandbox header values are `是` and `否`, mapped to `is_sandbox = 1` and `is_sandbox = 0`.
- List, count, export, and header-option endpoints use the same filter semantics.
- Top-level query and reset clear header filters and reset pagination to page 1.
- Do not commit, push, merge, reset, or remove user-owned files.

---

### Task 1: Back-end query and SQL contract

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`

**Interfaces:**
- Consumes: existing `ReplayIssueQuery`, `ReplayIssueDao.findPage`, `ReplayIssueDao.count`, and `ReplayIssueDao.headerFilterValues`.
- Produces: accessors `issueId()`, `groupNames()`, and `sandboxes()` with shared SQL behavior.

- [ ] **Step 1: Write failing DAO tests**

Add focused tests that seed multiple issue IDs, groups, and sandbox values, then assert fuzzy `issueId` matching, matching count, multi-value group/sandbox behavior, AND composition, and sorted header option values for `groupName` and `sandbox`.

- [ ] **Step 2: Run DAO tests and verify RED**

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home mvn -Dtest=ReplayIssueDaoTest test`

Expected: compilation or assertion failures because the new query fields and header fields are not implemented.

- [ ] **Step 3: Implement the minimal DTO and SQL changes**

Append `String issueId`, `List<String> groupNames`, and `List<String> sandboxes` to `ReplayIssueQuery`; update every compatibility constructor with empty defaults. In `appendFilters`, add issue ID `LIKE`, group `IN`, and sandbox boolean mapping. Extend `headerFilterValues` with `groupName -> i.group_name` and a `CASE` expression returning `是` or `否` for sandbox.

- [ ] **Step 4: Run DAO tests and verify GREEN**

Run the Task 1 command and confirm all DAO tests pass.

### Task 2: Back-end HTTP parameter propagation

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`

**Interfaces:**
- Consumes: `ReplayIssueQuery` fields produced by Task 1.
- Produces: `issueId`, `groupNames`, and `sandboxes` query parameters on list, header-option, and export endpoints.

- [ ] **Step 1: Write failing controller tests**

Add MockMvc tests proving list requests accept the three parameters, header options return domain and sandbox values under the same filters, and export requests accept the same contract.

- [ ] **Step 2: Run controller tests and verify RED**

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home mvn -Dtest=ReplayIssueControllerTest test`

Expected: failures because controller methods do not bind or forward the new parameters.

- [ ] **Step 3: Implement parameter binding**

Add `@RequestParam(required = false)` parameters to the three controller entry points and pass them in the correct `ReplayIssueQuery` constructor order.

- [ ] **Step 4: Run controller tests and verify GREEN**

Run the Task 2 command and confirm the new controller tests pass; record any pre-existing unrelated batch-tracking failures separately.

### Task 3: Front-end query controls and header filters

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify only if required by RED test: `/Users/java/axon-link-frontend/src/api/replayIssues.js`

**Interfaces:**
- Consumes: back-end query parameters from Task 2 and existing header-filter popover behavior.
- Produces: `filters.issueId`, `headerFilters.domain -> groupNames`, and `headerFilters.is_sandbox -> sandboxes`.

- [ ] **Step 1: Write failing component/API tests**

Assert the issue ID input renders immediately after sandbox, list queries contain trimmed `issueId`, domain and sandbox columns expose filter buttons, applying multiple values sends arrays, and query/reset clears existing header filters and returns to page 1.

- [ ] **Step 2: Run front-end tests and verify RED**

Run: `npm run test -- src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js`

Expected: assertions fail because the new control and two header-filter mappings do not exist.

- [ ] **Step 3: Implement the minimal Vue/API changes**

Insert the issue ID input after sandbox, add `issueId` to reactive filters and request builders, and extend `headerFilterConfig` with `domain: ['groupName', 'groupNames']` and `is_sandbox: ['sandbox', 'sandboxes']`. Reuse the existing array query serializer; change it only if the RED API test proves it does not serialize the new arrays.

- [ ] **Step 4: Run front-end tests and verify GREEN**

Run the Task 3 command and confirm all focused tests pass.

### Task 4: Regression, build, and packaging verification

**Files:**
- Generated: `src/main/resources/static/**`
- Generated: `target/axon-link-server*.jar`

**Interfaces:**
- Consumes: completed server and front-end changes.
- Produces: deployable static assets and Spring Boot JAR containing the updated UI.

- [ ] **Step 1: Run focused back-end regression tests**

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home mvn -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest test`

- [ ] **Step 2: Run front-end regression tests**

Run: `npm run test -- src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js` in `/Users/java/axon-link-frontend`.

- [ ] **Step 3: Build front end into back end**

Run: `npm run build` in `/Users/java/axon-link-frontend` and verify generated assets are written to `/Users/java/axon-link-server/src/main/resources/static`.

- [ ] **Step 4: Package back end with Java 17**

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home mvn -DskipTests package`.

- [ ] **Step 5: Verify artifacts and patch hygiene**

Run: `git diff --check`, inspect the built JAR for `BOOT-INF/classes/static/index.html`, and report focused test results plus any known unrelated baseline failures.
