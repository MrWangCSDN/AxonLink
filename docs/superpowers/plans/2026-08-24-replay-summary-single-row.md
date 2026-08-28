# Replay Summary Single-row Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep all eight replay issue summary cards on one desktop row while preserving the current two-column narrow-screen layout.

**Architecture:** Derive the desktop grid column count from the rendered summary-card count through a CSS custom property on the summary container. The base grid consumes that property, while the existing `max-width: 768px` media rule continues to override the grid to two columns.

**Tech Stack:** Vue 3, scoped CSS, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Desktop renders the eight cards in one row.
- Narrow screens at or below 768px retain two columns.
- Card order, values, tooltips, and query behavior do not change.
- Frontend production output is written to `/Users/java/axon-link-server/src/main/resources/static`.
- Preserve unrelated dirty-worktree changes and do not commit.

---

### Task 1: Summary grid regression

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces: `.replay-summary` CSS variable `--replay-summary-columns` equal to the number of summary cards.
- Consumes: the existing `summaryCards` array and existing mobile media query.

- [x] **Step 1: Write a failing test** that mounts the page, confirms eight cards, and asserts the summary container exposes `--replay-summary-columns: 8`.
- [x] **Step 2: Run** `npm test -- --run src/components/replay/ReplayIssuePage.spec.js` and confirm failure because the variable is absent.
- [x] **Step 3: Bind** `--replay-summary-columns` to `summaryCards.length` and change the desktop grid to `repeat(var(--replay-summary-columns), minmax(0, 1fr))`; leave the narrow-screen two-column rule unchanged.
- [x] **Step 4: Re-run** the targeted test and confirm it passes.

### Task 2: Build and delivery verification

**Files:**
- Generated: `/Users/java/axon-link-server/src/main/resources/static/**`

**Interfaces:**
- Consumes: the updated frontend component.
- Produces: backend-served production assets containing the eight-column summary layout.

- [x] **Step 1: Run** `npm test` and confirm all frontend tests pass.
- [x] **Step 2: Run** `npm run build` and confirm the production bundle is written to the backend static directory.
- [x] **Step 3: Run** `mvn -DskipTests package` with Java 17 and confirm the packaged JAR contains the current frontend entry asset.
- [x] **Step 4: Run** `git diff --check` in both repositories and confirm no whitespace errors.
