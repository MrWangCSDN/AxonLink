# 计划验证日期按组高级权限 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为计划验证日期增加按 `group_name` 配置的高级编辑名单，使其自动拥有该组编辑权限并突破首次出现日期加 7 个自然日限制。

**Architecture:** 在现有 `ReplayIssuePlanDateProperties` 中增加与普通 `editors` 平行的 `advancedEditors` 映射。权限接口把普通与高级领域并集投影到 `editableGroups`，把高级领域单独投影到 `dateLimitBypassGroups`；后端保存时始终先执行缺陷修复日期锁定和严格日期解析，仅高级名单命中的当前组跳过首次出现日期及 7 天边界。前端只用权限投影跳过预校验，后端仍是最终事实源。

**Tech Stack:** Java 17、Spring Boot ConfigurationProperties、Spring JDBC、JUnit 5、Vue 3、Vitest。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`、`并行回放问题清单-数据模型.md`、`并行回放问题清单-API接口.md`

## Global Constraints

- 高级名单按 `group_name` 分组，身份匹配继续优先 `emp_no`，无工号时回退 `username`。
- 高级名单本身授予对应组编辑权限；同人同时命中普通和高级名单时按高级权限。
- 高级权限只跳过首次出现日期和 7 天边界，不跳过严格 `yyyy-MM-dd` 校验。
- `defect_repair_date` 非空锁定优先于普通、高级及开发负责人权限。
- 开发负责人交易码授权仍执行普通 7 天边界。
- 不修改数据库结构，不治理历史数据，不提交或覆盖工作区中的其他改动。

---

### Task 1: 后端高级配置、权限投影与保存强制校验

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateProperties.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePlanDatePermissions.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateService.java`

**Interfaces:**
- Consumes: `ReplayIssuePlanDateProperties.EditorGroup`、现有普通 `editors`、当前启用用户身份。
- Produces: `getAdvancedEditors()/setAdvancedEditors(...)`；`ReplayIssuePlanDatePermissions(editableGroups, dateLimitBypassGroups, editableTransactionCodes)`；高级名单命中时跳过 `validateOccurrenceBoundary`。

- [x] **Step 1: 写高级权限失败测试**

在测试配置中新增高级用户，并断言：高级用户未配置普通名单仍出现在 `editableGroups` 和 `dateLimitBypassGroups`；能保存首次出现日期为空或超过 7 天的合法日期；普通人员和开发负责人仍被边界拒绝；高级人员遇到缺陷修复日期仍被锁定；非法日期仍被拒绝。

- [x] **Step 2: 运行聚焦测试确认 RED**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest test`

Expected: 编译或断言失败，因为高级配置与 `dateLimitBypassGroups` 尚不存在。

- [x] **Step 3: 实现最小后端能力**

在 Properties 中增加：

```java
private Map<String, EditorGroup> advancedEditors = new LinkedHashMap<>();
```

权限计算分别得到普通领域和高级领域，使用稳定顺序并集生成 `editableGroups`。保存时计算当前用户是否命中当前组高级名单；`canEdit` 接受普通、高级或开发负责人任一路径，高级命中时不调用 `validateOccurrenceBoundary`。

- [x] **Step 4: 运行聚焦测试确认 GREEN**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest test`

Expected: PASS。

---

### Task 2: HTTP 契约与 YAML 示例

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml.example`

**Interfaces:**
- Consumes: Task 1 的三集合权限 DTO。
- Produces: `GET /plan-date-permissions` JSON 字段 `dateLimitBypassGroups`；环境变量 `DII_REPLAY_PLAN_DATE_ADVANCED_EDITORS_PUBLIC/DEPOSIT/LOAN/SETTLEMENT`。

- [x] **Step 1: 写控制器契约测试**

给控制器测试的公共组配置高级用户，断言权限响应同时包含 `editableGroups`、`dateLimitBypassGroups`、`editableTransactionCodes`，并通过 PATCH 保存超过 7 天的合法日期。

- [x] **Step 2: 验证控制器既有测试状态**

Run: `mvn -Dtest=ReplayIssueControllerTest test`

Result: 服务层 RED 已先确认；控制器契约在服务能力完成后补充，不重复声称独立 RED。

- [x] **Step 3: 更新装配测试和 YAML**

四个现有业务组均增加：

```yaml
advanced-editors:
  公共组:
    emp-nos: ${DII_REPLAY_PLAN_DATE_ADVANCED_EDITORS_PUBLIC:}
```

其余组分别使用 `DEPOSIT`、`LOAN`、`SETTLEMENT`。普通配置保持不变。

- [x] **Step 4: 运行控制器聚焦测试确认 GREEN**

Run: `mvn '-Dtest=ReplayIssueControllerTest#planDatePermissionsAndPatchEnforceAuthenticationPermissionAndValidation' test`

Expected: PASS。

---

### Task 3: 前端高级权限预校验

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`

**Interfaces:**
- Consumes: `getReplayIssuePlanDatePermissions()` 返回的 `dateLimitBypassGroups`。
- Produces: 高级组行仍显示编辑入口，并对合法日期直接调用保存接口；普通组继续在浏览器阻止超 7 天值。

- [x] **Step 1: 写前端失败测试**

保留现有普通用户超 7 天阻止测试；新增高级组权限响应 `{ editableGroups:['贷款组'], dateLimitBypassGroups:['贷款组'], editableTransactionCodes:[] }`，输入超过 7 天的合法日期后断言调用 `updateReplayIssuePlannedCompletionDate`，首次出现日期为空时也可调用；缺陷修复日期锁定测试保持不变。

- [x] **Step 2: 运行聚焦测试确认 RED**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: 高级用户仍被现有前端 7 天预校验拦截。

- [x] **Step 3: 实现前端最小分支**

权限状态默认值增加 `dateLimitBypassGroups: []`，新增：

```js
function canBypassPlanDateLimit(row) {
  return (planDatePermissions.dateLimitBypassGroups || []).includes(row?.group_name)
}
```

`savePlanDate` 仅在 `normalized && !canBypassPlanDateLimit(row)` 时执行首次出现日期和 7 天校验；格式校验和缺陷修复日期锁定保持在该分支之外。

- [x] **Step 4: 运行前端聚焦测试确认 GREEN**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: PASS。

---

### Task 4: 完整回归与交付检查

**Files:**
- Verify only: backend and frontend worktrees.

**Interfaces:**
- Consumes: Tasks 1–3 全部结果。
- Produces: 可发布的后端配置契约与前端行为。

- [x] **Step 1: 运行后端相关聚焦回归**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest test`；`mvn '-Dtest=ReplayIssueControllerTest#planDatePermissionsAndPatchEnforceAuthenticationPermissionAndValidation' test`

Result: 服务 12/12、HTTP 契约 1/1 通过。控制器整类仍有 8 个与本功能无关的既有失败（5 个历史夹具/顺序/统计断言，3 个 Java 25 下 Mockito 动态代理问题），未在本任务扩改。

Full-suite note: 2026-09-01 收尾执行 `mvn test`，Java 25 下共 456 项、10 个失败、34 个错误，其中大量错误来自当前 Byte Buddy/Mockito 不支持 Java 25；随后使用本机 Java 17 重跑，结果为 12 个失败、5 个错误、2 个跳过。剩余问题分布在 UIAS 测试装配缺少 `AiAnalysisConfig`、回放历史夹具/导入批次格式/周任务夹具以及错误码扫描历史断言，与本功能无关。本功能聚焦测试继续通过，因此停止提交、合并和推送，不在本任务修改无关模块。

- [x] **Step 2: 运行前端全量测试**

Run: `npm test -- --run`

Expected: 全部 PASS。

- [x] **Step 3: 运行前端生产构建**

Run: `npm run build`

Expected: Vite 成功，产物写入后端 `src/main/resources/static`。

- [x] **Step 4: 检查差异质量**

Run: `git diff --check && git -C /Users/java/axon-link-frontend diff --check`

Expected: exit 0；仅报告本功能及用户既有改动，不删除或覆盖其他文件。
