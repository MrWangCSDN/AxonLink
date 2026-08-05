# Replay Issue Display Editing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make replay issue manual fields read-only red content by default, edit them together from an edit action, and expose a dedicated tracking action in the table.

**Architecture:** Keep the existing PATCH transaction and tracking API. Replace inline controls with a row edit modal, keep the tracking drawer as a separate read-only view, and make the table column definition the single source for order and labels.

**Tech Stack:** Vue 3, Vitest, Vue Test Utils, existing replay issue API helpers.

## Global Constraints

- Manual fields are displayed as red text when not editing.
- One edit save submits issue status, issue type, initial analysis, final solution, and cooperation person together.
- Analysis and final solution are limited to 500 characters.
- The operation column follows transaction owner and contains edit and tracking buttons.
- Remark moves immediately before serial number.
- Defect repair date remains display-only; legacy data repair date is not displayed.

### Task 1: Rework Table Columns and Read-Only Rendering

**Files:**
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-issue-tracking/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-issue-tracking/src/components/replay/ReplayIssuePage.spec.js`

- [x] Added assertions for the operation column position, remark/serial order, red manual values, and absence of inline editors in the default row.
- [x] Updated the column metadata and row renderer to show manual fields as read-only red content.
- [x] Focused Vue test passes.

### Task 2: Add Combined Edit Modal

**Files:**
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-issue-tracking/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-issue-tracking/src/components/replay/ReplayIssuePage.spec.js`

- [x] Added tests for opening edit, loading all six fields, 500-character limits, and one PATCH payload on save.
- [x] Implemented modal state and controls for the three manual statuses, five issue types, three text areas, and collaborator fuzzy search.
- [x] Cancel is side-effect free and the row refreshes after a successful save.
- [x] Full frontend suite passes: 26 tests.

### Task 3: Add Tracking Action and Package

**Files:**
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-issue-tracking/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/.worktrees/replay-issue-tracking/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-server/.worktrees/replay-issue-tracking/src/main/resources/static/` (generated output)

- [x] Operation-column tracking action and history drawer rendering are covered by the existing tracking test.
- [x] The newest-first tracking drawer remains separate from pagination and table scrolling.
- [x] Frontend tests/build, backend replay tests, and Maven package all pass; generated output is synchronized to backend static resources.
- [x] Frontend commit `7165d9b` and backend remark/API commit `62f6d05` are complete; static integration remains in the packaging commit.
