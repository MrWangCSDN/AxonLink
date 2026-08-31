# 计划完成情况默认系统当天 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将“计划完成情况”首次打开的默认统计范围改为服务端系统当天到当天，并在当天无计划验证数据时展示当天空结果而不回退历史日期。

**Architecture:** 后端注入的 `Clock` 是默认日期唯一来源，日期点接口与无参数统计接口复用同一个当天值。前端保留真实日期时间轴，同时为不在真实日期点中的默认当天提供只用于查询的下拉选项；Mock 使用固定当天保证验收可重复。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate、JUnit 5、Vue 3、Vitest、Vite Mock server

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` 与 `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- 默认日期只能来自服务端 `Clock`，前端不得用浏览器日期自行计算。
- 当天无数据时不得回退到历史日期，不得向真实时间轴补充零数量柱子。
- 用户选择真实日期范围、滑块逐点移动、分组口径和上下分区行为保持不变。
- 当前后端和前端工作区含用户未提交改动；不清理、不重置、不提交无关文件。

---

### Task 1: 后端默认日期契约

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionDatePointsResponse.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `datePoints().defaultStartDate/defaultEndDate = LocalDate.now(clock)`。
- Produces: `dashboard(null, null)` 返回当天到当天；当天无记录时汇总为 0。

- [x] **Step 1: 写失败测试**

将固定时钟设为 `2026-08-27`，断言日期点默认起止均为 `2026-08-27`；断言无参数 dashboard 的有效起止均为 `2026-08-27` 且计划总数为 0；空库日期点也返回当天而非 `null`。控制器测试同步断言当天默认值。

- [x] **Step 2: 运行测试确认 RED**

Run: `./mvnw -Dtest=ReplayIssueCompletionStatsServiceTest,ReplayIssueControllerTest test`

Expected: 旧“最新三个日期”断言与新当天断言冲突并失败。

- [x] **Step 3: 最小实现**

在 `datePoints()` 中无论日期点是否为空都返回 `LocalDate.now(clock)` 作为两个默认值；在 `dashboard()` 无边界参数时直接聚合当天到当天，且空库返回当天有效范围。保留显式真实日期范围的吸附与越界校验。

- [x] **Step 4: 运行聚焦测试确认 GREEN**

Run: `./mvnw -Dtest=ReplayIssueCompletionStatsServiceTest,ReplayIssueControllerTest test`

Expected: 本任务相关测试通过。

### Task 2: 前端当天空范围展示

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Consumes: 日期点响应中的 `defaultStartDate/defaultEndDate`。
- Produces: 当默认当天不在 `datePoints` 中时，下拉框仍显示当天，统计展示空结果，时间轴不增加零数量柱子。

- [x] **Step 1: 写失败测试**

增加“默认当天存在于真实日期点”和“默认当天不在真实日期点”两个用例：首次请求均使用当天到当天；后者断言时间轴列数不变、下拉额外包含当天、没有任何柱子被错误选中，并正常展示 `plannedTotal=0`。

- [x] **Step 2: 运行测试确认 RED**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayPlannedCompletionModal.spec.js`

Expected: 旧组件会让不在选项中的默认当天显示为空或跳过 dashboard。

- [x] **Step 3: 最小实现**

新增只服务于日期下拉的计算选项集合，将默认当天与真实日期点合并去重；时间轴仍只遍历 `datePoints`。允许没有真实日期点时继续加载当天 dashboard；虚拟当天范围使用 `-1` 索引并隐藏滑块选区，真实日期选择继续沿用原逐点逻辑。

- [x] **Step 4: 运行聚焦测试确认 GREEN**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayPlannedCompletionModal.spec.js`

Expected: 聚焦组件测试通过。

### Task 3: Mock 与完整验证

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Test: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`
- Modify: `/Users/java/obsidian/log.md`

**Interfaces:**
- Produces: Mock 日期点和无参数 dashboard 默认使用固定 `2026-08-31` 当天。

- [x] **Step 1: 写 Mock 失败测试**

断言 `/stats/planned-completion/date-points` 返回默认起止 `2026-08-31`，无参数 dashboard 的有效起止同为 `2026-08-31`，且真实日期点仍只有 30 个非零日期。

- [x] **Step 2: 运行测试确认 RED**

Run: `cd /Users/java/axon-link-frontend && npm test -- mock/daoIndexMockServer.spec.js`

Expected: 旧 Mock 仍返回最新三个日期点首尾。

- [x] **Step 3: 修改 Mock 并运行全量验证**

用常量 `REPLAY_COMPLETION_TODAY = '2026-08-31'` 统一默认范围和完成分类当前日；显式日期范围逻辑保持原样。

Run: `cd /Users/java/axon-link-frontend && npm test`

Run: `cd /Users/java/axon-link-frontend && npm run build`

Expected: 前端全量测试及生产构建通过，构建产物直接写入后端 `src/main/resources/static`。

- [x] **Step 4: 浏览器验收并记录**

打开 `http://127.0.0.1:5174/#replay-issues`，进入“计划完成情况”，确认默认起止日期都是 `2026-08-31`、仅查询当天、时间轴仍为 30 个真实非零日期点。将验证结果追加到 `/Users/java/obsidian/log.md`。
