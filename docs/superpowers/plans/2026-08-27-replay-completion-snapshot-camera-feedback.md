# Replay Completion Snapshot Camera Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a short camera-style flash and shutter response to “拍摄快照”, then show “快照已经复制” and fade it out after successful clipboard copying.

**Architecture:** Keep snapshot rendering, downloading, and clipboard behavior in the existing `completionSnapshot.js`. Add presentation-only transient state and timers to `ReplayPlannedCompletionModal.vue`; CSS pseudo-elements render the flash and centered camera feedback over the existing table wrapper without entering the generated PNG.

**Tech Stack:** Vue 3 Composition API, CSS keyframes, lucide-vue-next, Vitest, Vue Test Utils, Vite

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` — “当前组图片快照”

## Global Constraints

- Use approved option A: a `100~140ms` white flash plus centered camera press/rebound feedback.
- Successful clipboard copying displays exactly `快照已经复制`, holds for about `1.5s`, then fades for `500~700ms` and is removed.
- Generation failure never shows the success message; clipboard failure keeps the existing partial-success message.
- Snapshotting disables duplicate clicks and duplicate animation runs.
- Animation is visual feedback only and must not change PNG content, filename, date range, group, sorting, backend APIs, or database state.
- `prefers-reduced-motion: reduce` skips the flash and shutter scaling while retaining concise result feedback.
- Preserve unrelated dirty-worktree changes and do not create a git commit.

---

### Task 1: Camera Feedback State and Timing

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`

**Interfaces:**
- Consumes: existing `captureSnapshot()` and `downloadAndCopyCompletionSnapshot(blob, filename): Promise<{ copied: boolean }>`.
- Produces: transient `snapshotEffectActive: Ref<boolean>` and `snapshotMessageVisible: Ref<boolean>` used only by the modal template.

- [x] **Step 1: Write failing interaction tests**

Use fake timers and a deferred snapshot Promise. Assert one click immediately adds `data-testid="completion-snapshot-effect"`, keeps the button disabled, and a second click does not call `createCompletionSnapshotBlob` twice. Resolve `{ copied: true }`, then assert the message is exactly `快照已经复制`; advance the hold/fade timers and assert the message is removed. Add failure assertions confirming generation failure and `{ copied: false }` never render `快照已经复制`.

```js
expect(wrapper.get('[data-testid="completion-snapshot-effect"]').classes()).toContain('is-active')
expect(createCompletionSnapshotBlob).toHaveBeenCalledTimes(1)
expect(wrapper.get('[data-testid="completion-snapshot-message"]').text()).toBe('快照已经复制')
await vi.advanceTimersByTimeAsync(2200)
expect(wrapper.find('[data-testid="completion-snapshot-message"]').exists()).toBe(false)
```

- [x] **Step 2: Run the focused tests and verify RED**

Run from `/Users/java/axon-link-frontend`:

```bash
npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js -t 'camera|snapshot'
```

Expected: FAIL because the effect layer, exact success copy, and automatic removal do not exist.

- [x] **Step 3: Implement minimal state and timer lifecycle**

Add refs for the active flash/shutter layer and message visibility, plus timer handles. At capture start, clear stale timers/message and activate the effect once. Deactivate the effect after the short camera sequence. On copied success, set `快照已经复制`, hold it for about `1500ms`, apply a leaving class for `600ms`, then remove it. Keep warning/error messages visible as existing operational feedback. Clear timers when the modal closes or reinitializes so a prior capture cannot mutate a reopened modal.

- [x] **Step 4: Run the focused tests until GREEN**

Run:

```bash
npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js -t 'camera|snapshot'
```

Expected: all camera/snapshot modal tests pass.

### Task 2: Overlay Markup, Animation, and Accessibility

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`

**Interfaces:**
- Consumes: `snapshotEffectActive`, `snapshotMessageVisible`, and the existing `snapshotMessageKind`.
- Produces: `.replay-completion-snapshot-flash`, `.replay-completion-snapshot-shutter`, and `.is-leaving` presentation states.

- [x] **Step 1: Write failing markup and reduced-motion contract tests**

Assert the effect is contained inside `.replay-completion-table-wrap`, is `aria-hidden="true"`, and the result message uses `role="status"`/`aria-live="polite"`. Read component CSS and assert it contains a reduced-motion media query that disables flash/shutter animation.

```js
expect(wrapper.get('.replay-completion-table-wrap [data-testid="completion-snapshot-effect"]').attributes('aria-hidden')).toBe('true')
expect(wrapper.get('[data-testid="completion-snapshot-message"]').attributes('role')).toBe('status')
expect(source).toContain('@media(prefers-reduced-motion:reduce)')
```

- [x] **Step 2: Run the focused tests and verify RED**

Run:

```bash
npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js -t 'camera|snapshot|reduced motion'
```

Expected: FAIL because the overlay placement, accessibility attributes, and CSS contract are absent.

- [x] **Step 3: Add overlay markup and scoped keyframes**

Make `.replay-completion-table-wrap` the positioned clipping container. Add a pointer-transparent absolute white flash layer and a centered dark circular camera surface using the existing `Camera` icon. Animate a single short flash and camera press/rebound, keep the table layout unchanged, and place the success status near the table bottom center with enter/leave transitions. Add `@media(prefers-reduced-motion:reduce)` rules to disable flash/shutter movement and shorten feedback transitions.

- [x] **Step 4: Run component and full frontend tests**

Run:

```bash
npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js
npm test
```

Expected: modal tests pass and the complete frontend suite remains green.

### Task 3: Production Build and Real Browser Verification

**Files:**
- Generated: `/Users/java/axon-link-server/src/main/resources/static/**`
- Update: `/Users/java/obsidian/log.md`

**Interfaces:**
- Consumes: the completed modal interaction.
- Produces: backend static assets containing the approved animation.

- [x] **Step 1: Build frontend assets into backend static resources**

Run from `/Users/java/axon-link-frontend`:

```bash
npm run build
```

Expected: Vite completes successfully and writes the production bundle to `/Users/java/axon-link-server/src/main/resources/static/`.

- [x] **Step 2: Verify the real Mock interaction in a browser**

Open the local replay-issues page, open “计划完成情况”, select a populated group, and click “拍摄快照”. Verify one short white flash plus centered camera rebound, the button reads `拍摄中…` and rejects a second click, the downloaded PNG remains complete, and copied success shows `快照已经复制` before fading out. Verify closing/reopening has no stale overlay or message.

- [x] **Step 3: Run final hygiene checks**

Run:

```bash
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/axon-link-server diff --check
git -C /Users/java/obsidian diff --check
```

Expected: all three commands exit successfully without whitespace errors.

- [x] **Step 4: Record implementation evidence**

Append one `[IMPL]` entry to `/Users/java/obsidian/log.md` with the final test count, build result, browser animation observation, download/clipboard result, and fade-out verification.
