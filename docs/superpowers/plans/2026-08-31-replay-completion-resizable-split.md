# Replay Completion Resizable Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将“计划完成情况”改为接近全屏、可上下拖拽且可收起上半区的双分区模态框，让日期筛选和负责人明细能够同时清晰展示。

**Architecture:** 保持现有统计 API、快照和问题下钻不变，在前端增加纯函数布局计算模块，由 `ReplayPlannedCompletionModal.vue` 管理会话内拖拽、收起和复位。桌面端使用固定标题栏与上下 Grid 分区，下半区表格独立滚动；小屏回退为自然文档流和整体滚动。

**Tech Stack:** Vue 3 Composition API、Vitest、Vue Test Utils、原生 Pointer Events、CSS Grid、Vite

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` 中“全屏可拖拽上下分区（2026-08-31）”章节

## Global Constraints

- 只修改前端布局和会话内状态；不得修改后端接口、统计口径、下钻参数、快照内容或文件名。
- 桌面端模态框约为 `96vw × 94vh`，默认上半区比例为 `38%`。
- 上半区最小约 `230px`，下半区最小约 `260px`；拖拽和窗口缩放执行同一约束。
- 数据刷新、领域和统计口径切换保持比例与收起状态；关闭再打开恢复默认比例与展开状态。
- 宽度不超过 `900px` 时停用拖拽，使用自然排列和模态框整体纵向滚动。
- 不增加第三方依赖。
- 前端仓库已有用户未提交改动；不得 reset、clean 或覆盖无关改动，只编辑本计划列出的文件。

---

### Task 1: Add deterministic split calculations

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/replay/replayCompletionSplit.js`
- Create: `/Users/java/axon-link-frontend/src/components/replay/replayCompletionSplit.spec.js`

**Interfaces:**
- Produces: `DEFAULT_TOP_RATIO = 0.38`
- Produces: `MIN_TOP_HEIGHT = 230`
- Produces: `MIN_BOTTOM_HEIGHT = 260`
- Produces: `clampTopHeight(requestedHeight: number, availableHeight: number): number`
- Produces: `defaultTopHeight(availableHeight: number): number`

- [ ] **Step 1: Write the failing pure-function tests**

```js
import { describe, expect, it } from 'vitest'
import {
  DEFAULT_TOP_RATIO, MIN_BOTTOM_HEIGHT, MIN_TOP_HEIGHT,
  clampTopHeight, defaultTopHeight,
} from './replayCompletionSplit.js'

describe('replay completion split calculations', () => {
  it('uses 38 percent within both pane minimums', () => {
    expect(DEFAULT_TOP_RATIO).toBe(0.38)
    expect(defaultTopHeight(800)).toBe(304)
  })

  it('prevents either pane from disappearing', () => {
    expect(clampTopHeight(20, 800)).toBe(MIN_TOP_HEIGHT)
    expect(clampTopHeight(790, 800)).toBe(800 - MIN_BOTTOM_HEIGHT)
  })

  it('splits evenly if the viewport cannot satisfy both minimums', () => {
    expect(clampTopHeight(300, 400)).toBe(200)
    expect(defaultTopHeight(400)).toBe(200)
  })
})
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/replayCompletionSplit.spec.js
```

Expected: FAIL because `replayCompletionSplit.js` does not exist.

- [ ] **Step 3: Implement the pure calculations**

```js
export const DEFAULT_TOP_RATIO = 0.38
export const MIN_TOP_HEIGHT = 230
export const MIN_BOTTOM_HEIGHT = 260

export function clampTopHeight(requestedHeight, availableHeight) {
  const height = Math.max(0, Number(availableHeight) || 0)
  if (height < MIN_TOP_HEIGHT + MIN_BOTTOM_HEIGHT) return Math.round(height / 2)
  return Math.min(
    height - MIN_BOTTOM_HEIGHT,
    Math.max(MIN_TOP_HEIGHT, Math.round(Number(requestedHeight) || 0)),
  )
}

export function defaultTopHeight(availableHeight) {
  return clampTopHeight(availableHeight * DEFAULT_TOP_RATIO, availableHeight)
}
```

- [ ] **Step 4: Run the focused test**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/replayCompletionSplit.spec.js
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Record the calculation-unit checkpoint**

Run `git -C /Users/java/axon-link-frontend diff -- src/components/replay/replayCompletionSplit.js src/components/replay/replayCompletionSplit.spec.js` and confirm only the new pure functions and their three tests are present. Do not commit yet because the current front-end worktree contains overlapping user changes.

---

### Task 2: Restructure the modal into upper and lower panes

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue:1-159,220-556`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Consumes: `defaultTopHeight()` and `clampTopHeight()` from Task 1
- Produces test IDs: `completion-split-layout`, `completion-upper-pane`, `completion-splitter`, `completion-collapse-upper`, `completion-collapsed-summary`, `completion-lower-pane`
- Preserves: existing close, group switch, date range, drawer and snapshot behavior

- [ ] **Step 1: Add failing component tests for structure and collapse/restore**

```js
it('renders a fixed-header two-pane layout', async () => {
  const wrapper = mount(ReplayPlannedCompletionModal, { props: { open: true } })
  await flushPromises()
  expect(wrapper.get('[data-testid="completion-upper-pane"]').exists()).toBe(true)
  expect(wrapper.get('[data-testid="completion-splitter"]').attributes('role')).toBe('separator')
  expect(wrapper.get('[data-testid="completion-splitter"]').attributes('aria-orientation')).toBe('horizontal')
  expect(wrapper.get('[data-testid="completion-lower-pane"]').exists()).toBe(true)
  expect(wrapper.get('[data-testid="completion-split-layout"]').attributes('style'))
    .toContain('--completion-top-height:')
})

it('collapses to a summary and restores the previous height', async () => {
  const wrapper = mount(ReplayPlannedCompletionModal, { props: { open: true } })
  await flushPromises()
  const before = wrapper.get('[data-testid="completion-split-layout"]').attributes('style')
  await wrapper.get('[data-testid="completion-collapse-upper"]').trigger('click')
  expect(wrapper.find('[data-testid="completion-upper-pane"]').exists()).toBe(false)
  expect(wrapper.get('[data-testid="completion-collapsed-summary"]').text()).toContain('2026-08-25 至 2026-08-29')
  expect(wrapper.get('[data-testid="completion-collapsed-summary"]').text()).toContain('计划问题数 21')
  await wrapper.get('[data-testid="completion-collapse-upper"]').trigger('click')
  expect(wrapper.get('[data-testid="completion-split-layout"]').attributes('style')).toBe(before)
})
```

- [ ] **Step 2: Run the component spec and verify failure**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js
```

Expected: FAIL because the pane, separator and collapse elements do not exist.

- [ ] **Step 3: Add the component state**

```js
import { clampTopHeight, defaultTopHeight } from './replayCompletionSplit.js'

const splitLayoutRef = ref(null)
const topPaneHeight = ref(0)
const upperCollapsed = ref(false)
const topHeightBeforeCollapse = ref(0)
const splitLayoutStyle = computed(() => ({ '--completion-top-height': `${topPaneHeight.value}px` }))

function availableSplitHeight() {
  return splitLayoutRef.value?.getBoundingClientRect().height || 0
}

function resetSplitLayout() {
  upperCollapsed.value = false
  topPaneHeight.value = defaultTopHeight(availableSplitHeight() || 760)
  topHeightBeforeCollapse.value = topPaneHeight.value
}

function toggleUpperPane() {
  if (upperCollapsed.value) {
    upperCollapsed.value = false
    topPaneHeight.value = clampTopHeight(topHeightBeforeCollapse.value, availableSplitHeight() || 760)
  } else {
    topHeightBeforeCollapse.value = topPaneHeight.value
    upperCollapsed.value = true
  }
}
```

Call `resetSplitLayout()` only when the modal opens, after `nextTick()`. Do not call it from `loadDashboard()`, range changes or group switches.

- [ ] **Step 4: Split the existing template**

Move the existing `.replay-completion-timeline-section` and `.replay-completion-overview` nodes, without changing their children, between `<section v-if="!upperCollapsed" data-testid="completion-upper-pane" class="replay-completion-upper-pane">` and its closing `</section>`. Move the existing `.replay-completion-group-toolbar` and `.replay-completion-table-stage`, without changing their children, between `<section data-testid="completion-lower-pane" class="replay-completion-lower-pane">` and its closing `</section>`. Both sections are children of this exact outer wrapper:

```vue
<div ref="splitLayoutRef" data-testid="completion-split-layout"
  class="replay-completion-split-layout"
  :class="{ 'is-upper-collapsed': upperCollapsed }"
  :style="splitLayoutStyle">
```

Immediately after the upper section, insert the collapsed alternative and separator exactly as follows; then place the lower section after the separator and close the outer wrapper after the lower section:

```vue
<div v-if="upperCollapsed" data-testid="completion-collapsed-summary" class="replay-completion-collapsed-summary">
  <span>{{ startDateInput }} 至 {{ endDateInput }}</span>
  <span>{{ groupBy === 'issueDomain' ? '问题所属领域' : '领域' }}</span>
  <strong>计划问题数 {{ dashboard?.summary?.plannedTotal ?? '-' }}</strong>
</div>
<div data-testid="completion-splitter" class="replay-completion-splitter"
  role="separator" aria-orientation="horizontal">
  <span class="replay-completion-splitter-grip" aria-hidden="true"></span>
  <button data-testid="completion-collapse-upper" type="button" @click="toggleUpperPane">
    {{ upperCollapsed ? '展开筛选与时间轴' : '收起筛选与时间轴' }}
  </button>
</div>
```

Keep the issue drawer as a sibling of the split layout inside the modal. Keep loading, empty and error feedback inside the pane that owns it.

- [ ] **Step 5: Add desktop layout CSS**

```css
.replay-completion-mask{padding:3vh 2vw}
.replay-completion-modal{width:96vw;max-width:none;height:94vh;max-height:none}
.replay-completion-header{flex:0 0 auto}
.replay-completion-body{display:flex;flex:1;min-height:0;overflow:hidden;padding:0}
.replay-completion-split-layout{display:grid;flex:1;min-height:0;grid-template-rows:minmax(0,var(--completion-top-height)) 34px minmax(260px,1fr);padding:0 22px 20px}
.replay-completion-upper-pane{min-height:0;overflow:hidden}
.replay-completion-lower-pane{display:flex;min-height:0;flex-direction:column}
.replay-completion-table-stage{display:flex;flex:1;min-height:0;flex-direction:column}
.replay-completion-table-wrap{flex:1;min-height:0;max-height:none;overflow:auto}
.replay-completion-splitter{display:flex;align-items:center;gap:10px}
.replay-completion-splitter-grip{flex:1;height:1px;background:#d7e0eb;cursor:row-resize}
.replay-completion-collapsed-summary{display:flex;align-items:center;gap:18px;min-height:42px}
```

- [ ] **Step 6: Run Task 1 and modal specs**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/replayCompletionSplit.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js
```

Expected: both specs PASS, including existing close-only-by-X, snapshots, range validation and drawer tests.

- [ ] **Step 7: Record the structural checkpoint**

Run `git -C /Users/java/axon-link-frontend diff -- src/components/replay/ReplayPlannedCompletionModal.vue src/components/replay/ReplayPlannedCompletionModal.spec.js` and confirm the existing business controls were only relocated, not renamed or removed. Do not commit overlapping dirty files.

---

### Task 3: Add pointer dragging, resize clamping and small-screen fallback

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Consumes: `clampTopHeight()` and Task 2 test IDs
- Produces handlers: `startSplitDrag`, `moveSplitDrag`, `stopSplitDrag`
- Produces one `ResizeObserver` while the modal is open

- [ ] **Step 1: Add failing tests for limits, persistence and reopen reset**

```js
it('clamps a dragged separator to both minimums', async () => {
  globalThis.PointerEvent ||= MouseEvent
  const wrapper = mount(ReplayPlannedCompletionModal, { props: { open: true } })
  await flushPromises()
  const layout = wrapper.get('[data-testid="completion-split-layout"]')
  layout.element.getBoundingClientRect = () => ({ top: 100, height: 800 })
  await wrapper.get('.replay-completion-splitter-grip').trigger('pointerdown', { pointerId: 1, clientY: 120 })
  window.dispatchEvent(new PointerEvent('pointermove', { pointerId: 1, clientY: 890 }))
  window.dispatchEvent(new PointerEvent('pointerup', { pointerId: 1 }))
  await wrapper.vm.$nextTick()
  expect(layout.attributes('style')).toContain('--completion-top-height: 540px')
})

it('keeps the split through refresh and resets only after reopen', async () => {
  globalThis.PointerEvent ||= MouseEvent
  const wrapper = mount(ReplayPlannedCompletionModal, { props: { open: true } })
  await flushPromises()
  const layout = wrapper.get('[data-testid="completion-split-layout"]')
  layout.element.getBoundingClientRect = () => ({ top: 100, height: 800 })
  await wrapper.get('.replay-completion-splitter-grip').trigger('pointerdown', { pointerId: 2, clientY: 500 })
  window.dispatchEvent(new PointerEvent('pointermove', { pointerId: 2, clientY: 500 }))
  window.dispatchEvent(new PointerEvent('pointerup', { pointerId: 2 }))
  const draggedStyle = layout.attributes('style')
  await wrapper.get('[data-testid="apply-completion-range"]').trigger('click')
  await flushPromises()
  expect(layout.attributes('style')).toBe(draggedStyle)
  await wrapper.setProps({ open: false })
  await wrapper.setProps({ open: true })
  await flushPromises()
  expect(wrapper.get('[data-testid="completion-split-layout"]').attributes('style')).not.toBe(draggedStyle)
})
```

- [ ] **Step 2: Run the modal spec and verify failure**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js
```

Expected: FAIL because pointer movement does not change the height.

- [ ] **Step 3: Implement pointer lifecycle**

```js
const draggingSplit = ref(false)
let activePointerId = null
let splitResizeObserver = null

function moveSplitDrag(event) {
  if (!draggingSplit.value || event.pointerId !== activePointerId || upperCollapsed.value) return
  const bounds = splitLayoutRef.value?.getBoundingClientRect()
  if (!bounds) return
  topPaneHeight.value = clampTopHeight(event.clientY - bounds.top, bounds.height)
  topHeightBeforeCollapse.value = topPaneHeight.value
}

function stopSplitDrag(event) {
  if (event && activePointerId !== null && event.pointerId !== activePointerId) return
  draggingSplit.value = false
  activePointerId = null
  window.removeEventListener('pointermove', moveSplitDrag)
  window.removeEventListener('pointerup', stopSplitDrag)
  window.removeEventListener('pointercancel', stopSplitDrag)
}

function startSplitDrag(event) {
  if (upperCollapsed.value || window.matchMedia('(max-width: 900px)').matches) return
  draggingSplit.value = true
  activePointerId = event.pointerId
  window.addEventListener('pointermove', moveSplitDrag)
  window.addEventListener('pointerup', stopSplitDrag)
  window.addEventListener('pointercancel', stopSplitDrag)
}
```

Bind `@pointerdown="startSplitDrag"` to `.replay-completion-splitter-grip`, not the collapse button. Add `user-select:none` while dragging.

- [ ] **Step 4: Add resize clamping and cleanup**

```js
function observeSplitLayout() {
  splitResizeObserver?.disconnect()
  if (!splitLayoutRef.value || typeof ResizeObserver === 'undefined') return
  splitResizeObserver = new ResizeObserver(entries => {
    const height = entries[0]?.contentRect?.height || availableSplitHeight()
    if (!upperCollapsed.value) topPaneHeight.value = clampTopHeight(topPaneHeight.value, height)
  })
  splitResizeObserver.observe(splitLayoutRef.value)
}

function disposeSplitLayout() {
  stopSplitDrag()
  splitResizeObserver?.disconnect()
  splitResizeObserver = null
}
```

Call `observeSplitLayout()` after the open-time `nextTick()`. Call `disposeSplitLayout()` when `open` becomes false and from `onBeforeUnmount` together with snapshot cleanup.

- [ ] **Step 5: Add the small-screen fallback**

```css
@media(max-width:900px){
  .replay-completion-mask{padding:10px}
  .replay-completion-modal{width:calc(100vw - 20px);height:calc(100vh - 20px)}
  .replay-completion-body{overflow:auto}
  .replay-completion-split-layout{display:block;overflow:visible;padding:0 14px 18px}
  .replay-completion-upper-pane,.replay-completion-lower-pane{overflow:visible}
  .replay-completion-splitter{display:none}
  .replay-completion-table-stage{display:block}
  .replay-completion-table-wrap{max-height:55vh;overflow:auto}
}
```

- [ ] **Step 6: Run focused and complete front-end tests**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/replayCompletionSplit.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js
npm test
```

Expected: focused specs and complete suite PASS.

- [ ] **Step 7: Record the drag/responsive checkpoint**

Run the focused diff again and verify all window listeners and the observer have matching cleanup paths. Keep the implementation uncommitted until the user requests a combined commit or the existing overlapping changes have been separated safely.

---

### Task 4: Build and perform browser acceptance with 50-person mock data

**Files:**
- Verify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify only if current mock has fewer than 50 developers: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify only with the preceding mock change: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`

**Interfaces:**
- Consumes: completed modal and existing planned-completion mock endpoints
- Produces: `/Users/java/axon-link-frontend/dist`
- Produces: browser evidence for desktop, small-screen, 50-row scroll and snapshot behavior

- [ ] **Step 1: Ensure the completion mock returns 50 developers**

```bash
rg -n "plannedTotal|matchedDeveloper|developers" /Users/java/axon-link-frontend/mock/daoIndexMockServer.js
```

If fewer than 50 exist, expand only the completion mock array with deterministic names `开发负责人01` through `开发负责人50`, keep the existing response schema, and add a mock test that asserts 50 entries for the selected group.

- [ ] **Step 2: Run mock tests and build**

```bash
cd /Users/java/axon-link-frontend
npm test -- mock/daoIndexMockServer.spec.js
npm run build
```

Expected: mock test PASS and Vite build succeeds.

- [ ] **Step 3: Start mock API and Vite UI**

```bash
cd /Users/java/axon-link-frontend
node mock/daoIndexMockServer.js
```

In a second terminal:

```bash
cd /Users/java/axon-link-frontend
npm run dev -- --host 127.0.0.1
```

Open the printed URL at `/#replay-issues`.

- [ ] **Step 4: Verify the desktop contract**

Open “计划完成情况” and verify all of the following:

1. Modal is approximately `96vw × 94vh`; title remains fixed.
2. Initial view simultaneously shows date controls/time axis/summary and multiple developer rows.
3. Separator stops near the `230px` top and `260px` bottom limits.
4. Date query, group switch and grouping switch preserve the current split.
5. Collapse shows range, grouping and planned total; expand restores the prior height.
6. Fifty developers scroll only inside the lower pane without pagination and the table header remains visible.
7. Issue drawer does not alter pane sizes; close restores the same split and scroll position.
8. Closing only with `X` and reopening restores expanded `38% / 62%` defaults and deposit group.
9. Snapshot still contains every developer, including rows outside the visible viewport.

- [ ] **Step 5: Verify the small-screen fallback**

At or below `900px` viewport width, verify the separator is hidden, content follows natural vertical flow, modal scrolling works, and date controls, group tabs, table and drawer remain usable.

- [ ] **Step 6: Record final scope without committing overlapping files**

```bash
git -C /Users/java/axon-link-frontend status --short
git -C /Users/java/axon-link-server status --short
```

If Task 4 changes mock files, include their focused diff in the handoff and do not commit them because both mock files already belong to the user's dirty working tree. Do not copy `dist` into backend `static` in this task; packaging remains a separate user-authorized delivery action.
