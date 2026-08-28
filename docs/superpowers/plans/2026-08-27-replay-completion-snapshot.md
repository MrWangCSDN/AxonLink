# Replay Completion Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a “拍摄快照” action that generates a complete PNG for the current completion-statistics date range and group, downloads it, and copies the same image to the browser clipboard.

**Architecture:** A focused `completionSnapshot.js` module converts the current group summary plus all sorted developers into a Canvas PNG Blob and owns the browser download/clipboard adapter. `ReplayPlannedCompletionModal.vue` supplies the already-loaded effective date range and active group, controls button state, and reports full or partial success without adding a backend API.

**Tech Stack:** Vue 3, browser Canvas 2D API, PNG Blob, ClipboardItem, Vitest, happy-dom

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` — “当前组图片快照”

## Global Constraints

- Snapshot content is the current effective `yyyy-MM-dd` range, current group total, and every developer row in the current group.
- The image excludes timeline, overview cards, group tabs, issue drawer, and page background.
- Filename is `计划完成情况-<领域>-<开始日期>至<结束日期>.png`.
- Download must complete even when image clipboard writing is unsupported or rejected.
- No backend endpoint, database record, uploaded file, third-party screenshot library, or scheduled snapshot.

---

### Task 1: Canvas Snapshot Module

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/replay/completionSnapshot.js`
- Create: `/Users/java/axon-link-frontend/src/components/replay/completionSnapshot.spec.js`

**Interfaces:**
- Produces: `buildCompletionSnapshotFilename({ groupName, startDate, endDate }): string`
- Produces: `createCompletionSnapshotBlob({ group, developers, startDate, endDate }, environment?): Promise<Blob>`
- Produces: `downloadAndCopyCompletionSnapshot(blob, filename, environment?): Promise<{ copied: boolean }>`

- [x] **Step 1: Write failing filename and complete-row tests**

Create fake Canvas/2D context objects that record `fillText` calls and return a PNG Blob from `toBlob`. Assert the generated filename contains the group and both dates; assert rendering one group plus three developers writes all four row labels and `2026-08-28 至 2026-08-30`.

```js
expect(buildCompletionSnapshotFilename({ groupName: '存款组', startDate: '2026-08-28', endDate: '2026-08-30' }))
  .toBe('计划完成情况-存款组-2026-08-28至2026-08-30.png')
expect(writtenTexts).toEqual(expect.arrayContaining(['存款组', '负责人甲', '负责人乙', '负责人丙', '2026-08-28 至 2026-08-30']))
```

- [x] **Step 2: Run the module test and verify RED**

Run: `npm test -- src/components/replay/completionSnapshot.spec.js`

Expected: FAIL because `completionSnapshot.js` and its exports do not exist.

- [x] **Step 3: Implement deterministic Canvas rendering**

Use a fixed logical width of `1400`, a `52px` header row, `48px` data rows, and a height calculated from `1 + developers.length`. Draw a white background, title, group/date subtitle, seven table headers, the group total row, then every developer row. Render all numeric cells from `plannedTotal`, `onTimeFixedCount`, `lateFixedCount`, `unfinishedCount`, `overdueUnfinishedCount`, and two-decimal `completionRate`. Convert with `canvas.toBlob(..., 'image/png')` and reject with `快照图片生成失败` when Canvas context or Blob creation fails.

- [x] **Step 4: Write failing download/clipboard isolation tests**

Assert an object URL download is triggered and revoked; assert ClipboardItem receives the same Blob; assert clipboard rejection still resolves `{ copied: false }` after download.

```js
await expect(downloadAndCopyCompletionSnapshot(blob, 'snapshot.png', env)).resolves.toEqual({ copied: false })
expect(anchor.click).toHaveBeenCalledOnce()
expect(env.URL.revokeObjectURL).toHaveBeenCalledWith('blob:test')
```

- [x] **Step 5: Implement download-first and best-effort clipboard behavior**

Create an `<a download>` with an object URL, click and remove it, then revoke the URL. After download, attempt `clipboard.write([new ClipboardItem({ 'image/png': blob })])`; return `{ copied: false }` when either API is absent or writing rejects, without rejecting the overall action.

- [x] **Step 6: Run module tests until GREEN**

Run: `npm test -- src/components/replay/completionSnapshot.spec.js`

Expected: all snapshot module tests pass.

### Task 2: Modal Snapshot Interaction

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Consumes: all three exports from `completionSnapshot.js`.

- [x] **Step 1: Write failing component interaction tests**

Mock the snapshot module. Assert the button appears to the right of group tabs, is disabled without `activeGroup`, passes `startDateInput`, `endDateInput`, `activeGroup`, and `sortedActiveDevelopers` to the generator, and uses the matching filename. Assert copied success displays `快照已保存并复制`; copied false displays `快照已保存，但图片复制失败`; generation rejection displays `快照生成失败，请重试`.

- [x] **Step 2: Run focused modal tests and verify RED**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js -t 'snapshot'`

Expected: FAIL because the button and action do not exist.

- [x] **Step 3: Add button, state, and action**

Add `snapshotting`, `snapshotMessage`, and `snapshotMessageKind`. Render a `data-testid="completion-snapshot"` button in the same toolbar as group tabs. Implement `captureSnapshot()` to close the issue drawer, generate the Blob from the effective selected dates and current group, call download/copy, and set the appropriate message. Disable while loading, snapshotting, or without an active group.

- [x] **Step 4: Add restrained toolbar and feedback styles**

Keep group tabs horizontally scrollable on the left and the snapshot button fixed on the right. Use the existing blue primary-button language. Place the small success/warning/error feedback adjacent to the button without adding a new row; allow wrapping only on narrow screens.

- [x] **Step 5: Run modal and full frontend tests**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Run: `npm test`

Expected: all tests pass.

### Task 3: Production Build and Browser Verification

**Files:**
- Generated: `/Users/java/axon-link-server/src/main/resources/static/**`
- Update: `/Users/java/obsidian/log.md`

- [x] **Step 1: Build the frontend into the backend**

Run: `npm run build` from `/Users/java/axon-link-frontend`.

Expected: Vite writes the production assets to the backend `static` directory.

- [x] **Step 2: Verify the real Mock interaction**

Open `http://127.0.0.1:5174/#replay-issues`, open “计划完成情况”, select a multi-person group and date range, click “拍摄快照”, and verify the downloaded filename includes the selected group and both dates. Inspect the PNG to confirm it contains the group total plus every developer row, not only visible rows. Verify success or the explicit clipboard-partial-success message according to browser capability.

- [x] **Step 3: Run final hygiene checks**

Run `git diff --check` in `/Users/java/axon-link-frontend`, `/Users/java/axon-link-server`, and `/Users/java/obsidian`.

- [x] **Step 4: Append implementation evidence**

Append an `[IMPL]` entry to `/Users/java/obsidian/log.md` with test counts, build result, snapshot row verification, filename, and clipboard outcome.
