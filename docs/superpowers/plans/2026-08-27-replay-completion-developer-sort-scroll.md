# Replay Completion Developer Sort And Scroll Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让当前领域的全部开发负责人按计划问题数降序展示，数量多时在表格内部滚动且不分页。

**Architecture:** 不修改统计接口和问题明细抽屉。前端基于 `activeGroup.developers` 创建非破坏性的排序副本，模板一次渲染全部排序结果；表格容器设置最大高度和双向 `overflow:auto`，沿用 sticky 表头。

**Tech Stack:** Vue 3、Vitest、Vue Test Utils、CSS

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 开发负责人先按 `plannedTotal` 从大到小排序。
- `plannedTotal` 相同时按 `matchedDeveloper` 中文名称正序稳定排序。
- 领域合计行固定第一行，不参与排序。
- 一次渲染当前领域的全部负责人，不新增分页或接口请求。
- 超出可视高度时由表格区域纵向滚动，表头保持固定。
- 右侧问题明细抽屉现有分页保持不变。
- 当前工作区包含用户已有改动，不 reset、不清理、不提交混合变更。

---

### Task 1: 负责人排序与滚动表格

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`

**Interfaces:**
- Consumes: `activeGroup.developers` with `matchedDeveloper` and `plannedTotal`.
- Produces: `sortedActiveDevelopers: ComputedRef<Array>` and a scroll-bounded `.replay-completion-table-wrap`.

- [x] **Step 1: Write the failing sort and no-pagination test**

给存款组提供计划数 `12、7、7、3` 的四个负责人，断言四行全部渲染，顺序为 `12` 在前、两个 `7` 按名称正序、`3` 最后，并断言明细抽屉的上一页/下一页仍只在打开抽屉后出现。

- [x] **Step 2: Run focused test RED**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: FAIL because the component currently preserves API developer order.

- [x] **Step 3: Implement a non-mutating computed sort**

```js
const developerNameCollator = new Intl.Collator('zh-CN')
const sortedActiveDevelopers = computed(() => [...(activeGroup.value?.developers || [])].sort((left, right) => {
  const countDifference = Number(right.plannedTotal || 0) - Number(left.plannedTotal || 0)
  return countDifference || developerNameCollator.compare(String(left.matchedDeveloper || ''), String(right.matchedDeveloper || ''))
}))
```

模板循环改为 `sortedActiveDevelopers`，不增加分页状态或切换请求。

- [x] **Step 4: Bound table height and keep sticky header**

将 `.replay-completion-table-wrap` 设置为 `max-height:min(420px,45vh); overflow:auto`；保留现有 `thead th { position:sticky; top:0 }`。

- [x] **Step 5: Run verification**

Run:

```bash
npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js
npm test
npm run build
```

Expected: focused、完整前端测试与生产构建全部通过，最新前端资源写入后端 `src/main/resources/static`。

- [x] **Step 6: Browser verify local Mock**

把本地 Mock 扩为每组 15 个负责人，并制造从多到少的计划问题数分布；在 `http://127.0.0.1:5174/#replay-issues` 选择完整日期范围，验证负责人按计划问题数降序、15 行全部返回、列表超高时表格内部滚动、四领域切换及日期状态保持不回归。
