# Replay Edit Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make “打开” selectable in replay issue editing and add “合理差异” immediately before “其他问题” everywhere issue types are exposed or validated.

**Architecture:** Keep the existing fixed option lists and lifecycle enum. Synchronize frontend options, mock options, DAO filter options, and edit-service validation, with focused frontend and backend regression tests.

**Tech Stack:** Vue 3, Vitest, Java 17, Spring JDBC, JUnit 5.

## Global Constraints

- User-selectable statuses remain exactly “打开、延后修复、修复待验证”.
- “合理差异” appears immediately before “其他问题”.
- Import lifecycle transitions and historical data remain unchanged.

---

### Task 1: Add failing option tests

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

- [ ] Assert the edit status dropdown contains “打开、延后修复、修复待验证”.
- [ ] Assert “合理差异” is directly before “其他问题” in page and DAO filter options.
- [ ] Assert the edit service accepts “打开” and “合理差异”.
- [ ] Run focused tests and confirm failures are caused by missing options.

### Task 2: Synchronize production and mock options

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`

- [ ] Add “打开” to the frontend manual status list.
- [ ] Add “合理差异” before “其他问题” in all fixed issue-type lists.
- [ ] Run focused frontend and backend tests until green.

### Task 3: Verify local behavior

**Files:**
- Build output: `src/main/resources/static`

- [ ] Run the full replay page test and focused backend replay tests.
- [ ] Build the frontend into backend static resources.
- [ ] Verify the running mock page exposes the new edit and query options.
