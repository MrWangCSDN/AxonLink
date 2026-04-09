# Async Build Status Design

## Background

`sunline-benchmark` 在全量拉取+编译成功后，会通过成功回调触发 `axon-link-server` 的异步 Neo4j build：

- benchmark 成功回调地址：`POST /api/neo4j/build/async`
- benchmark 当前只会在触发 8123 成功后，把 `BuildOperationRecord.asyncBuildStatus` 更新为 `异步构建中`
- benchmark 页面轮询接口 `GET /api/build/all/status` 会把 `asyncBuildStatus` 一并返回

当前问题是：

- `axon-link-server` 没有接住 benchmark 回调携带的 `operationId`
- `axon-link-server` 的 build phase 变化、最终成功、最终失败，都不会再回写 benchmark
- 页面侧当前仍然主要按 benchmark 的 `taskStatus` / snapshot 解释成功，不满足“必须 phase 完成才算真正成功”的要求

目标是把 `asyncBuildStatus` 变成页面判定“真正成功”的第一真源：

- 只有 8123 全部 phase 完成并回写 `成功`，页面才显示成功
- 只要不是明确失败，也不是明确成功，页面统一显示 `构建中`

## Goals

- 复用 benchmark 现有成功回调入口，不增加第二条对接链路
- 让 8123 在异步 build 全生命周期内向 benchmark 回写 `asyncBuildStatus`
- 页面轮询继续通过 `GET /api/system/build-sync-status` 获取状态，但状态判定改为优先使用 `asyncBuildStatus`
- 只有 `phase=done` 才回写 `成功`
- 任意异常退出都要回写 `失败:<phase>`

## Non-Goals

- 不修改 benchmark 现有 `POST /api/build/async-build/status` 契约
- 不重构 benchmark 的任务记录表结构
- 不改变 8123 build 主逻辑的阶段划分
- 不新增前端单独请求 benchmark 的接口

## Existing Flow

1. benchmark 完成全量拉取+编译
2. benchmark 调用 `POST http://127.0.0.1:8123/api/neo4j/build/async`
3. benchmark 本地将 `asyncBuildStatus` 写为 `异步构建中`
4. 8123 开始异步 build
5. 8123 build 完成或失败，但 benchmark 不知道结果
6. 页面只能看到 benchmark 拉取+编译状态，无法准确判断 8123 phase 是否真正完成

## Proposed Flow

1. benchmark 继续调用 `POST /api/neo4j/build/async`
2. benchmark 继续在请求头中传：
   - `X-Build-Operation-Id`
   - `X-Build-Version-No`
3. 8123 在入口接住这两个 header，并创建本次 build 的回调上下文
4. 8123 build 过程中在每个关键 phase 切换时，主动调用 benchmark 的 `POST /api/build/async-build/status`
5. 8123 `phase=done` 时回写 `成功`
6. 8123 任何异常退出时回写 `失败:<phase>`
7. 页面仍然轮询 `GET /api/system/build-sync-status`
8. `axon-link-server` 的状态聚合逻辑改为优先使用 benchmark 返回的 `asyncBuildStatus`

## Status Source of Truth

页面三态严格按 benchmark 的 `asyncBuildStatus` 解释：

- `成功`
  - 页面显示 `成功`
  - `statusType=success`
- `触发失败`，或任意包含 `失败` 的值
  - 页面显示 `失败`
  - `statusType=error`
- 其它全部
  - 页面显示 `构建中`
  - `statusType=running`

这意味着以下状态全部统一算 `构建中`：

- `异步构建中`
- `phase0_bootstrap`
- `collect`
- `phase1_declarations`
- `phase2_calls`
- `phase3_write`
- `phase4_flowtrans`
- `phase5_impact_projection`
- 空字符串
- `未触发`
- benchmark 状态接口暂时不可用时的回退状态

## Callback Contract

### Incoming callback to 8123

继续复用现有接口：

- `POST /api/neo4j/build/async`

benchmark 通过请求头传：

- `X-Build-Operation-Id`
- `X-Build-Version-No`

如果 header 缺失：

- 8123 仍允许本地 build 执行
- 但不会向 benchmark 回写 phase 状态

### Outgoing callback from 8123 to benchmark

8123 调用 benchmark：

- `POST /api/build/async-build/status`

请求体：

```json
{
  "operationId": "xxx",
  "status": "phase3_write"
}
```

状态写法固定为：

- `异步构建中`
- `phase0_bootstrap`
- `collect`
- `phase1_declarations`
- `phase2_calls`
- `phase3_write`
- `phase4_flowtrans`
- `phase5_impact_projection`
- `成功`
- `失败:<phase>`
- `失败:已有构建执行中`

## 8123 Backend Changes

### 1. Build async entry

`Neo4jGraphController` 的 `POST /api/neo4j/build/async` 需要：

- 接收请求头 `X-Build-Operation-Id`
- 接收请求头 `X-Build-Version-No`
- 将这两个值传给 `Neo4jGraphBuilder.startBuildAsync(...)`

### 2. Build context

`Neo4jGraphBuilder` 需要新增一份当前 build 上下文，至少包含：

- `operationId`
- `versionNo`
- `startedAt`

该上下文仅代表当前一次 build。

### 3. Benchmark async reporter

新增一个独立的回写器，例如 `BenchmarkAsyncBuildReporter`，职责单一：

- 根据配置读取 benchmark base URL
- 向 `POST /api/build/async-build/status` 发送状态
- 对外提供简单方法：
  - `reportRunning(operationId)`
  - `reportPhase(operationId, phase)`
  - `reportSuccess(operationId)`
  - `reportFailure(operationId, phaseOrReason)`

回写失败只记日志，不中断真实 build。

### 4. Phase reporting points

`Neo4jGraphBuilder.doBuild()` 的回写时机固定为：

- 接收到 benchmark 回调并成功启动 build 时：
  - 回写 `异步构建中`
- phase 切换时：
  - `phase0_bootstrap`
  - `collect`
  - `phase1_declarations`
  - `phase2_calls`
  - `phase3_write`
  - `phase4_flowtrans`
  - `phase5_impact_projection`
- 全部完成且 `phase=done`：
  - 回写 `成功`
- catch 中：
  - 回写 `失败:<当前phase>`

注意：

- 只有 `phase=done` 后才允许回写 `成功`
- `phase5_impact_projection` 完成前不能回写成功

### 5. Concurrent build rule

如果 8123 已经存在正在执行中的 build，再收到 benchmark 新回调：

- 不排队
- 不抢占
- 直接拒绝启动
- 若本次请求携带了 `operationId`，立即回写：
  - `失败:已有构建执行中`

## Frontend-facing Status Aggregation

`BuildSyncStatusServiceImpl` 需要改成以 `asyncBuildStatus` 为第一真源。

### Input data

它继续读取 benchmark：

- `GET /api/build/all/status`
- `GET /api/build/records/recent`

其中 `GET /api/build/all/status` 已包含：

- `status`
- `phase`
- `operationId`
- `finishMessage`
- `asyncBuildStatus`

### Aggregation rules

聚合结果规则：

- `asyncBuildStatus = 成功`
  - `statusText = 成功`
  - `statusType = success`
  - `message` 优先使用 benchmark 最近完成信息

- `asyncBuildStatus` 包含 `失败`，或等于 `触发失败`
  - `statusText = 失败`
  - `statusType = error`
  - `message = asyncBuildStatus`

- 其它全部
  - `statusText = 构建中`
  - `statusType = running`
  - 若 `asyncBuildStatus` 非空，`message = 当前阶段：<asyncBuildStatus>`
  - 若 `asyncBuildStatus` 为空，`message = 正在等待异步构建完成`

### Frontend display implication

`TransactionAnalysis.vue` 无需改变轮询节奏与展示入口，只需要消费新的聚合结果：

- 成功：绿色成功态
- 失败：红色失败态
- 其余全部：蓝色或默认运行态，文案统一为 `构建中`

## Error Handling

### Reporter failure

8123 回写 benchmark 失败时：

- 记录 warn 日志
- 不中断 build
- 不改变当前 build phase

### Missing operationId

手工触发 `POST /api/neo4j/build/async` 且没有请求头时：

- build 正常执行
- 仅本地日志可见，不向 benchmark 回写

### Build exception

如果 `doBuild()` 抛异常：

- 将 `phase` 设为 `error`
- 对 benchmark 回写 `失败:<原phase>`，如果原 phase 为空则回写 `失败:error`

## Verification

需要验证以下场景：

1. benchmark 正常触发 8123
   - 8123 能接住 `X-Build-Operation-Id`
   - benchmark 中 `asyncBuildStatus` 从 `异步构建中` 继续被更新为各个 phase

2. 8123 成功完成
   - benchmark 中最终 `asyncBuildStatus = 成功`
   - 页面最终显示 `成功`

3. 8123 在任一 phase 失败
   - benchmark 中最终 `asyncBuildStatus = 失败:<phase>`
   - 页面最终显示 `失败`

4. 8123 build 期间页面轮询
   - 即使 benchmark 自身 `taskStatus=SUCCESS`
   - 只要 `asyncBuildStatus != 成功`
   - 页面都显示 `构建中`

5. 8123 已在构建中再次收到回调
   - benchmark 新任务的 `asyncBuildStatus = 失败:已有构建执行中`

6. benchmark 回写接口不可用
   - 8123 build 仍然完成
   - 本地日志出现回写失败告警

## Implementation Scope

本次实现只覆盖：

- 8123 接收 benchmark 回调请求头
- 8123 在 build 生命周期内回写 benchmark 的 `asyncBuildStatus`
- `BuildSyncStatusServiceImpl` 三态聚合逻辑修正

本次不包含：

- benchmark 数据表结构调整
- benchmark 页面字段新增
- build phase 名称重构
