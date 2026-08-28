# Replay Completion Timeline Responsive Mock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让少量计划日期自动铺满时间轴可视宽度，并把本地计划完成情况 Mock 扩展到 30 个真实非零日期点验证横向滚动效果。

**Architecture:** 时间轴容器使用 `width: 100%` 和 `min-width: 日期数 × 96px`，CSS Grid 在日期较少时均分可视宽度、日期较多时由最小宽度触发横向滚动。滑块轨道首尾内边距按 `50% / 日期数` 计算，与动态列中心保持同一几何口径；Mock 仍为四领域各 30 条问题，但全局覆盖 30 个计划日期。

**Tech Stack:** Vue 3、CSS Grid、Vitest、Vite Mock middleware、浏览器验收

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 日期较少时等宽铺满可视区域，不在右侧留下大块空白。
- 日期较多时每列至少 `96px`，总宽度超过容器后只在时间轴内部横向滚动。
- 柱子、日期、小圆点和滑块手柄必须共用同一列中心。
- 默认范围仍是最新 3 个真实日期点。
- 30 个日期点只存在于本地 Mock，不修改生产接口、数据库或迁移。
- 当前前后端工作区含用户已有改动，不 reset、不清理、不提交混合变更。

---

### Task 1: 自适应铺满与横向滚动几何

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Consumes: `datePoints.length` and the existing minimum column width `96`.
- Produces: timeline inline variables `--timeline-count` and `min-width`; slider inset `calc(50% / var(--timeline-count))`.

- [x] **Step 1: Write the failing responsive-width test**

Assert that five dates produce `min-width: 480px`, expose `--timeline-count: 5`, and do not hard-code `width: 480px`:

```js
const style = wrapper.get('.replay-completion-timeline').attributes('style')
expect(style).toContain('min-width: 480px')
expect(style).toContain('--timeline-count: 5')
expect(style).not.toMatch(/(^|;)\s*width:\s*480px/)
```

- [x] **Step 2: Run the modal test and verify RED**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: FAIL because the current component sets both `width` and `min-width` to the fixed total.

- [x] **Step 3: Implement the CSS geometry**

Change the timeline style to:

```js
const timelineWidthStyle = computed(() => ({
  minWidth: `${datePoints.value.length * columnWidth}px`,
  '--timeline-count': datePoints.value.length,
}))
```

Use `width: 100%` on the timeline, CSS Grid with `repeat(var(--timeline-count), minmax(96px, 1fr))`, and slider shell insets `calc(50% / var(--timeline-count))`. Remove per-column fixed flex basis so the rendered column center comes from the grid track.

- [x] **Step 4: Run the modal tests GREEN**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: 4 tests pass（包含最新 3 点自动滚入可视区验证）。

---

### Task 2: 30 个真实日期 Mock 与浏览器验收

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`

**Interfaces:**
- Produces: 30 ascending date points from `2026-08-01` through `2026-08-30`, every point `plannedCount > 0`.
- Preserves: four groups × 30 issues and latest-three default selection.

- [x] **Step 1: Add a deterministic 30-date axis**

Define:

```js
const REPLAY_COMPLETION_DATES = Array.from(
  { length: 30 },
  (_, index) => `2026-08-${String(index + 1).padStart(2, '0')}`,
)
```

公共组的 30 条问题逐日覆盖全部日期；其他三组使用确定性取模分布制造不同柱高，但不得产生零问题日期。

- [x] **Step 2: Verify mock syntax and focused frontend regression**

Run:

```bash
node --check mock/daoIndexMockServer.js
npm test -- src/components/replay/ReplayIssuePage.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js src/api/replayIssues.spec.js
```

Expected: syntax valid and focused tests pass.

- [x] **Step 3: Browser-verify the 30-date layout**

Verify in the local Mock page:

- exactly 30 non-zero date columns;
- default selected columns equal 3;
- timeline `scrollWidth > clientWidth`;
- first and last slider track centers match first and last column centers;
- horizontally scrolling reveals later dates without squeezing columns below `96px`.

- [x] **Step 4: Run production build after UI verification**

Run: `npm run build`

Expected: Vite build succeeds and copies the latest frontend into backend `src/main/resources/static`.
