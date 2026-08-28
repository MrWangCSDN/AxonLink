# Replay Completion Real Date Selects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把计划完成情况的手工日期输入改成全量真实日期下拉选择，并在前端阻止开始日期晚于结束日期的查询。

**Architecture:** 复用现有 `datePoints` 作为两个原生 `<select>` 的唯一选项来源，不增加接口和本地日期生成逻辑。下拉框继续绑定 `startDateInput/endDateInput`，时间轴沿用现有同步函数；`applyInputRange()` 在调用后端前按日期点索引校验区间。

**Tech Stack:** Vue 3、Vitest、Vue Test Utils、Vite

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 日期只能选择 `datePoints` 中实际存在的日期，不允许手工输入。
- 两个下拉选项按时间轴既有正序完整展示。
- 默认范围和时间轴双向同步行为保持不变。
- 开始日期晚于结束日期时显示指定提示，不请求后端、不改变统计和当前领域。
- 不自动交换错误区间，不修改后端接口和数据库。
- 当前工作区包含用户已有改动，不 reset、不清理、不提交混合变更。

---

### Task 1: 真实日期下拉与区间校验

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`

**Interfaces:**
- Consumes: `datePoints: Ref<Array<{date: string}>>`, `startDateInput`, `endDateInput`, `loadDashboard()`.
- Produces: two `<select>` controls and guarded `applyInputRange(): Promise<void>`.

- [x] **Step 1: Write failing select-option and invalid-range tests**

断言起止控件标签均为 `SELECT`，选项文本与 `datePoints.map(point => point.date)` 完全一致；选择开始 `2026-08-29`、结束 `2026-08-25` 后点击查询，断言显示“开始日期不能晚于结束日期，请重新选择”，统计接口仍只保留初始化的一次调用，当前领域和统计行不变。

- [x] **Step 2: Run focused test RED**

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: FAIL because the controls are text inputs and invalid ranges still call the backend.

- [x] **Step 3: Implement real-date selects**

把两个文本输入替换为：

```vue
<select v-model="startDateInput" data-testid="completion-start-date">
  <option v-for="point in datePoints" :key="point.date" :value="point.date">{{ point.date }}</option>
</select>
```

结束日期下拉使用相同选项，保留现有 v-model 和测试标识。

- [x] **Step 4: Implement range guard before request**

在 `applyInputRange()` 中先取得两个日期在 `datePoints` 的索引；任一无效时提示“请选择有效的计划完成日期”，开始索引大于结束索引时提示“开始日期不能晚于结束日期，请重新选择”并直接返回。仅合法区间调用 `loadDashboard()`。

- [x] **Step 5: Update slider synchronization test and run focused GREEN**

把旧的任意文本/吸附测试改为从下拉选择真实日期，保留滑块逐格移动后下拉值同步的断言。

Run: `npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: focused tests pass.

- [x] **Step 6: Run full verification and browser acceptance**

Run:

```bash
npm test
npm run build
git diff --check
```

在 `http://127.0.0.1:5174/#replay-issues` 验证两个下拉均有 30 个真实日期、非法区间出现提示且统计不变、合法区间正常刷新、领域选择保持不变。
