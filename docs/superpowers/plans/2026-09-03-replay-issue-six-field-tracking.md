# Replay Issue Six-Field Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restrict the issue tracking timeline to actual changes in issue status, issue type, cooperation person, initial analysis, final solution, and remark while preserving complete original import data.

**Architecture:** Keep the field whitelist in `ReplayIssueTrackingProjection`, which remains the single backend projection for snapshot differences. Filter projected history entries with empty `changes` from timeline event arrays, while retaining the existing batch shell so the frontend can show one batch-level no-change system item.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, MockMvc, Vue 3, Vitest.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Track exactly `issueStatus`, `issueType`, `cooperationPerson`, `initialAnalysis`, `finalSolution`, and `remark`.
- Preserve all 29 `originalData` fields.
- Preserve stored history rows and snapshots.
- Hide ordinary projected events whose `changes` list is empty.
- Retain an empty batch group so the frontend can show “本次导入未产生上述字段变化”.
- Do not commit or discard unrelated working-tree changes.

---

### Task 1: Lock the Snapshot Projection Contract

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueTrackingProjectionTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueTrackingProjection.java`

**Interfaces:**
- Consumes: `ReplayIssueTrackingProjection.fieldChanges(String, String)`.
- Produces: an immutable list containing differences from only the six allowed fields.

- [ ] Add a test snapshot where all six allowed fields and several excluded fields change; assert the exact six labels and values.
- [ ] Run `mvn -q -Dnet.bytebuddy.experimental=true -Dtest=ReplayIssueTrackingProjectionTest test` and confirm the test fails because excluded fields are still returned.
- [ ] Reduce `CHANGE_FIELDS` to the exact six-field whitelist without changing `IMPORT_FIELDS`.
- [ ] Re-run the projection test and confirm it passes.

### Task 2: Remove Empty Timeline Events

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`

**Interfaces:**
- Consumes: projected `ReplayIssueHistoryEntry.changes()` lists.
- Produces: tracking groups whose event arrays contain only entries with at least one tracked-field change.

- [ ] Add a controller test with one excluded-only history event and one mixed event; assert the excluded-only event is absent and the mixed event contains only its tracked change.
- [ ] Run the focused controller test and confirm it fails because the empty projected event remains visible.
- [ ] Filter history entries after `projectHistory`, preserving batch groups and original data.
- [ ] Re-run focused backend tests and confirm they pass.

### Task 3: Align Frontend No-Change Copy

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`

**Interfaces:**
- Consumes: batch groups with empty `inheritedEvents` and `manualEvents`.
- Produces: one synthetic system item reading “本次导入未产生上述字段变化”.

- [ ] Update the focused component assertion to require the new no-change wording.
- [ ] Run the focused frontend test and confirm the old wording causes failure.
- [ ] Update only the synthetic batch-level no-change copy.
- [ ] Re-run focused frontend tests and confirm they pass.

### Task 4: Verify and Package

**Files:**
- Verify: backend and frontend focused suites.
- Build: `/Users/java/axon-link-frontend/dist` into backend static resources through the existing build flow.
- Package: `target/axon-link-server-1.0.0.jar`.

**Interfaces:**
- Produces: tested production assets and a checksum-addressable Jar.

- [ ] Run `mvn -q -Dnet.bytebuddy.experimental=true -Dtest=ReplayIssueTrackingProjectionTest,ReplayIssueControllerTest test`.
- [ ] Run `npm test -- --run src/components/replay/ReplayTrackingValue.spec.js src/components/replay/ReplayIssuePage.spec.js` in `/Users/java/axon-link-frontend`.
- [ ] Browser-check `http://127.0.0.1:5175/` if the local Mock server is available.
- [ ] Run `VITE_USE_MOCK=0 npm run build` in `/Users/java/axon-link-frontend`.
- [ ] Run `mvn clean package -DskipTests` in `/Users/java/axon-link-server`.
- [ ] Inspect the Jar contents and calculate `shasum -a 256 target/axon-link-server-1.0.0.jar`.
