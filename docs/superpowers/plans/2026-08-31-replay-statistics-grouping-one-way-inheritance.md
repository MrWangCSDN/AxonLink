# 回放统计口径外层默认与弹窗单向继承 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 外层统计口径默认“按问题所属领域”，三个统计弹窗打开时继承外层口径，但弹窗内部切换不再反向修改外层或其他弹窗。

**Architecture:** 在 `ReplayIssuePage` 中保留一个外层口径状态，并新增汇总弹窗、计划完成弹窗两个会话口径。打开弹窗时执行一次父到子的复制；弹窗内部只更新自己的会话口径和对应接口，关闭后不持久化，重开再次复制。后端已有 `groupBy=domain|issueDomain` 支持，本次不修改接口和 SQL。

**Tech Stack:** Vue 3 Composition API、Vitest、现有 Replay Issue REST API

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 外层页面刷新后默认 `issueDomain`。
- 外层切换继续刷新外层 `/stats`，并作为以后打开弹窗的继承来源。
- 三个统计弹窗每次打开都复制外层当前口径。
- 弹窗内部切换不得修改外层口径，也不得影响其他弹窗的下次打开值。
- 关闭重开必须重新继承外层，不保留上次弹窗内部选择。
- 各组问题数和负责人排名仍分别调用 `/stats/groups`、`/stats/person-ranking`。
- 计划完成情况的总体、分组和问题下钻继续使用同一个弹窗会话 `groupBy`。
- 不修改后端兼容默认值 `domain`，页面通过显式参数实现默认 `issueDomain`。
- 保留工作区用户既有未提交改动，不重置、不清理、不覆盖无关文件。

---

### Task 1: 外层默认口径与汇总弹窗会话口径

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces: `statisticsGroupBy` 外层状态，默认值 `issueDomain`。
- Produces: `summaryModalGroupBy`、`summaryGroupingLoading`、`setSummaryModalGroupBy(groupBy)`。
- Consumes: `getReplayIssueStats`、`getReplayIssueGroupSummaries`、`getReplayIssuePersonRankings`。

- [x] **Step 1: 写外层默认值失败测试**

挂载页面后断言首次元数据请求显式传递 `issueDomain`，外层按钮“按问题所属领域”点亮：

```js
expect(getReplayIssueStats).toHaveBeenLastCalledWith({ groupBy: 'issueDomain' })
expect(wrapper.get('[data-testid="stats-group-issue-domain-toolbar"]').attributes('aria-pressed')).toBe('true')
```

- [x] **Step 2: 写汇总弹窗单向继承失败测试**

覆盖以下行为：外层默认 `issueDomain` 时打开负责人排名，接口收到 `issueDomain`；弹窗内部切到 `domain` 后仅排名接口刷新，外层按钮仍保持 `issueDomain`；关闭后把外层切到 `domain`，重新打开各组问题数时继承 `domain`。

```js
await wrapper.get('[data-testid="person-ranking-entry"]').trigger('click')
expect(getReplayIssuePersonRankings).toHaveBeenLastCalledWith({ groupBy: 'issueDomain' })
await wrapper.get('[data-testid="stats-group-domain-modal"]').trigger('click')
expect(wrapper.get('[data-testid="stats-group-issue-domain-toolbar"]').attributes('aria-pressed')).toBe('true')
expect(getReplayIssueStats).toHaveBeenCalledTimes(initialStatsCalls)
expect(getReplayIssuePersonRankings).toHaveBeenLastCalledWith({ groupBy: 'domain' })
```

- [x] **Step 3: 运行测试确认 RED**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayIssuePage.spec.js`

Expected: FAIL；当前外层默认 `domain`，且弹窗内部按钮仍调用 `setStatisticsGroupBy` 修改外层。

- [x] **Step 4: 实现外层默认和汇总弹窗本地状态**

将外层初始值改为：

```js
const statisticsGroupBy = ref('issueDomain')
```

新增汇总弹窗状态：

```js
const summaryModalGroupBy = ref('issueDomain')
const summaryGroupingLoading = ref(false)
```

`openSummaryModal(type)` 先执行 `summaryModalGroupBy.value = statisticsGroupBy.value`，再打开并按本地口径加载。汇总弹窗的按钮、组名集合、负责人页签和 `loadSummaryRows` 全部读取 `summaryModalGroupBy`；`setSummaryModalGroupBy` 只更新本地状态并重查当前弹窗，不调用 `/stats`。外层 `setStatisticsGroupBy` 删除“刷新当前汇总弹窗”的逻辑。

- [x] **Step 5: 运行页面测试确认第一阶段 GREEN**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayIssuePage.spec.js`

Expected: 新默认值和两个汇总弹窗单向继承用例通过；根据新默认值更新的既有断言全部通过。

### Task 2: 计划完成弹窗会话口径

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Verify only: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`

**Interfaces:**
- Produces: `plannedCompletionGroupBy`、`openPlannedCompletion()`、`setPlannedCompletionGroupBy(groupBy)`。
- Consumes: `ReplayPlannedCompletionModal` 的 `groupBy` prop 与 `update:groupBy` 事件。

- [x] **Step 1: 写计划完成弹窗隔离失败测试**

默认外层 `issueDomain` 时打开计划完成情况，断言 dashboard 使用 `issueDomain`。在弹窗内切换为 `domain` 后，dashboard 使用 `domain`，但外层仍保持 `issueDomain`。关闭重开后，再次继承外层 `issueDomain`。

```js
await wrapper.get('[data-testid="planned-completion-entry"]').trigger('click')
expect(getReplayCompletionDashboard).toHaveBeenLastCalledWith(expect.objectContaining({ groupBy: 'issueDomain' }))
await wrapper.get('[data-testid="completion-grouping-domain"]').trigger('click')
await flushPromises()
expect(wrapper.get('[data-testid="stats-group-issue-domain-toolbar"]').attributes('aria-pressed')).toBe('true')
expect(getReplayCompletionDashboard).toHaveBeenLastCalledWith(expect.objectContaining({ groupBy: 'domain' }))
```

- [x] **Step 2: 运行测试确认 RED**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayIssuePage.spec.js`

Expected: FAIL；计划完成弹窗仍把 `update:groupBy` 直接转发给外层 `setStatisticsGroupBy`。

- [x] **Step 3: 实现计划完成弹窗本地状态**

入口按钮改为调用 `openPlannedCompletion()`；函数打开前执行：

```js
plannedCompletionGroupBy.value = statisticsGroupBy.value
plannedCompletionOpen.value = true
```

父组件传入 `:group-by="plannedCompletionGroupBy"`，并将事件绑定到只修改本地状态的 `setPlannedCompletionGroupBy`。关闭只设置 `plannedCompletionOpen=false`；下次打开覆盖旧的本地值。`ReplayPlannedCompletionModal` 继续使用现有 prop 监听重新查询，无需修改其内部接口逻辑。

- [x] **Step 4: 运行页面及计划完成组件测试确认 GREEN**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayIssuePage.spec.js ReplayPlannedCompletionModal.spec.js replayIssues.spec.js`

Expected: 三个弹窗的继承、隔离、关闭重开及 API 参数测试全部通过。

### Task 3: 完整回归与页面验收

**Files:**
- Modify: `/Users/java/obsidian/log.md`
- Generated by build: `/Users/java/axon-link-server/src/main/resources/static/**`

**Interfaces:**
- Consumes: Task 1、Task 2 的前端状态流。
- Produces: 后端最新静态资源及实施记录。

- [x] **Step 1: 运行前端全量测试**

Run: `cd /Users/java/axon-link-frontend && npm test`

Expected: 全部测试文件通过。

- [x] **Step 2: 生产构建到后端 static**

Run: `cd /Users/java/axon-link-frontend && npm run build`

Expected: Vite 构建退出码 `0`，产物写入 `/Users/java/axon-link-server/src/main/resources/static`。

- [x] **Step 3: 浏览器验收单向继承**

在 `http://127.0.0.1:5174/#replay-issues` 验证：刷新后外层默认“按问题所属领域”；打开三个弹窗都继承该口径；任一弹窗内部切到“领域”后外层仍保持“按问题所属领域”；关闭重开恢复继承外层。再把外层切到“按领域”，分别重开三个弹窗，确认都继承“领域”。

- [x] **Step 4: 更新日志并检查差异**

在 `/Users/java/obsidian/log.md` 追加 `[IMPL]` 记录；执行：

```bash
cd /Users/java/axon-link-frontend
git diff --check -- src/components/replay/ReplayIssuePage.vue src/components/replay/ReplayIssuePage.spec.js
```

Expected: 无空白错误，不改动后端 Java 统计逻辑和无关文件。
