# 计划完成时间轴柱子直查与重合端点双向拖动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持点击日期柱子立即查询单日数据，并让重合的日期端点能够按拖动方向向左或向右逐格展开，同时补充 2026-08-31 至 2026-09-05 的 Mock 数据。

**Architecture:** 将坐标到日期索引的换算及重合拖动边界计算提取为纯函数；Vue 组件只管理指针生命周期、日期同步和接口查询。普通范围继续使用原生双滑块，只有两端重合时切换为方向感知共享手柄。

**Tech Stack:** Vue 3、Vitest、Pointer Events、Vite Mock server

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 柱子点击将起止日期设为同一天并立即查询。
- 重合点首次有效水平位移决定本次拖动方向，松开前不得切换另一端。
- 拖动只落在真实日期点，松开才请求统计接口，无位移不请求。
- Mock 日期点必须来源于非零问题数据，不生成空柱子。
- 保留既有分组口径、日期下拉、非重合双滑块、分区布局和快照行为。

---

### Task 1: 重合拖动纯函数

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/replay/replayCompletionTimeline.js`
- Create: `/Users/java/axon-link-frontend/src/components/replay/replayCompletionTimeline.spec.js`

**Interfaces:**
- Produces: `timelineIndexFromClientX(clientX, left, width, pointCount): number`
- Produces: `overlapDragRange(originIndex, nextIndex, edge): { edge, startIndex, endIndex }`

- [x] **Step 1: 写失败测试**

覆盖坐标吸附到首点、中间点、末点；覆盖起点 3 向左到 1 返回开始边界、向右到 5 返回结束边界；覆盖已锁定左端后拖回右侧只收拢到起点、不切换为右端。

- [x] **Step 2: 运行测试确认 RED**

Run: `cd /Users/java/axon-link-frontend && npm test -- replayCompletionTimeline.spec.js`

Expected: 模块尚不存在而失败。

- [x] **Step 3: 实现纯函数并确认 GREEN**

坐标按轨道宽度映射到 `0..pointCount-1` 后四舍五入并钳制；`edge` 为空时由 `nextIndex < originIndex` 选择 `start`，大于时选择 `end`，相等保持空。锁定后只更新对应边界。

Run: `cd /Users/java/axon-link-frontend && npm test -- replayCompletionTimeline.spec.js`

Expected: 纯函数测试通过。

### Task 2: 日期列点击与共享拖动点

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Consumes: Task 1 的两个纯函数。
- Produces: 日期列点击/Enter/空格单日直查；`timeline-overlap-handle` 重合点双向拖动。

- [x] **Step 1: 写组件失败测试**

断言点击 `2026-08-22` 日期列后下拉起止均为该日并请求该日；键盘 Enter 执行同一行为。将起止设为同一真实点后，模拟共享手柄从中点向左拖，断言开始日期减少且松开只请求一次；重新重合后向右拖，断言结束日期增加且松开只请求一次；无位移松开不请求。

- [x] **Step 2: 运行组件测试确认 RED**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayPlannedCompletionModal.spec.js`

Expected: 日期列没有点击行为且重合时仍由结束滑块独占指针。

- [x] **Step 3: 实现日期列直查**

为日期列增加 `role=button`、`tabindex=0`、描述性 `aria-label` 和 click/Enter/Space 事件；事件调用 `synchronizeRange(date,date)` 后立即 `loadDashboard(date,date)`，加载中忽略重复操作。

- [x] **Step 4: 实现共享拖动点**

新增 `rangesOverlap`、共享手柄位置、pointerdown/move/up 生命周期。重合时隐藏两个原生 range；首次跨过真实日期点后锁定 start/end，移动时只同步 UI，pointerup 时请求一次。关闭模态框和组件卸载时移除窗口监听器。

- [x] **Step 5: 运行组件测试确认 GREEN**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayPlannedCompletionModal.spec.js`

Expected: 组件聚焦测试全部通过。

### Task 3: 跨月 Mock 与完整验收

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`
- Modify: `/Users/java/obsidian/log.md`

**Interfaces:**
- Produces: `2026-08-01` 至 `2026-09-05` 共 36 个真实非零日期点，默认当天 `2026-08-31`。

- [x] **Step 1: 写 Mock 失败测试**

断言日期点共 36 个、全部数量大于 0、包含 `2026-08-31` 和 `2026-09-01` 至 `2026-09-05`；默认 dashboard 只统计 `2026-08-31` 且数量大于 0。

- [x] **Step 2: 运行 Mock 测试确认 RED**

Run: `cd /Users/java/axon-link-frontend && npm test -- mock/daoIndexMockServer.spec.js`

Expected: 当前只有 30 个日期且 8 月 31 日无数据。

- [x] **Step 3: 扩展 Mock 并运行完整验证**

将 `REPLAY_COMPLETION_DATES` 扩展为 8 月 1 日至 9 月 5 日，复用现有问题生成器分布非零数据。

Run: `cd /Users/java/axon-link-frontend && npm test`

Run: `cd /Users/java/axon-link-frontend && npm run build`

Expected: 前端全量测试通过，生产资源写入后端 static。

- [x] **Step 4: 浏览器验收**

在 `http://127.0.0.1:5174/#replay-issues` 验证默认 8 月 31 日单点；点击 9 月 5 日柱子立即查询；重合点分别向左、向右拖动均成功；时间轴存在 36 根非零柱子。将结果写入实施日志。
