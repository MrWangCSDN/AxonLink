# Replay Issue Domain Header Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Excel-style header filter for the current issue domain and keep list, counts, candidates, export, and Mock behavior consistent.

**Architecture:** Extend the existing typed `ReplayIssueQuery` with `issueDomains` and reuse the existing header-filter candidate/count endpoints. The DAO uses one fallback expression, `COALESCE(NULLIF(TRIM(i.issue_domain), ''), i.group_name)`, for candidates and filtering so legacy blank domains match what the list displays. The frontend and Mock add one field mapping and pass the selected values through the existing filter state.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, JUnit 5, Vue 3, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section “问题所属领域与转组控制（2026-08-31）”, with matching data-model and API documents.

## Global Constraints

- Do not add a database column or table.
- Candidate values and query filtering must both fall back from blank `issue_domain` to `group_name`.
- The same `issueDomains` values must affect list count, page rows, candidate composition, and Excel export.
- Preserve all existing header filters and current issue-domain editing behavior.
- Work directly in the user-authorized existing workspace; do not reset, clean, commit, or overwrite unrelated changes.

---

### Task 1: Add the backend query and candidate contract

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces `ReplayIssueQuery.issueDomains(): List<String>`.
- Accepts repeated query parameter `issueDomains` on list, export, and both header-filter endpoints.
- Accepts `field=issueDomain` on both header-filter endpoints.

- [ ] Add DAO tests showing `field=issueDomain` returns counted six-domain candidates, uses `group_name` for a blank stored domain, and filters rows by multiple selected domains.
- [ ] Add a controller test showing `issueDomains=存款组&issueDomains=平台组` affects list/export and is accepted by the counted candidate endpoint.
- [ ] Run the focused tests and confirm they fail because the query field and header-filter expression are missing.
- [ ] Append `issueDomains` to the query record and every constructor/call site.
- [ ] Add `issueDomain -> COALESCE(NULLIF(TRIM(i.issue_domain), ''), i.group_name)` to the candidate expression whitelist.
- [ ] Add the multi-value predicate through the existing `appendTextListFilter` path using the same fallback expression.
- [ ] Re-run the focused backend tests and confirm they pass.

### Task 2: Add the page and Mock header filter

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`

**Interfaces:**
- Maps table key `issue_domain` to candidate field `issueDomain` and request parameter `issueDomains`.
- Mock maps `issueDomain` to `issue_domain` and applies `issueDomains` before pagination and candidate counts.

- [ ] Add component tests asserting the “问题所属领域” header has a filter button and selected values are sent as `issueDomains` to list/export/candidate requests.
- [ ] Add Mock tests for candidate counts and multi-domain filtering.
- [ ] Run the focused frontend tests and confirm the new assertions fail because the mapping is absent.
- [ ] Add `issue_domain: ['issueDomain', 'issueDomains']` to the existing `headerFilterConfig`.
- [ ] Add the Mock field/parameter maps and `inFilter('issueDomains', 'issue_domain')`, using `group_name` when the Mock domain is blank.
- [ ] Re-run the focused frontend tests and confirm they pass.

### Task 3: Verify production and Mock behavior

**Files:**
- Modify: `/Users/java/obsidian/log.md`
- Generated: `src/main/resources/static/**`

**Interfaces:**
- Browser displays the existing Excel-style filter panel from the “问题所属领域” header.

- [ ] Run the backend focused suite for DAO/controller query, candidate, count, and export behavior.
- [ ] Run `npm test -- --run` in `/Users/java/axon-link-frontend`.
- [ ] Run `npm run build` and confirm output is written to backend `src/main/resources/static`.
- [ ] Run `mvn -DskipTests package` in `/Users/java/axon-link-server`.
- [ ] Open the local Mock page, choose two issue domains, and verify candidate counts, filtered rows, reset behavior, and coexistence with another header filter.
- [ ] Append actual verification evidence to `/Users/java/obsidian/log.md`.
