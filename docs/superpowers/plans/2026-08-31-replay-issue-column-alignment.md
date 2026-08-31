# Replay Issue Column Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `issue_id` as wide as the transaction-code column and keep every issue-domain selector the same width even when a transfer badge is present.

**Architecture:** Keep the existing fixed table layout. Change only the frontend column metadata and issue-domain flex sizing: `issue_id=100px`, `issue_domain=164px`, selector fixed at `112px`, and the existing history badge remains a fixed-width sibling.

**Tech Stack:** Vue 3, CSS, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`.

## Global Constraints

- Do not change APIs, database fields, Excel export columns, filtering, or transfer behavior.
- Rows with zero and nonzero transfer counts must render selectors with the same fixed width.
- Preserve the existing tooltip overflow behavior.
- Do not reset, clean, commit, or overwrite unrelated user changes.

---

### Task 1: Lock column and selector widths

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces `issue_id` and `transaction_code` columns at `100px`.
- Produces every `.replay-issue-domain-cell select` at `112px`, independent of the history badge.

- [ ] Change the component test to assert `issue_id` and transaction-code col styles are equal and contain `100px`.
- [ ] Add a test with one zero-transfer row and one two-transfer row asserting both selectors use the same `112px` fixed-width rule.
- [ ] Run the component test and confirm it fails on the old `80px` and flexible selector.
- [ ] Set the column metadata to `issue_id=100px` and `issue_domain=164px`.
- [ ] Set selector CSS to `flex: 0 0 112px; width: 112px` while retaining height and padding.
- [ ] Re-run the component test and confirm it passes.

### Task 2: Build and visually verify

**Files:**
- Generated: `src/main/resources/static/**`
- Modify: `/Users/java/obsidian/log.md`

- [ ] Run the full frontend test suite.
- [ ] Run `npm run build` and confirm output goes to backend static resources.
- [ ] Open the Mock page and verify rows with 0/1/2/3 transfer counts have vertically aligned selectors and visible badges.
- [ ] Append actual verification evidence to the Obsidian implementation log.
