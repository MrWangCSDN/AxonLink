# Flowtrans 分支步骤唯一键修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `case/when` 分支中缺少 `id` 的 `service` 或 `method` 因步骤 key 冲突而被 Neo4j 合并覆盖的问题。

**Architecture:** 保留现有 DOM 递归解析与图关系结构，只调整流程步骤 key 的生成规则。步骤 key 使用父容器 key、标签、局部顺序和稳定业务标识组成，使同一交易不同分支中的步骤保持唯一，同时不改变节点属性及查询路径。

**Tech Stack:** Java 17、JUnit 5、Spring Test ReflectionTestUtils、Maven

**Spec:** 用户于 2026-08-30 确认采用“父路径唯一 key，缺少 id 时回退到 serviceName/method”的缺陷修复方案；依据工程设计宪法，Bug 修复无需新增设计文档。

## Global Constraints

- 不修改 Flowtran 对外 API、Neo4j 节点标签或关系类型。
- `service` 标识优先级：`id`，其次 `serviceName`，最后局部顺序。
- `method` 标识优先级：`id`，其次 `method`，最后局部顺序。
- 修复后需要重新完整构建图谱才能替换历史冲突节点。

---

### Task 1: 修复分支步骤 key 冲突

**Files:**
- Create: `src/test/java/com/axonlink/service/FlowtransMetaGraphBuilderTest.java`
- Modify: `src/main/java/com/axonlink/service/FlowtransMetaGraphBuilder.java:326-433`

**Interfaces:**
- Consumes: flowtrans XML 中的 `case/when/service/method` 层级及属性。
- Produces: 在同一交易内按父容器路径唯一的 `FlowServiceStep.key` 和 `FlowMethodStep.key`。

- [x] **Step 1: 写失败测试**

  构造一个包含两个 `case/when` 的 XML：两个分支的首个 `service` 都不含 `id`，另构造两个不含 `id` 的 `method`。调用真实 `parseContainer`，断言四个步骤 key 均唯一，且 key 分别含对应 `serviceName` 或 `method`。

- [x] **Step 2: 运行测试确认 RED**

  Run: `mvn -Dtest=FlowtransMetaGraphBuilderTest test`

  Expected: FAIL，旧实现对不同分支生成相同的 `TX:TC028:SERVICE:1:` 或 `TX:TC028:METHOD:1:`。

- [x] **Step 3: 最小实现**

  在 `parseContainer` 中将步骤 key 改为：

  ```java
  String identity = firstNonBlank(attr(child, "id"), attr(child, fallbackAttribute), String.valueOf(order));
  String currentKey = txKey(txId) + ":" + stepType + ":"
      + keySegment(parentKey) + ":" + order + ":" + keySegment(identity);
  ```

  实际实现使用小型辅助方法集中处理 key 片段转义，避免属性中的分隔符破坏 key 可读性。

- [x] **Step 4: 运行定向测试确认 GREEN**

  Run: `mvn -Dtest=FlowtransMetaGraphBuilderTest test`

  Expected: PASS。

- [x] **Step 5: 运行相关回归测试**

  Run: `mvn -Dtest=FlowtransMetaGraphBuilderTest,FlowtranChainExportServiceImplTest test`

  Expected: PASS，现有链路导出行为不变。

- [x] **Step 6: 检查差异**

  Run: `git diff --check && git diff -- src/main/java/com/axonlink/service/FlowtransMetaGraphBuilder.java src/test/java/com/axonlink/service/FlowtransMetaGraphBuilderTest.java`

  Expected: 无空白错误，差异仅覆盖 key 生成和回归测试。
