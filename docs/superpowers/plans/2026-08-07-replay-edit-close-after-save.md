# Replay Edit Close After Save Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the replay issue edit modal immediately after a successful save, even when the changed status removes the issue from the refreshed list.

**Architecture:** Keep the existing update API and refresh calls. Treat a successful `updateReplayIssue` response as the modal-close boundary, then refresh the current list and metadata without requiring the saved issue to remain visible under the active filters.

**Tech Stack:** Vue 3, Vitest, Vue Test Utils.

## Global Constraints

- Preserve the edit modal and its draft when `updateReplayIssue` fails.
- Preserve the current page and horizontal scroll position when refreshing after save.
- Do not change backend APIs, status rules, import behavior, or unrelated dirty files.

---

### Task 1: Close the Edit Modal After a Successful Save

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `updateReplayIssue(id, payload)`, `loadList({ preserveOnError: true })`, and `loadMetadata()`.
- Produces: an edit workflow whose modal closes whenever `updateReplayIssue` resolves successfully.

- [x] **Step 1: Replace the stale-row modal test with the failing success-boundary test.**

Configure the refreshed list to be empty after `updateReplayIssue` succeeds, submit a status change, and assert that `[data-testid="edit-modal"]` no longer exists while the list refresh still runs.

- [x] **Step 2: Run the focused test and verify RED.**

Run:

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: the new test fails because `saveEdit` currently keeps the modal open when the saved issue is absent from the refreshed result.

- [x] **Step 3: Implement the minimal save-flow change.**

After `updateReplayIssue` resolves, clear the saving guard and call `closeEdit()` before refreshing. Remove the checks that require refresh success, the same page, and the saved issue to remain visible. Continue refreshing the list and metadata, and restore horizontal scroll after `nextTick()`.

- [x] **Step 4: Run the focused test and verify GREEN.**

Run:

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: all component tests pass.

- [x] **Step 5: Run the complete frontend test and production build.**

Run:

```bash
npm test -- --run
npm run build
```

Expected: all frontend tests pass and the build refreshes the backend static resources successfully.
