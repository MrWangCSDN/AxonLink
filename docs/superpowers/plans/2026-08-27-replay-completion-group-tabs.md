# Replay Completion Group Tabs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把计划完成情况的四领域明细拆成固定顺序按钮切换，并确保日期范围变化保持当前领域、关闭重开才恢复默认存款组。

**Architecture:** 保留现有一次性返回全部 `dashboard.groups` 的接口，在模态框内部新增独立 `activeGroupName` 会话状态和 `activeGroup` 计算属性。领域按钮只切换前端展示，不发起接口请求；`initialize()` 负责每次打开时恢复默认领域，而 `loadDashboard()`、滑块和日期查询不得重置该状态。

**Tech Stack:** Vue 3、Vitest、Vue Test Utils、Vite

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 按钮固定顺序为存款组、贷款组、公共组、结算组。
- 每次打开模态框默认展示“存款组/开发负责人”。
- 日期滑块和日期输入查询只刷新数据，不改变当前领域。
- 关闭整个模态框再打开时恢复默认存款组。
- 某领域在当前日期范围没有数据时展示空状态，不隐藏按钮。
- 不修改后端接口、数据库或 Mock 数据结构。
- 当前前后端工作区包含用户已有改动，不 reset、不清理、不提交混合变更。

---

### Task 1: 锁定领域切换与状态生命周期

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`

**Interfaces:**
- Consumes: `dashboard.groups: Array<{ groupName: string, developers: Array }>` and `props.open: boolean`.
- Produces: `activeGroupName: Ref<string>`、`activeGroup: ComputedRef<object | null>`、固定 `groupTabs`。

- [x] **Step 1: Write failing tests for the four buttons and default group**

把测试 dashboard 扩展为四个领域，断言四个 `data-testid="completion-group-tab"` 按固定顺序展示，默认 `data-active="true"` 为存款组，表格只渲染存款组及其负责人。

- [x] **Step 2: Run the focused test and verify RED**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: FAIL because the component has no group buttons and renders all `dashboard.groups`.

- [x] **Step 3: Implement fixed tabs and single-group rendering**

在组件中加入：

```js
const defaultGroupName = '存款组'
const groupTabs = ['存款组', '贷款组', '公共组', '结算组']
const activeGroupName = ref(defaultGroupName)
const activeGroup = computed(() => dashboard.value?.groups?.find(group => group.groupName === activeGroupName.value) || null)
```

模板渲染四个按钮，按钮文案为 `${groupName}/开发负责人`，表格改为只使用 `activeGroup`；没有数据时显示“当前时间范围暂无该领域数据”。

- [x] **Step 4: Run the focused test GREEN**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: the new default-and-switching assertions pass.

---

### Task 2: 日期刷新保持领域、关闭重开恢复默认

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`

**Interfaces:**
- Consumes: `activeGroupName`, `loadDashboard(startDate, endDate)`, `initialize()` and the `open` prop watcher.
- Produces: a modal-session lifecycle where only `initialize()` resets `activeGroupName`.

- [x] **Step 1: Write failing lifecycle tests**

切换到结算组后触发时间滑块 `change`，断言结算组仍激活；随后把 `open` 设为 `false` 再设为 `true`，断言恢复存款组。

- [x] **Step 2: Run the focused test and verify RED**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: FAIL until the reset is placed only at the open-session boundary.

- [x] **Step 3: Implement modal-session reset boundary**

在 `initialize()` 开始位置执行 `activeGroupName.value = defaultGroupName`；不要在 `loadDashboard()`、`moveStart()`、`moveEnd()` 或 `applyInputRange()` 修改该字段。

- [x] **Step 4: Run focused and full frontend verification**

Run:

```bash
npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js
npm test
npm run build
```

Expected: focused tests、全部前端测试及生产构建均通过，最新资源写入后端 `src/main/resources/static`。

- [x] **Step 5: Browser verify local Mock**

在 `http://127.0.0.1:5174/#replay-issues` 验证固定四按钮、默认存款组、结算组在时间轴变化后保持选中，以及关闭重开恢复存款组。
