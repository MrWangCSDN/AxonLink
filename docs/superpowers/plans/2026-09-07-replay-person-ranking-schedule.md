# 开发负责人三状态整体排期 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在“各组开发负责人问题排名”中增加 `排期（新建+打开+重新打开） / 已排 / 总数` 单列，并在点击后于当前弹窗右侧展示该负责人的日期排期汇总和未排期数量。

**Architecture:** 负责人排名 SQL 同次计算三状态总数与已排期数，避免逐行请求；点击单元格后调用独立轻量详情接口，按当前 `replayType + groupBy + groupName + developer` 聚合计划日期。前端在现有负责人排名模态框内部增加可关闭侧栏，不创建嵌套模态框。

**Tech Stack:** Java 17、Spring MVC、Spring JDBC、JUnit 5、Vue 3、Vitest、Vite Mock Server。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md:500`；`/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md:535`

## Global Constraints

- 三状态总数严格等于 `newCount + openCount + reopenedCount`。
- 已排期数只统计上述三状态且 `planned_completion_date IS NOT NULL` 的问题。
- 统计跟随负责人弹窗的 `replayType`、`groupBy` 和当前组，不继承主表页头筛选。
- 多人负责人组合不拆分；空负责人继续归入“未匹配负责人”。
- 日期升序返回；日期数量之和等于已排期数；未排期数等于总数减已排期数。
- 当前需求不下钻具体问题清单，不增加数据库字段或迁移。
- 当前工作区已有其他未提交改动；不重置、不清理、不提交。

---

### Task 1: 负责人排名增加排期计数

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePersonRanking.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java:1018`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java:700`

**Interfaces:**
- Produces: `ReplayIssuePersonRanking.schedulePlannedCount(): long`
- Produces: `ReplayIssuePersonRanking.scheduleTotalCount(): long`

- [ ] **Step 1: 写失败 DAO 测试**

在现有负责人排名测试数据中覆盖新建、打开、重新打开三种状态，并分别设置有日期、无日期记录；断言同一负责人：

```java
assertEquals(ranking.newCount() + ranking.openCount() + ranking.reopenedCount(),
        ranking.scheduleTotalCount());
assertEquals(2L, ranking.schedulePlannedCount());
```

同时加入延后修复且有计划日期的数据，断言它不进入两个排期计数；分别以 `ReplayIssueReplayType.ALL/DZ/QUERY` 与 `domain/issueDomain` 查询，确认口径继承。

- [ ] **Step 2: 运行测试确认红灯**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDaoTest#personIssueRankings* test`

Expected: 编译失败或断言失败，因为 DTO 与 SQL 尚无两个排期字段。

- [ ] **Step 3: 最小实现排名计数**

在 `ReplayIssuePersonRanking` 的 `reopenedCount` 后增加：

```java
long schedulePlannedCount,
long scheduleTotalCount,
```

在 `personIssueRankings` SQL 中增加：

```sql
SUM(CASE WHEN i.issue_status IN ('新建','打开','重新打开')
          AND i.planned_completion_date IS NOT NULL THEN 1 ELSE 0 END) AS schedule_planned_count,
SUM(CASE WHEN i.issue_status IN ('新建','打开','重新打开')
         THEN 1 ELSE 0 END) AS schedule_total_count,
```

并按 DTO 字段顺序映射两个别名。

- [ ] **Step 4: 运行 DAO 测试确认绿灯**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDaoTest test`

Expected: PASS。

---

### Task 2: 新增负责人排期详情接口

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueScheduleDateCount.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePersonSchedule.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java:572`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java:1300`

**Interfaces:**
- Consumes: existing `ReplayIssueReplayType.parse(String)` and DAO group/replay predicates.
- Produces: `ReplayIssueDao.personSchedule(String groupBy, ReplayIssueReplayType replayType, String groupName, String developer)`.
- Produces: `GET /api/ai/parallel-replay/issues/stats/person-ranking/schedule`.

- [ ] **Step 1: 写失败 DAO 详情测试**

断言目标负责人只聚合新建、打开、重新打开，日期升序，空日期计入未排期：

```java
ReplayIssuePersonSchedule result = dao.personSchedule(
        "issueDomain", ReplayIssueReplayType.ALL, "存款组", "张三(c-zhangs3)");
assertEquals(4L, result.scheduleTotalCount());
assertEquals(3L, result.schedulePlannedCount());
assertEquals(1L, result.scheduleUnplannedCount());
assertEquals(List.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)),
        result.dateCounts().stream().map(ReplayIssueScheduleDateCount::plannedCompletionDate).toList());
assertEquals(result.schedulePlannedCount(),
        result.dateCounts().stream().mapToLong(ReplayIssueScheduleDateCount::count).sum());
```

再断言不同 `replayType`、不同 `groupBy` 和“未匹配负责人”不会串数据。

- [ ] **Step 2: 运行 DAO 测试确认红灯**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDaoTest#personSchedule* test`

Expected: 编译失败，因为详情 DTO 和 DAO 方法不存在。

- [ ] **Step 3: 实现详情 DTO 与单次聚合查询**

DTO 结构固定为：

```java
public record ReplayIssueScheduleDateCount(
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate plannedCompletionDate,
        long count) {}

public record ReplayIssuePersonSchedule(
        String groupName,
        String developer,
        long scheduleTotalCount,
        long schedulePlannedCount,
        long scheduleUnplannedCount,
        List<ReplayIssueScheduleDateCount> dateCounts) {}
```

DAO 先校验 `groupName`、`developer` 非空，再使用与排名完全相同的分组表达式、负责人表达式和回放类型 `EXISTS` 谓词；限定 `issue_status IN ('新建','打开','重新打开')`，按 `planned_completion_date` 分组并升序。空日期单独累加为未排期，非空日期生成 `dateCounts`；总数、已排期、未排期在 Java 中从同一结果集求和，避免多次查询口径漂移。

- [ ] **Step 4: 写失败 Controller 测试**

请求完整参数并断言 JSON：

```java
mvc.perform(get("/api/ai/parallel-replay/issues/stats/person-ranking/schedule")
        .param("replayType", "ALL")
        .param("groupBy", "issueDomain")
        .param("groupName", "存款组")
        .param("developer", "张三(c-zhangs3)"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.scheduleTotalCount").value(4))
    .andExpect(jsonPath("$.data.dateCounts[0].plannedCompletionDate").value("2026-07-01"));
```

缺失/空 `groupName` 或 `developer` 断言 HTTP 400；非法 `replayType`、`groupBy` 沿用现有中文错误。

- [ ] **Step 5: 实现 Controller 路由并验证**

新增 `@GetMapping("/stats/person-ranking/schedule")`，解析参数并调用 DAO；捕获 `IllegalArgumentException` 返回现有 `error(HttpStatus.BAD_REQUEST, message)`。

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest test`

Expected: PASS。

---

### Task 3: 前端 API、排名列与详情侧栏

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js:112`
- Test: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue:150`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js:1400`

**Interfaces:**
- Consumes: ranking row fields `schedulePlannedCount` and `scheduleTotalCount`.
- Produces: `getReplayIssuePersonSchedule({ replayType, groupBy, groupName, developer })`.
- Consumes: Task 2 response `ReplayIssuePersonSchedule`.

- [ ] **Step 1: 写失败 API 测试**

断言方法生成并编码以下查询：

```text
/stats/person-ranking/schedule?replayType=DZ&groupBy=issueDomain&groupName=存款组&developer=张三(c-zhangs3)
```

Run: `npx vitest run src/api/replayIssues.spec.js`

Expected: FAIL，因为导出函数不存在。

- [ ] **Step 2: 实现 API 包装并确认绿灯**

```js
export function getReplayIssuePersonSchedule(params = {}) {
  const query = queryString(params)
  return request(`${PREFIX}/stats/person-ranking/schedule${query ? `?${query}` : ''}`)
}
```

Run: `npx vitest run src/api/replayIssues.spec.js`

Expected: PASS。

- [ ] **Step 3: 写失败组件测试**

覆盖以下行为：

```js
expect(wrapper.get('[data-testid="person-schedule-header"]').text())
  .toContain('排期（新建+打开+重新打开）')
expect(wrapper.get('[data-testid="person-schedule-张三(c-zhangs3)"]').text()).toBe('2 / 3')
await wrapper.get('[data-testid="person-schedule-张三(c-zhangs3)"]').trigger('click')
expect(getReplayIssuePersonSchedule).toHaveBeenCalledWith({
  replayType: 'ALL', groupBy: 'issueDomain', groupName: '存款组', developer: '张三(c-zhangs3)',
})
expect(wrapper.get('[data-testid="person-schedule-panel"]').text())
  .toContain('2026-07-01').toContain('15个').toContain('未排期').toContain('20')
```

另测 `0 / 0` 不可点击；切换负责人替换面板；切换组、`replayType`、`groupBy` 或关闭排名窗口时关闭面板；详情失败只在侧栏显示；复制 TSV 包含新表头与 `2 / 3`。

Run: `npx vitest run src/components/replay/ReplayIssuePage.spec.js`

Expected: FAIL，因为列、状态和侧栏尚不存在。

- [ ] **Step 4: 最小实现排名列与侧栏**

在 `personRankingColumns` 的 `developer` 后增加专用列：

```js
{ key: 'schedule', label: '排期（新建+打开+重新打开）\n已排 / 总数', segment: 'schedule' }
```

模板对该列使用按钮渲染 `${row.schedulePlannedCount} / ${row.scheduleTotalCount}`，其他列保持通用渲染。新增当前负责人、loading、error、detail 状态；点击后按当前弹窗会话参数请求详情。在排名模态框内容区右侧渲染侧栏，显示总数、已排、未排及日期列表；组别和口径切换入口先清空侧栏再刷新数据。

CSS 保持现有暖橙/绿色分段，排期列使用独立蓝色强调；表头通过 `white-space: pre-line` 显示两行，侧栏桌面宽度约 `320px`，窄屏改为表格下方，不创建第二层遮罩。

- [ ] **Step 5: 运行组件测试确认绿灯**

Run: `npx vitest run src/components/replay/ReplayIssuePage.spec.js`

Expected: PASS。

---

### Task 4: Mock 对齐与整体验证

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Test: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`

**Interfaces:**
- Produces: Mock `/stats/person-ranking` 两个新字段。
- Produces: Mock `/stats/person-ranking/schedule` 与生产接口同结构。

- [ ] **Step 1: 写失败 Mock 测试**

断言排名每行 `scheduleTotalCount === newCount + openCount + reopenedCount`、`schedulePlannedCount <= scheduleTotalCount`；详情请求返回日期升序，且 `planned + unplanned === total`。

Run: `npx vitest run mock/daoIndexMockServer.spec.js -t 'person ranking schedule'`

Expected: FAIL，因为 Mock 未返回新字段和路由。

- [ ] **Step 2: 实现 Mock 数据与路由**

排名数据从现有 Mock 问题按三状态和计划日期实时聚合；详情路由复用相同 `replayType`、`groupBy`、负责人匹配规则，不硬编码与列表无法对账的数字。

- [ ] **Step 3: 运行聚焦回归**

Run:

```bash
cd /Users/java/axon-link-server
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest test
cd /Users/java/axon-link-frontend
npx vitest run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js mock/daoIndexMockServer.spec.js
```

Expected: 新增与现有聚焦测试 PASS；若 Mock 全文件仅出现已知日期敏感基线失败，记录但不修改无关行为。

- [ ] **Step 4: 构建与打包**

Run:

```bash
cd /Users/java/axon-link-frontend
npm run build
cd /Users/java/axon-link-server
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -DskipTests package
git diff --check
git -C /Users/java/axon-link-frontend diff --check
```

Expected: Vite 构建、Maven 打包和两个差异检查均退出码 0；前端静态文件写入后端 `src/main/resources/static/`。

---

## Plan Self-Review

- 设计中的排名首批计数、按需详情、筛选继承、未排期、日期排序、复制和响应式布局均有对应任务。
- DTO 字段名在后端、API 文档、前端和 Mock 中统一为 `schedulePlannedCount`、`scheduleTotalCount`、`scheduleUnplannedCount`、`dateCounts`。
- 无数据库迁移、无问题明细下钻、无独立新模态框，符合当前范围。
- 实施严格执行红灯 → 最小实现 → 绿灯，不提交当前脏工作区。

---

### Task 5: 精简排期侧栏并压缩排名表列宽

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

- [ ] **Step 1: 写失败组件测试**

点击非零排期单元格后，断言侧栏仍展示负责人和日期明细，但不再出现“三状态总数”“已排期”“未排期”三块汇总文案。

- [ ] **Step 2: 运行测试确认红灯**

Run: `npx vitest run src/components/replay/ReplayIssuePage.spec.js -t 'opens person schedule detail'`

Expected: FAIL，因为现有侧栏仍渲染三块汇总卡片。

- [ ] **Step 3: 最小实现布局调整**

删除侧栏汇总卡片模板和对应无用样式；人员排名表将排名、分组、开发负责人列分别收窄至约 `52px`、`76px`、`180px`，排期列扩大至约 `190px`，其余统计列收窄至约 `72px`，侧栏保持 `320px`。

- [ ] **Step 4: 运行组件测试和生产构建**

Run: `npx vitest run src/components/replay/ReplayIssuePage.spec.js && npm run build`

Expected: PASS，且构建退出码为 0。
