# 回放问题计划完成日期 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在问题清单中增加按领域授权的计划完成日期内联编辑、严格日期校验、导入继承、导出列和问题跟踪审计。

**Architecture:** 当前问题表新增可空 DATE 字段；独立权限属性与服务按 `group_name + emp_no` 判定可编辑范围；独立 PATCH 接口在结果库事务中锁定问题、校验权限、幂等更新并写历史。前端在表格单元格中用普通文本框实现失焦/Enter 自动保存和 Esc 取消，不耦合现有状态与邮件编辑流程。

**Tech Stack:** Java 17、Spring Boot 3、Spring JDBC、Flyway SQL、Vue 3、Vite、Vitest、Apache POI、Playwright/浏览器验收

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`、`并行回放问题清单-数据模型.md`、`并行回放问题清单-API接口.md`

## Global Constraints

- 列顺序固定为“问题描述 → 计划完成日期 → 缺陷修复日期”。
- 计划日期不从 Excel 导入，任何重复导入和基础数据覆盖都必须保留人工值。
- 非空输入只接受真实存在的 `yyyy-MM-dd`；统一错误文案为“填写日期格式不合法，请按 2026-08-26 格式填写”。
- 失焦或 Enter 保存，Esc 取消；规范化后无变化不更新、不写历史，已有日期清空属于有效修改。
- 权限按公共组、存款组、贷款组、结算组分别配置 `emp_no`，沙箱与非沙箱共用规范化领域。
- 前端权限只控制交互，后端 PATCH 接口必须重新鉴权。
- 不增加日期控件、筛选、批量编辑、提醒或逾期高亮。
- 当前工作区包含大量用户既有改动；不 reset、不清理、不提交、不覆盖无关文件。

---

### Task 1: 数据库字段与当前问题投影

**Files:**
- Create: `src/main/resources/db/daoindex/V50__dii_replay_issue_planned_completion_date.sql`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`

**Interfaces:**
- Produces: `ReplayIssueRow.plannedCompletionDate(): LocalDate`、`ReplayIssueDao.updatePlannedCompletionDate(long, LocalDate)`。
- Preserves: `insertCurrent`/`updateCurrent`/导入合并携带原 `plannedCompletionDate`，Excel 解析器不产生该字段。

- [ ] **Step 1: 写失败测试**

在 DAO 测试中插入带 `plannedCompletionDate=2026-08-26` 的问题，断言查询投影保留日期；调用专用更新方法后断言日期变化；在合并测试中给存量问题设置日期后导入相同 `issue_key`，断言日期仍为原值。

- [ ] **Step 2: 运行 RED**

Run: `mvn -Dtest=ReplayIssueDaoTest,ReplayIssueMergeServiceTest test`

Expected: 因字段、迁移或方法不存在而失败。

- [ ] **Step 3: 实现最小数据变更**

迁移内容：

```sql
ALTER TABLE dii_replay_issue
    ADD COLUMN planned_completion_date DATE DEFAULT NULL
    COMMENT '计划完成日期（领域授权人员维护）'
    AFTER issue_description;
```

扩展 `ReplayIssueRow`、`mapRow`、insert/update 参数和测试表结构。专用更新 SQL：

```java
public void updatePlannedCompletionDate(long id, LocalDate value) {
    jdbc.update("UPDATE dii_replay_issue SET planned_completion_date=? WHERE id=?", value, id);
}
```

- [ ] **Step 4: 运行 GREEN**

Run: `mvn -Dtest=ReplayIssueDaoTest,ReplayIssueMergeServiceTest test`

Expected: PASS，且重复导入不覆盖计划日期。

---

### Task 2: 领域权限与计划日期事务服务

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateProperties.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateForbiddenException.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateService.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePlanDatePermissions.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePlannedCompletionDateUpdateRequest.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateServiceTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml.example`

**Interfaces:**
- Consumes: `ReplayIssueDao.findCurrentByIdForUpdate`、`updatePlannedCompletionDate`、`insertHistoryForRound`。
- Produces: `permissions(ReplayIssueOperator): ReplayIssuePlanDatePermissions`、`update(long, String, ReplayIssueOperator): ReplayIssueRow`。

- [ ] **Step 1: 写权限和日期失败测试**

覆盖：本组工号可编辑、跨组和未配置工号拒绝、沙箱沿用领域权限、`2026-08-26` 成功、`2026/08/26`/`2026-08-32`/`2026-02-30` 返回统一非法日期文案、空字符串清空。

- [ ] **Step 2: 写幂等与审计失败测试**

覆盖：原值和新值相同不 UPDATE、不新增历史；原值和新值都空不保存；已有日期清空会更新并新增一条“修改计划完成日期”历史，快照包含前后 `plannedCompletionDate` 和操作人。

- [ ] **Step 3: 运行 RED**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest test`

Expected: 新类型和服务不存在。

- [ ] **Step 4: 实现属性绑定和严格解析**

属性前缀：

```java
@ConfigurationProperties(prefix = "dii.replay.issue-plan-date")
public class ReplayIssuePlanDateProperties {
    private Map<String, EditorGroup> editors = new LinkedHashMap<>();
}
```

严格解析先验证 `\\d{4}-\\d{2}-\\d{2}`，再调用 `LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)`；空白归一化为 `null`。

- [ ] **Step 5: 实现事务更新和快照审计**

事务内执行：锁定问题 → 校验当前操作人 `empNo` 属于规范化 `groupName` 名单 → 比较旧新日期 → 专用 SQL 更新 → 重新查询投影 → 写历史并关联最近问题批次。无变化直接返回原行。

- [ ] **Step 6: 运行 GREEN**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest test`

Expected: PASS。

---

### Task 3: HTTP 接口、列表投影与 Excel 导出

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `GET /api/ai/parallel-replay/issues/plan-date-permissions`。
- Produces: `PATCH /api/ai/parallel-replay/issues/{id}/planned-completion-date`。
- Produces: 列表键 `planned_completion_date` 和导出列“计划完成日期”。

- [ ] **Step 1: 写控制器失败测试**

验证登录态权限查询、合法保存、清空、未登录 401、无权限 403、非法日期 400、问题不存在 404，以及响应使用统一 `R<T>` 包装。

- [ ] **Step 2: 写导出列顺序失败测试**

生成导出工作簿并断言“问题描述”后一列是“计划完成日期”，再后一列是“缺陷修复日期”；计划日期为真实 Excel 日期或 ISO 文本，空值为空单元格。

- [ ] **Step 3: 运行 RED**

Run: `mvn -Dtest=ReplayIssueControllerTest test`

Expected: 路由不存在或导出列顺序不符。

- [ ] **Step 4: 实现接口和导出投影**

控制器解析当前操作人并调用独立服务；捕获 `ReplayIssuePlanDateForbiddenException` 返回 403，日期参数异常返回 400，不存在返回 404。导出 headers 与 values 同步插入计划日期并移动缺陷修复日期。

- [ ] **Step 5: 运行 GREEN**

Run: `mvn -Dtest=ReplayIssueControllerTest test`

Expected: 新增用例通过；若完整套件仍出现已知批次跟踪旧断言，单独报告而不改变本功能口径。

---

### Task 4: 前端 API 与内联编辑交互

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `getReplayIssuePlanDatePermissions()`、`updateReplayIssuePlannedCompletionDate(id, plannedCompletionDate)`。
- Produces: 表格字段 `planned_completion_date`，内联编辑状态 `editingPlanDateId/planDateDraft/planDateError/planDateSavingId`。

- [ ] **Step 1: 写 API 失败测试**

断言权限请求 GET 新路径，保存请求 PATCH 新路径并发送 `{plannedCompletionDate:'2026-08-26'}`，清空发送 `{plannedCompletionDate:null}`。

- [ ] **Step 2: 写页面列与权限失败测试**

断言列顺序为“问题描述、计划完成日期、缺陷修复日期”；空值显示 `-`；权限响应只含“公共组”时公共组单元格可点击、贷款组只读。

- [ ] **Step 3: 写交互失败测试**

覆盖：点击进入文本框；blur/Enter 自动保存；Esc 取消；未变化和空到空不调用 API；已有值清空发送 null；非法格式和不存在日期不调用 API并显示统一提示；保存失败保留编辑状态。

- [ ] **Step 4: 运行 RED**

Run: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js`

Workdir: `/Users/java/axon-link-frontend`

Expected: 新 API、列和交互不存在。

- [ ] **Step 5: 实现最小 API 和页面逻辑**

`visibleColumns` 中把 `planned_completion_date` 插在 `issue_description` 后，把 `defect_repair_date` 紧随其后并从原位置删除。日期单元格仅在 `editableGroups.includes(normalizeGroup(row.group_name))` 时响应点击；输入框使用 `type="text"`、`placeholder="2026-08-26"`，blur 和 Enter 共用 `savePlanDate`，Esc 设置取消标记以避免 blur 再保存。

- [ ] **Step 6: 样式收口**

内联输入框限制在单元格内，日期等宽展示；保存中显示轻量状态；错误提示浮在单元格附近或复用页面错误区，不改变行高和宽表滚动结构。

- [ ] **Step 7: 运行 GREEN**

Run: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js`

Expected: PASS。

---

### Task 5: 本地 Mock、浏览器验收与构建

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Verify: `src/main/resources/static/**`

**Interfaces:**
- Produces: 本地可访问测试页面，至少包含有权限/无权限、有日期/空日期四类行。

- [ ] **Step 1: 准备非生产 Mock 数据**

提供公共组授权行：`2026-08-26`、空值；贷款组未授权行：`2026-09-10`、空值。Mock 权限返回 `editableGroups:['公共组']`，保存 API 在内存中回写对应行，不写真实数据库。

- [ ] **Step 2: 启动前端并真实浏览器验证**

Run: `npm run dev -- --host 127.0.0.1`

验证列顺序、横向滚动、授权/只读差异、blur/Enter/Esc、无变化不请求、清空、非法日期提示和正常日期回显。

- [ ] **Step 3: 运行前后端完整相关测试**

Run backend: `mvn -Dtest=ReplayIssueDaoTest,ReplayIssueMergeServiceTest,ReplayIssuePlanDateServiceTest,ReplayIssueControllerTest test`

Run frontend: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js`

- [ ] **Step 4: 生产构建和静态资源同步**

Run frontend: `npm run build`

Vite 的 `build.outDir` 已直接指向 `../axon-link-server/src/main/resources/static`，构建完成后随后运行：

Run backend: `mvn -DskipTests package`

- [ ] **Step 5: 最终检查**

Run: `git diff --check`

检查生产静态资源包含新 API 路径和“计划完成日期”文案；确认没有把 Mock 数据打进生产构建；报告专项测试数量、本地 URL 和已知无关失败。
