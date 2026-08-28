# Replay Planned Validation Date Label Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将用户可见的“计划完成日期”统一改为“计划验证日期”，同时保留“计划完成情况”统计功能名称及全部内部兼容标识。

**Architecture:** 这是显示契约调整，不修改数据流。前端 Vue 文案、后端 Excel/异常/审计文案和测试同步更新；数据库列 `planned_completion_date`、JSON 字段 `plannedCompletionDate`、接口路径、类名与方法名保持不变。

**Tech Stack:** Vue 3、Vitest、Spring Boot、JUnit 5、Apache POI、Maven

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md:550`

## Global Constraints

- “计划完成情况”入口、模态框标题、快照标题和快照文件名必须保持原名称。
- 所有用户可见的日期名使用“计划验证日期”。
- 不修改 `planned_completion_date`、`plannedCompletionDate`、接口路径、类名、方法名或数据库数据。
- 保留工作区中用户已有改动；不清理、不重置、不提交。

---

### Task 1: 前端日期文案

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`

**Interfaces:**
- Consumes: 既有 `planned_completion_date` 行字段与 `plannedCompletionDate` 统计 DTO。
- Produces: 页面、筛选、编辑提示及统计明细统一显示“计划验证日期”；“计划完成情况”标题保持不变。

- [ ] **Step 1: 写失败测试**

  更新组件测试，使表头、锁定提示、时间轴说明和下钻字段期待“计划验证日期”，并继续断言“计划完成情况”。

- [ ] **Step 2: 验证 RED**

  Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js`

  Expected: FAIL，旧页面仍返回“计划完成日期”。

- [ ] **Step 3: 最小实现**

  只替换两个 Vue 组件中的用户可见日期文案；不得修改 `planned_completion_date`、`plannedCompletionDate` 或“计划完成情况”。

- [ ] **Step 4: 验证 GREEN**

  Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js`

  Expected: PASS。

### Task 2: 后端导出、异常与审计文案

**Files:**
- Modify: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateServiceTest.java`
- Modify: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsServiceTest.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateService.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/service/ReplayIssueCompletionRangeException.java`
- Modify: `/Users/java/axon-link-server/src/main/resources/application.yml`
- Modify: `/Users/java/axon-link-server/src/main/resources/application-local.yml.example`

**Interfaces:**
- Consumes: 既有 PATCH、统计范围校验和 Excel 导出行为。
- Produces: Excel 表头、权限/锁定/范围错误及问题跟踪操作名称统一使用“计划验证日期”。

- [ ] **Step 1: 写失败测试**

  将导出表头、锁定错误、范围错误和历史操作类型的预期更新为“计划验证日期”。

- [ ] **Step 2: 验证 RED**

  Run: `mvn -Dtest=ReplayIssueControllerTest,ReplayIssuePlanDateServiceTest,ReplayIssueCompletionStatsServiceTest test`

  Expected: FAIL，实际返回旧文案。

- [ ] **Step 3: 最小实现**

  更新控制器、服务与异常中的用户可见文案，并同步更新 YAML 注释；不修改类型和方法签名。

- [ ] **Step 4: 验证 GREEN**

  Run: `mvn -Dtest=ReplayIssueControllerTest,ReplayIssuePlanDateServiceTest,ReplayIssueCompletionStatsServiceTest test`

  Expected: PASS。

### Task 3: 回归、静态资源与打包

**Files:**
- Generated: `/Users/java/axon-link-server/src/main/resources/static/**`
- Generated: `/Users/java/axon-link-server/target/axon-link-server-1.0.0.jar`

**Interfaces:**
- Consumes: Task 1 和 Task 2 的已验证源代码。
- Produces: 已嵌入后端的最新前端资源与可运行 JAR。

- [ ] **Step 1: 全局残留检查**

  在源码与配置中确认旧日期文案只存在于设计历史或数据库迁移注释等非用户界面位置；确认“计划完成情况”仍存在。

- [ ] **Step 2: 运行前端完整测试**

  Run: `npm test -- --run`

  Expected: 全部 PASS。

- [ ] **Step 3: 生产构建前端到后端**

  使用项目既有构建脚本生成 `/Users/java/axon-link-server/src/main/resources/static`。

- [ ] **Step 4: 构建后端并检查变更**

  Run: `mvn -DskipTests clean package`

  Run: `git diff --check`

  Expected: JAR 构建成功，diff 无空白错误。
