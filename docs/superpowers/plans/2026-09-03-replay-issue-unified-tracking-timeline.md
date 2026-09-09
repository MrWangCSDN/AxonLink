# Replay Issue Unified Tracking Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign each replay issue batch as a compact header plus one strictly time-ordered change timeline, while hiding duplicated tracking events and supporting copyable overflow values.

**Architecture:** Keep database history unchanged. Filter irrelevant operations and batch-number changes in the backend response projection, then derive one display-only event array per batch in Vue by merging existing `inheritedEvents` and `manualEvents`. A focused reusable value component owns overflow detection, hover/focus popover state, and clipboard feedback.

**Tech Stack:** Java 17, Spring MVC, Jackson, JUnit 5, Vue 3 Composition API, Vitest, Vue Test Utils, Lucide Vue, CSS.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Preserve all existing database history rows; filtering applies only to the round-tracking response.
- Keep the current round-tracking DTO fields for compatibility.
- Sort displayed events by `operationAt DESC`, then numeric `id DESC`.
- Keep no-change import batches visible as one system event.
- Only overflowed non-empty values expose the interactive copy popover.

---

### Task 1: Filter Backend Tracking Projection

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueTrackingProjection.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueTrackingProjectionTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: persisted `ReplayIssueHistoryEntry` snapshots and operation types.
- Produces: `fieldChanges(...)` without `批次号`, and round groups without `修改问题所属领域` or `修改计划验证日期` events.

- [x] Add a projection test whose snapshots differ only in `batchNo` and assert `fieldChanges(...)` is empty.
- [x] Add a controller test containing ordinary, domain-transfer, and planned-date events and assert only the ordinary event remains in the tracking response.
- [x] Run `mvn -q -Dnet.bytebuddy.experimental=true -Dtest=ReplayIssueTrackingProjectionTest,ReplayIssueControllerTest test` and confirm the new assertions fail.
- [x] Remove `batchNo` from `CHANGE_FIELDS` and filter the two excluded operation types before grouping history events.
- [x] Run the focused Maven tests and confirm they pass.

### Task 2: Build Unified Frontend Timeline

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: unchanged `inheritedEvents`, `manualEvents`, batch `actionType`, and `importedAt` response fields.
- Produces: `trackingEvents(group)` returning merged, filtered, descending events and one synthetic no-change import event when both arrays are empty.

- [x] Replace existing tracking assertions with expectations for one timeline, no system/user headings, no batch header time, and descending mixed event order.
- [x] Run the focused Vitest file and confirm the new assertions fail.
- [x] Implement `trackingEvents(group)` with stable timestamp and id ordering plus the no-change fallback event.
- [x] Replace the two event `<details>` blocks with one event list and keep operator identity on every event.
- [x] Move the original-data button beside the batch number/latest marker and remove the batch-level `<time>` element.
- [x] Update batch header and event-list CSS for clear desktop and narrow-screen layout.
- [x] Run the focused Vitest file and confirm the timeline tests pass.

### Task 3: Add Copyable Overflow Value Popover

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayTrackingValue.vue`
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayTrackingValue.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`

**Interfaces:**
- Consumes: `value: string | number | null | undefined`.
- Produces: a single-line value; when `scrollWidth > clientWidth`, hover or focus opens a popover containing full text and a Lucide copy button.

- [x] Add component tests for non-overflow text, overflow popover visibility, clipboard copy, success feedback, and empty values.
- [x] Run the component test and confirm it fails because the component is absent.
- [x] Implement overflow measurement on mount/resize, delayed close that permits pointer movement into the popover, and `navigator.clipboard.writeText` copying.
- [x] Use the component for original values and both change columns; remove native `title` attributes.
- [x] Run component and page tests and confirm they pass.

### Task 4: Build And Browser-Verify

**Files:**
- Modify generated output: `src/main/resources/static/`
- Update: `/Users/java/obsidian/log.md`

**Interfaces:**
- Consumes: local Vite Mock round-tracking data.
- Produces: production static assets embedded in the backend and visual evidence for desktop/mobile behavior.

- [x] Run the focused frontend and backend test suites.
- [x] Start or reuse the local Vite Mock server and open the first issue tracking drawer.
- [x] Verify batch header alignment, no batch time, one mixed timeline, descending timestamps, no excluded operations, and popover copy behavior at desktop width.
- [x] Repeat at a narrow mobile viewport and verify no overlap or clipped controls.
- [x] Run `VITE_USE_MOCK=0 npm run build` from `/Users/java/axon-link-frontend`.
- [x] Run `mvn clean package -DskipTests` and inspect the Jar for the new frontend text and backend class.
- [x] Append implementation and verification results to `/Users/java/obsidian/log.md`.
