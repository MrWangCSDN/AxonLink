# 回放交易类型联动切换 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在回放问题清单、统计卡片和三个统计弹窗中增加“全部 / 动账 / 查询”交易类型口径，并保证外层基线优先、内层状态隔离以及计划完成日期范围稳定。

**Architecture:** 后端以统一枚举 `ReplayIssueReplayType` 解析 `replayType=ALL|DZ|QUERY`，并通过出现批次关联表上的 `EXISTS` 谓词按问题去重过滤；列表、候选、导出及全部统计接口共享该口径。前端用外层 `replayType` 作为最高优先级筛选基线，两个汇总弹窗和计划完成弹窗各自复制该状态并在弹窗生命周期内独立修改；具体表头筛选只叠加到主清单，不反向改变按钮。

**Tech Stack:** Java 17、Spring Boot、Spring MVC、JdbcTemplate、JUnit 5、H2；Vue 3 Composition API、Vite、Vitest、Vue Test Utils；Node mock server。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`、`/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- `replayType` 只允许 `ALL`、`DZ`、`QUERY`，缺省为 `ALL`；非法值返回 HTTP 400，消息为“回放交易类型不合法”。
- `DZ` 使用 `dii_replay_issue_occurrence_batch.batch_name LIKE 'DZ%'` 的 `EXISTS`，`QUERY` 使用 `LIKE 'RPT%'`；一个问题即使存在多个同族批次也只能计一次。
- `ALL` 包含所有历史问题，不以批次前缀限制；同时有 RPT 和 DZ 的问题在 ALL 中只计一次。
- 外层交易类型默认 `ALL`，每次点击（包括重复点击当前项）都清空主清单表头筛选、优先任务状态、出现笔数排序并回第一页，但不改变“领域 / 问题所属领域”。
- “重置筛选条件”保留外层交易类型基线；用户手工修改“出现批次”筛选只影响主清单，不反向改变交易类型按钮。
- 外层交易类型和分组口径会在打开弹窗时传入；弹窗内部切换不回写外层，关闭后重开重新继承外层。
- 计划完成情况的全量日期轴不随交易类型改变；类型/分组切换不得改变用户已选日期范围和滚动位置。
- 计划完成情况首次打开默认使用服务端系统日期的前一天、当天、后一天，三个日期即使问题数为 0 也要保留。
- 当前前后端文件已有用户未提交修改；实施时只做增量补丁，不 reset、checkout、清理或覆盖既有改动，也不自动提交、合并或推送。
- 不引入新的前端或后端依赖。

---

## File Map

### Backend

- Create `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueReplayType.java`: 唯一的交易类型解析和批次族语义入口。
- Modify `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`: 主清单查询对象携带 `ReplayIssueReplayType replayType`，旧构造器继续默认 ALL。
- Modify `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`: 九类 GET 接口接收并校验 `replayType`。
- Modify `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`: 列表/计数/候选/导出及三类汇总统计复用 replay type 谓词。
- Modify `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDao.java`: 日期点保持全量，汇总和下钻按 replay type 过滤。
- Modify `src/main/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsService.java`: 默认三天范围、类型传递、日期轴零数量点补齐。
- Test `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`.
- Test `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`.
- Test `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDaoTest.java`.
- Test `src/test/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsServiceTest.java`.

### Frontend and Mock

- Modify `/Users/java/axon-link-frontend/src/api/replayIssues.js`: 所有相关请求透传 `replayType`，日期点接口接受 params。
- Test `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`.
- Modify `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`: 外层按钮、基线筛选重置、卡片与弹窗状态继承。
- Test `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`.
- Modify `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`: 内层交易类型切换、日期和滚动位置保持。
- Test `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`.
- Modify `/Users/java/axon-link-frontend/src/components/replay/completionSnapshot.js`: 快照标题和文件名增加交易类型。
- Test `/Users/java/axon-link-frontend/src/components/replay/completionSnapshot.spec.js`.
- Modify `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`: mock 数据及全部接口与真实后端保持契约一致。

---

### Task 1: 统一后端交易类型值对象

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueReplayType.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `ReplayIssueReplayType.parse(String): ReplayIssueReplayType`；`batchPrefix(): String|null`；`ReplayIssueQuery.replayType(): ReplayIssueReplayType`。
- Compatibility: 所有既有 `ReplayIssueQuery` 重载构造器必须继续编译并把类型设为 `ALL`。

- [x] **Step 1: 写枚举解析失败测试和旧构造器兼容测试**

```java
@Test
void replayTypeDefaultsToAllAndRejectsUnknownValue() {
    assertEquals(ReplayIssueReplayType.ALL, ReplayIssueReplayType.parse(null));
    assertEquals(ReplayIssueReplayType.ALL, ReplayIssueReplayType.parse(" "));
    assertEquals(ReplayIssueReplayType.DZ, ReplayIssueReplayType.parse("dz"));
    assertEquals(ReplayIssueReplayType.QUERY, ReplayIssueReplayType.parse("QUERY"));
    assertEquals(ReplayIssueReplayType.ALL,
            new ReplayIssueQuery(50, 0, null, null, null, null, null).replayType());
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ReplayIssueReplayType.parse("OTHER"));
    assertEquals("回放交易类型不合法", error.getMessage());
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ReplayIssueDaoTest#replayTypeDefaultsToAllAndRejectsUnknownValue test`

Expected: FAIL，提示 `ReplayIssueReplayType` 或 `replayType()` 尚不存在。

- [x] **Step 3: 实现枚举和查询对象默认值**

```java
public enum ReplayIssueReplayType {
    ALL(null), DZ("DZ"), QUERY("RPT");

    private final String batchPrefix;

    ReplayIssueReplayType(String batchPrefix) { this.batchPrefix = batchPrefix; }
    public String batchPrefix() { return batchPrefix; }

    public static ReplayIssueReplayType parse(String value) {
        if (value == null || value.isBlank()) return ALL;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("回放交易类型不合法");
        }
    }
}
```

在 `ReplayIssueQuery` 主 record 参数末尾增加 `ReplayIssueReplayType replayType`；新增一个与当前完整参数签名相同但不带 replay type 的兼容构造器，并令所有旧重载最终委托到 `ReplayIssueReplayType.ALL`。主构造器 compact normalization：`replayType = replayType == null ? ReplayIssueReplayType.ALL : replayType;`。

- [x] **Step 4: 运行测试和编译确认通过**

Run: `mvn -DskipTests compile && mvn -Dtest=ReplayIssueDaoTest#replayTypeDefaultsToAllAndRejectsUnknownValue test`

Expected: BUILD SUCCESS，旧调用点无需改动。

---

### Task 2: 主清单、候选、计数和导出支持交易类型基线

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `ReplayIssueReplayType.parse(String)` and `ReplayIssueQuery.replayType()` from Task 1.
- Produces: list/export/header option/header count endpoints accept `@RequestParam(required=false) String replayType`.
- SQL helper: `appendReplayType(StringBuilder sql, List<Object> args, ReplayIssueReplayType replayType)`.

- [x] **Step 1: 写 DAO 去重和叠加筛选测试**

构造四个问题：RPT-only、DZ-only、RPT+DZ、无标准前缀历史批次；为双族问题写入多个 occurrence batch。断言：

```java
assertEquals(Set.of("RPT-only", "both"), issueKeys(dao.list(query(QUERY))));
assertEquals(Set.of("DZ-only", "both"), issueKeys(dao.list(query(DZ))));
assertEquals(Set.of("RPT-only", "DZ-only", "both", "legacy"), issueKeys(dao.list(query(ALL))));
assertEquals(2, dao.count(query(QUERY)));
assertEquals(List.of("RPT20260901"),
        dao.headerFilterValues("occurrenceBatch", query(QUERY), null));
```

再给 QUERY query 叠加 `occurrenceBatches=["RPT20260901"]`，确认只取两者交集；同一问题多个 RPT 批次仍只返回一行。

- [x] **Step 2: 运行 DAO 测试确认失败**

Run: `mvn -Dtest=ReplayIssueDaoTest#filtersListCountOptionsAndExportByReplayType test`

Expected: FAIL，QUERY/DZ 尚未限制批次族。

- [x] **Step 3: 在共享条件拼装中加入 EXISTS**

```java
private static void appendReplayType(StringBuilder sql, List<Object> args,
                                     ReplayIssueReplayType replayType) {
    if (replayType == null || replayType == ReplayIssueReplayType.ALL) return;
    sql.append(" AND EXISTS (SELECT 1 FROM dii_replay_issue_occurrence_batch rt")
       .append(" WHERE rt.replay_issue_id=i.id AND rt.batch_name LIKE ?)");
    args.add(replayType.batchPrefix() + "%");
}
```

在 `appendFilters(...)` 内、具体 `appendOccurrenceBatches(...)` 之前调用，使类型基线与具体批次取交集。header candidate 查询排除当前列时只能排除 `occurrenceBatches`，不能排除 `replayType`。导出继续走同一 `ReplayIssueQuery`。

- [x] **Step 4: 写 Controller 参数与 400 测试**

```java
mockMvc.perform(get("/ai/parallel-replay/issues").param("replayType", "DZ"))
        .andExpect(status().isOk());
mockMvc.perform(get("/ai/parallel-replay/issues/header-filter-option-counts")
        .param("field", "occurrenceBatch").param("replayType", "QUERY"))
        .andExpect(status().isOk());
mockMvc.perform(get("/ai/parallel-replay/issues/export").param("replayType", "OTHER"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msg").value("回放交易类型不合法"));
```

- [x] **Step 5: 在四类接口构建 query 时统一解析参数**

为 `list`、`headerFilterOptions`、`headerFilterOptionCounts`、`export` 增加同名字符串参数，在构造 `ReplayIssueQuery` 时最后传入 `ReplayIssueReplayType.parse(replayType)`。沿用项目现有 `IllegalArgumentException -> 400` 返回结构，不新增第二套异常处理。

- [x] **Step 6: 运行聚焦测试**

Run: `mvn -Dtest=ReplayIssueDaoTest#filtersListCountOptionsAndExportByReplayType,ReplayIssueControllerTest#acceptsReplayTypeAcrossListOptionsAndExport,ReplayIssueControllerTest#rejectsInvalidReplayType test`

Expected: BUILD SUCCESS。

---

### Task 3: 卡片、各组问题数和负责人排名支持交易类型

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `stats(String groupBy, ReplayIssueReplayType replayType)`、`groupIssueSummaries(String groupBy, ReplayIssueReplayType replayType)`、`personIssueRankings(String groupBy, ReplayIssueReplayType replayType)`。
- Compatibility: 现有无 replay type 重载继续委托 `ALL`。

- [x] **Step 1: 写三类统计的 RPT/DZ/ALL 对账测试**

```java
Map<String, Object> queryStats = dao.stats("issueDomain", QUERY);
assertEquals(2L, ((Number) queryStats.get("total")).longValue());
assertEquals(2L, dao.groupIssueSummaries("issueDomain", QUERY).stream()
        .mapToLong(ReplayIssueGroupSummary::total).sum());
assertEquals(2L, dao.personIssueRankings("issueDomain", QUERY).stream()
        .mapToLong(ReplayIssuePersonRanking::total).sum());
```

对 DZ 和 ALL 做同样断言，并验证双族问题在每个统计口径内都只计一次。

- [x] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ReplayIssueDaoTest#filtersAllSummaryStatisticsByReplayType test`

Expected: FAIL，统计方法尚无 replay type 参数。

- [x] **Step 3: 为三个统计 SQL 注入同一 EXISTS 片段**

把统计 SQL 从固定字符串调整为 `StringBuilder + args`，在 `WHERE` 后调用 Task 2 的 replay type helper。不得 join occurrence 表，避免多批次放大总数。保留原有状态字段顺序、负责人动态匹配和 groupBy 表达式。

- [x] **Step 4: Controller 三接口解析参数**

```java
@RequestParam(required = false) String replayType
```

分别传给 DAO 新重载；非法值沿用统一 400。

- [x] **Step 5: 运行 DAO 和 Controller 聚焦测试**

Run: `mvn -Dtest=ReplayIssueDaoTest#filtersAllSummaryStatisticsByReplayType,ReplayIssueControllerTest#filtersAllStatisticsEndpointsByReplayType test`

Expected: BUILD SUCCESS。

---

### Task 4: 计划完成情况支持类型口径及默认三天

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces DAO: `aggregate(startDate, endDate, today, groupBy, replayType)` and `findIssues(startDate, endDate, today, groupBy, replayType, groupName, matchedDeveloper, category, limit, offset)`.
- Produces service: `datePoints()` remains global; `dashboard(startDate, endDate, groupBy, replayType)` and `issues(..., groupBy, replayType, ...)`.
- API: date-points accepts but intentionally does not filter by `replayType`; dashboard and issues do filter.

- [x] **Step 1: 写 DAO 类型过滤测试**

为同一日期建立 RPT-only、DZ-only、双族和历史问题，断言 QUERY/DZ 的 aggregate 与 findIssues 都符合 2 条且无重复；`findDatePoints()` 仍包含全量问题数。

- [x] **Step 2: 运行 DAO 测试确认失败**

Run: `mvn -Dtest=ReplayIssueCompletionStatsDaoTest#filtersDashboardAndDrilldownWithoutFilteringGlobalDatePoints test`

Expected: FAIL，新签名尚不存在。

- [x] **Step 3: 将类型 EXISTS 放入共享 classified source**

```java
private static ClassifiedSource classifiedSource(String groupBy, ReplayIssueReplayType replayType) {
    String replayPredicate = replayType == ALL ? "" : """
         AND EXISTS (SELECT 1 FROM dii_replay_issue_occurrence_batch rt
                      WHERE rt.replay_issue_id=i.id AND rt.batch_name LIKE ?)
         """;
    return new ClassifiedSource(CLASSIFIED_SOURCE_TEMPLATE.formatted(groupExpression(groupBy), replayPredicate),
            replayType == ALL ? List.of() : List.of(replayType.batchPrefix() + "%"));
}
```

`aggregate` 和 `findIssues` 使用相同 source 与参数顺序；`findDatePoints()` 保持不带 replay type。

- [x] **Step 4: 写服务默认三天、零点保留和类型切换测试**

固定 `Clock` 为 `2026-09-02`，断言：

```java
assertEquals(LocalDate.parse("2026-09-01"), response.defaultStartDate());
assertEquals(LocalDate.parse("2026-09-03"), response.defaultEndDate());
assertEquals(List.of("2026-09-01", "2026-09-02", "2026-09-03"),
        response.points().stream().map(p -> p.date().toString()).toList());
assertEquals(List.of(0L, 0L, 0L),
        response.points().stream().map(ReplayIssueCompletionDatePoint::plannedCount).toList());
```

当数据库另有远端日期时，全量日期按正序合并，默认三天仍存在。dashboard 缺省范围取三天；传 QUERY/DZ 时汇总变化但 start/end 不变。

- [x] **Step 5: 实现全量日期轴合并和默认范围**

```java
LocalDate today = LocalDate.now(clock);
LocalDate defaultStart = today.minusDays(1);
LocalDate defaultEnd = today.plusDays(1);
Map<LocalDate, Long> counts = dao.findDatePoints().stream().collect(...);
for (LocalDate date = defaultStart; !date.isAfter(defaultEnd); date = date.plusDays(1)) {
    counts.putIfAbsent(date, 0L);
}
```

`datePoints()` 返回合并后的正序 points 和上述 defaultStart/defaultEnd。`normalize` 明确接受默认三天，即使它超出数据库原始最早/最晚日期；用户显式选择仍必须来自响应日期点集合且 start <= end。

- [x] **Step 6: Controller 计划完成三接口传递类型**

`date-points` 接受并验证 `replayType` 以保持 API 一致，但仍调用全量 `datePoints()`；dashboard/issues 调用 service 新签名。为非法值添加三接口 400 测试。

- [x] **Step 7: 运行计划完成聚焦测试**

Run: `mvn -Dtest=ReplayIssueCompletionStatsDaoTest,ReplayIssueCompletionStatsServiceTest,ReplayIssueControllerTest#supportsReplayTypeForPlannedCompletionEndpoints test`

Expected: BUILD SUCCESS。

---

### Task 5: 前端 API 与 mock 契约对齐

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Test: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`

**Interfaces:**
- Produces: `getReplayCompletionDatePoints(params = {})`，其余既有 API 均通过现有 `queryString` 透传 `replayType`。
- Mock query: `normalizeReplayType(value)` and `matchesReplayType(issue, replayType)`.

- [x] **Step 1: 写 API URL 测试**

```javascript
await getReplayIssueStats({ groupBy: 'issueDomain', replayType: 'DZ' })
expect(fetch).toHaveBeenCalledWith(expect.stringContaining('replayType=DZ'), expect.anything())
await getReplayCompletionDatePoints({ replayType: 'QUERY' })
expect(fetch).toHaveBeenCalledWith(expect.stringContaining('replayType=QUERY'), expect.anything())
```

同时覆盖 group summaries、person ranking、dashboard、issues、list、header counts 和 export。

- [x] **Step 2: 运行 API 测试确认 date-points 失败**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/api/replayIssues.spec.js`

Expected: FAIL，date-points 当前忽略 params。

- [x] **Step 3: 修改 date-points API 并保持其他签名**

```javascript
export function getReplayCompletionDatePoints(params = {}) {
  const query = queryString(params)
  return request(`${PREFIX}/stats/planned-completion/date-points${query ? `?${query}` : ''}`)
}
```

- [x] **Step 4: 为 mock 增加批次族数据和共享过滤器**

至少保留四种记录：RPT-only、DZ-only、RPT+DZ、legacy。过滤器必须检查 issue 的完整 occurrence batch 数组，不能只检查展示字符串：

```javascript
function matchesReplayType(issue, replayType = 'ALL') {
  const batches = replayOccurrenceBatches(issue)
  if (replayType === 'ALL') return true
  const prefix = replayType === 'DZ' ? 'DZ' : 'RPT'
  return batches.some((batch) => batch.startsWith(prefix))
}
```

列表、候选、候选计数、stats、groups、person-ranking、completion dashboard/issues 都先应用此过滤器；completion date points 继续从全量数据构建，并补服务端当前日期前后各一天的零点。

- [x] **Step 5: 运行 API 与 mock 启动冒烟**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/api/replayIssues.spec.js && node --check mock/daoIndexMockServer.js`

Expected: 全部通过且语法检查无输出。

---

### Task 6: 页面外层交易类型基线与筛选优先级

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces refs: `statisticsReplayType = ref('ALL')`、`summaryModalReplayType = ref('ALL')`、`plannedCompletionReplayType = ref('ALL')`。
- Produces actions: `setStatisticsReplayType(type)` and `clearUserFiltersForReplayType()`.
- `filterParams()` and `headerFilterParams()` always include `replayType: statisticsReplayType.value`.

- [x] **Step 1: 写外层按钮展示与请求测试**

挂载后断言“全部”点亮，首次 list/stats 请求均为 ALL。依次点击动账、查询，断言 list/stats 请求携带 DZ/QUERY；按钮位于领域口径按钮之前。

- [x] **Step 2: 写最高优先级重置测试**

先设置多个表头筛选、优先任务、出现笔数降序和 page > 1，再点击当前已激活的交易类型，断言：

```javascript
expect(lastListParams()).toMatchObject({ replayType: 'DZ', offset: 0 })
expect(lastListParams().weeklyTask).toBeUndefined()
expect(lastListParams().affectedTransactionCountOrder).toBeUndefined()
expect(lastListParams().issueStatuses).toBeUndefined()
expect(lastListParams().occurrenceBatches).toBeUndefined()
```

再手工选 RPT 批次，断言按钮仍为 DZ；点击“重置筛选条件”后仍为 DZ 且恢复动态 DZ 基线，而不是保存具体批次数组。

- [x] **Step 3: 运行页面测试确认失败**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/components/replay/ReplayIssuePage.spec.js -t "交易类型"`

Expected: FAIL，按钮和状态尚不存在。

- [x] **Step 4: 实现按钮和外层状态**

```vue
<div class="replay-segmented" aria-label="回放交易类型">
  <button v-for="option in REPLAY_TYPE_OPTIONS" :key="option.value"
    :class="{ 'is-active': statisticsReplayType === option.value }"
    @click="setStatisticsReplayType(option.value)">{{ option.label }}</button>
</div>
```

常量为：

```javascript
const REPLAY_TYPE_OPTIONS = [
  { value: 'ALL', label: '全部' },
  { value: 'DZ', label: '动账' },
  { value: 'QUERY', label: '查询' },
]
```

复用现有 segmented 样式，避免新增视觉体系。

- [x] **Step 5: 实现点击与重置语义**

`setStatisticsReplayType` 不因同值提前 return；每次都设置 type、清空所有 `headerFilters`、关闭筛选浮层、取消优先任务、清除排序、page=1，然后并行刷新 list 和 stats。`resetFilters` 复用清理 helper，但不写 `statisticsReplayType='ALL'`。加载失败时保留选中的类型并展示现有错误提示，避免按钮与请求口径失配。

- [x] **Step 6: 限制出现批次候选但不反向联动**

`headerFilterParams()` 始终带 replayType，因此 mock/后端动态返回当前批次族；应用具体 `occurrenceBatches` 时不修改 `statisticsReplayType`。

- [x] **Step 7: 运行页面聚焦测试**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/components/replay/ReplayIssuePage.spec.js -t "交易类型"`

Expected: 全部通过。

---

### Task 7: 两个汇总弹窗继承外层并保持内部隔离

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `statisticsReplayType` from Task 6.
- Produces: `loadSummaryModal()` sends `{ groupBy: summaryModalGroupBy.value, replayType: summaryModalReplayType.value }`.

- [x] **Step 1: 写打开继承和内层隔离测试**

外层选 DZ，打开各组问题数，断言内层 DZ 点亮且请求 `{groupBy:'issueDomain', replayType:'DZ'}`。内层切 QUERY 后断言外层仍为 DZ；关闭重开后内层恢复 DZ。对负责人排名执行同样测试。

- [x] **Step 2: 写领域与类型双向隔离测试**

外层切按领域后打开弹窗应继承 domain；内层同时切 issueDomain 和 QUERY，不得触发外层 stats 请求。外层之后切 ALL 不应实时改动已打开弹窗，只有重开才继承。

- [x] **Step 3: 运行测试确认失败**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/components/replay/ReplayIssuePage.spec.js -t "弹窗交易类型"`

Expected: FAIL，弹窗尚无交易类型按钮。

- [x] **Step 4: 实现弹窗按钮和独立 loader**

在弹窗 toolbar 中把“全部 / 动账 / 查询”放在分组按钮左侧。`openSummaryModal(type)` 同时复制外层 groupBy 和 replayType；`setSummaryModalReplayType` 只更新弹窗 ref 并重新加载当前弹窗数据。使用现有 request sequence/token 机制丢弃旧响应，防止快速切换时后返回请求覆盖新口径。

- [x] **Step 5: 运行聚焦测试**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/components/replay/ReplayIssuePage.spec.js -t "弹窗交易类型|统计口径"`

Expected: 全部通过。

---

### Task 8: 计划完成弹窗保持日期、滚动位置并更新快照

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/completionSnapshot.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/completionSnapshot.spec.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Modal props: `open: Boolean`, `groupBy: 'domain'|'issueDomain'`, `replayType: 'ALL'|'DZ'|'QUERY'`.
- Modal emits: `close`, `update:groupBy`, `update:replayType`.
- Snapshot: `buildCompletionSnapshotFilename({ replayTypeLabel, groupName, startDate, endDate })` and `createCompletionSnapshotBlob({ replayTypeLabel, group, developers, startDate, endDate }, env)`.

- [x] **Step 1: 写首次打开默认三天测试**

mock date-points 返回 defaultStartDate `2026-09-01`、defaultEndDate `2026-09-03` 和含 0 的三点。断言两个下拉框、时间轴及 dashboard 请求都使用完整三天，而非自动收缩到有数据日期。

- [x] **Step 2: 写类型/分组切换保持日期和滚动位置测试**

用户先选择 `2026-08-31` 至 `2026-09-05` 并将时间轴 `scrollLeft=420`，再切 QUERY 和 issueDomain，断言：

```javascript
expect(getReplayCompletionDashboard).toHaveBeenLastCalledWith({
  startDate: '2026-08-31', endDate: '2026-09-05',
  groupBy: 'issueDomain', replayType: 'QUERY',
})
expect(timeline.scrollLeft).toBe(420)
```

关闭再打开后，日期才恢复默认三天，replayType/groupBy 重新继承外层。

- [x] **Step 3: 运行 Modal 测试确认失败**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js -t "交易类型|默认三天|滚动位置"`

Expected: FAIL，新 prop、按钮和三天规则尚未实现。

- [x] **Step 4: 实现 Modal prop、按钮及稳定刷新**

```javascript
const props = defineProps({
  open: Boolean,
  groupBy: { type: String, default: 'issueDomain' },
  replayType: { type: String, default: 'ALL' },
})
const emit = defineEmits(['close', 'update:groupBy', 'update:replayType'])
```

`initialize()` 仅在 `open false -> true` 时加载 date points 并应用服务端默认范围。groupBy/replayType watcher 调用 `refreshPreservingViewport()`：读取日期和 `scrollLeft`，刷新 dashboard 后在 `nextTick` 恢复滚动位置；不得再次调用 initialize 或 date-points。

- [x] **Step 5: 下钻请求携带当前类型**

`getReplayCompletionIssues` 参数同时包含当前 start/end、groupBy 和 replayType。弹窗内切类型时关闭当前下钻层或按新口径刷新，不能保留旧类型明细；计划采用关闭下钻层，避免上下文混淆。

- [x] **Step 6: 写并实现快照类型测试**

```javascript
expect(buildCompletionSnapshotFilename({
  replayTypeLabel: '动账', groupName: '存款组',
  startDate: '2026-09-01', endDate: '2026-09-03',
})).toBe('计划完成情况-动账-存款组-2026-09-01至2026-09-03.png')
```

画布标题第一行同步包含“计划完成情况 · 动账 · 存款组”，文件名使用安全字符清理后的 label；ALL 显示“全部”。

- [x] **Step 7: 页面父组件继承但不被子组件回写**

打开时 `plannedCompletionReplayType.value = statisticsReplayType.value`。父组件接收 `update:replayType` 只更新 `plannedCompletionReplayType`，不得改 `statisticsReplayType`；关闭时销毁/重置弹窗内部生命周期状态。

- [x] **Step 8: 运行 Modal、快照和页面聚焦测试**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/components/replay/ReplayPlannedCompletionModal.spec.js src/components/replay/completionSnapshot.spec.js src/components/replay/ReplayIssuePage.spec.js`

Expected: 全部通过。

---

### Task 9: 全量验证、生产构建与设计实施记录

**Files:**
- Verify only: `/Users/java/axon-link-server`
- Verify only: `/Users/java/axon-link-frontend`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/_overview.md`
- Modify: `/Users/java/obsidian/index.md`
- Modify: `/Users/java/obsidian/log.md`
- Modify: `/Users/java/axon-link-server/docs/superpowers/plans/2026-09-02-replay-type-statistics-switch.md`

**Interfaces:**
- Consumes: all tasks.
- Produces: verified backend, frontend bundle, and auditable implementation status.

- [ ] **Step 1: 运行后端全量测试**

Run: `cd /Users/java/axon-link-server && mvn test`

Expected: BUILD SUCCESS；若出现与本功能无关的既有失败，记录精确类名和失败信息，不修改无关业务来“压绿”。

- [x] **Step 2: 运行前端全量测试**

Run: `cd /Users/java/axon-link-frontend && npm test`

Expected: 所有 Vitest suites 通过。

- [x] **Step 3: 运行前端生产构建**

Run: `cd /Users/java/axon-link-frontend && npm run build`

Expected: Vite build 成功并生成 `dist/`。

- [ ] **Step 4: 本地 mock 冒烟验证关键路径**

Run: `cd /Users/java/axon-link-frontend && npm run dev -- --host 127.0.0.1`

验证：

1. 外层 ALL/DZ/QUERY 清筛选并刷新列表和卡片；重置不改变类型。
2. 手工出现批次筛选不改变按钮。
3. 两个汇总弹窗和计划完成弹窗继承外层，内切不反向影响外层。
4. 计划完成默认三天；改日期后切类型/分组仍保持日期与滚动位置。
5. 快照标题和文件名包含交易类型。

- [x] **Step 5: 审查差异只包含授权范围**

Run: `git -C /Users/java/axon-link-server diff -- src/main/java/com/axonlink/ai/replay src/test/java/com/axonlink/ai/replay docs/superpowers/plans/2026-09-02-replay-type-statistics-switch.md`

Run: `git -C /Users/java/axon-link-frontend diff -- src/api/replayIssues.js src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.vue src/components/replay/ReplayIssuePage.spec.js src/components/replay/ReplayPlannedCompletionModal.vue src/components/replay/ReplayPlannedCompletionModal.spec.js src/components/replay/completionSnapshot.js src/components/replay/completionSnapshot.spec.js mock/daoIndexMockServer.js`

Expected: 无 reset、删除、无关格式化或未授权文件覆盖。

- [x] **Step 6: 更新 Obsidian 实施状态**

在系统设计和 API 设计相应章节标注 `[IMPL]` 已实现与验证日期；更新 `_overview.md`、`index.md`，并向 `log.md` 追加：

```text
2026-09-02 [UPDATE] 实施回放问题清单交易类型联动切换 | 更新系统设计、API、总览与索引 | ALL/DZ/QUERY 覆盖主清单和四类统计
```

- [x] **Step 7: 回填本计划复选框和验证证据**

把实际完成步骤改为 `[x]`，在本任务末尾追加后端测试、前端测试、构建命令的真实通过数量和时间；失败项保持 `[ ]` 并写明阻塞原因，不用“应该通过”替代证据。

---

## Self-Review Checklist

- [x] Spec coverage: 已覆盖 ALL/DZ/QUERY 语义、双族去重、历史数据、主清单、导出、候选/计数、卡片、两个汇总弹窗、计划完成汇总/下钻、默认三天、日期轴零点、状态继承与隔离、重置优先级、快照命名。
- [x] Placeholder scan: 未发现待补内容、模糊的跨任务引用或未定义接口；每个实现任务都有失败测试、执行命令、最小实现和通过验证。
- [x] Type consistency: 全链路统一使用参数名 `replayType`、值 `ALL|DZ|QUERY`、具体批次名 `occurrenceBatches`、分组名 `groupBy=domain|issueDomain`。
- [x] Dirty-worktree safety: 明确禁止自动提交、合并、推送及覆盖既有用户修改。

## Execution Evidence（2026-09-02）

- 后端聚焦验证通过：`ReplayIssueDaoTest` 31 项、`ReplayIssueCompletionStatsDaoTest` 4 项、`ReplayIssueCompletionStatsServiceTest` 9 项；Controller 本次新增 `replayType`、非法值、计划完成类型过滤及显式日期分组用例通过。
- 后端全量 `mvn test` 已执行但未全绿：本机 Java 25 需要 `-Dnet.bytebuddy.experimental=true` 才能运行旧版 Byte Buddy；启用后仍有 12 个既有失败和 5 个既有错误，集中在轮次跟踪、优先任务旧批次夹具、旧导入批次格式、ErrorCode mock 和 UIAS 测试缺少 `AiAnalysisConfig`，不属于本功能改动。
- 前端全量验证通过：`npm test`，16 个测试文件、212 项测试全部通过。
- 前端生产构建通过：`npm run build`，Vite 8.0.1 共转换 5610 个模块，产物已输出到后端 `src/main/resources/static`。
- 本地 mock 服务已成功启动于 `127.0.0.1:5173`；浏览器自动化访问 localhost 被当前浏览器 URL 安全策略拦截，因此未把人工页面冒烟标记为完成，自动化契约由全量 Vitest 覆盖。
