# 服务分析全领域反向聚合设计

日期：2026-04-09

## 1. 背景

当前“影响分析 -> 服务分析”仍然依赖前端基于当前页面已加载交易链路做本地聚合，结果只覆盖当前页数据，不能表达以下真实语义：

- 一个服务方法可能被多个上游服务调用
- 一个服务方法可能被多个 `xxx.flowtrans.xml` 直接编排进 `flow`
- 同一个服务方法既可能存在编码调用路径，也可能直接参与流程编排
- 联机交易必须来自流程编排，而不是任意服务调用链

因此，服务分析不能继续复用当前前端本地 `service` 模式，需要新增一套独立的后端全领域反向聚合能力。

## 2. 目标

- 新增服务分析后端目录接口与影响图接口
- 服务目录的粒度固定为 `ServiceOperation`，即 XML 中每个 `<service>` 节点一个服务方法
- 服务分析的图层级固定为：
  - `根服务 -> 上游服务层 -> 流程编排层 -> 联机交易层`
- 流程编排层按“编排实例”展示，不按服务定义去重
- 联机交易层按“交易名”去重
- 前端保持当前 Figma/页面整体视觉结构，不改主视图形态，只替换服务分析的数据源与层级语义

## 3. 非目标

- 本次不改表级分析与构件分析既有接口语义
- 本次不修改交易链路单交易页面的后端组装逻辑
- 本次不在页面展示 DAO 层
- 本次不把“流程编排层”和“联机交易层”合并成一层
- 本次不让服务分析直接从服务节点跨层挂交易

## 4. 设计原则

1. 服务定义与流程编排实例必须分层表达，不能混成同一层。
2. 只有直接出现在某个交易 `flowtrans.xml` flow 节点中的步骤，才有资格归并到联机交易。
3. 服务层表达“编码调用关系”，流程编排层表达“被哪个 flow 直接编排”。
4. 同一个服务被多个 `flowtrans.xml` 直接编排时，必须完整展示多个流程编排实例节点。
5. 联机交易仍按交易名去重，但流程编排实例不去重。
6. 页面中的关系线继续全部走虚线语义，悬停节点时只点亮一跳邻接关系。

## 5. 现有语义澄清

### 5.1 服务层

服务层节点来自已落库的 `ServiceOperation`，类型范围固定为：

- `pbs`
- `pcs`
- `service`

这里的“服务层”只表示服务方法定义及其编码调用关系，不直接承载交易归属语义。

### 5.2 流程编排层

流程编排层不是服务定义层的别名，而是“流程中直接出现的编排实例层”。

一个流程编排实例表示：

- 某个 `Transaction`
- 某个 `FlowMethodStep` 或 `FlowServiceStep`
- 该步骤直接出现在 `flowtrans.xml` 的 `flow/case/when` 容器中

因此：

- 同一个服务方法被 3 个不同 `flowtrans.xml` 直接编排时，要展示 3 个流程编排实例节点
- 同一个交易里同一个服务被编排多次，也保留多个实例节点，因为它们代表不同 step 位置

### 5.3 联机交易层

联机交易只能由流程编排层向上归并得到，不能由服务层直接挂接。

## 6. 总体方案

新增独立后端服务分析能力：

- `GET /api/flowtran/impact/services`
- `GET /api/flowtran/impact/service/{serviceId}`

前端服务分析模式改为：

- 目录选择：使用后端全量服务目录
- 图结果：使用后端全领域服务影响分析结果

当前前端本地 `analyzeImpact(..., 'service', ...)` 逻辑只作为过渡实现，不再作为正式服务分析的数据来源。

## 7. 接口设计

### 7.1 服务目录

路由：

`GET /api/flowtran/impact/services`

请求参数：

- `keyword`：可选，支持按服务方法编码或中文名过滤

返回结构：

```json
{
  "total": 4321,
  "items": [
    {
      "id": "DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp",
      "name": "检查二次提交状态",
      "longname": "检查二次提交状态",
      "domainKey": "deposit",
      "nodeKind": "pbs",
      "serviceTypeId": "DpCheckAffrTwcSubmitBcs"
    }
  ]
}
```

目录范围只允许：

- `pbs`
- `pcs`
- `service`

### 7.2 服务影响图

路由：

`GET /api/flowtran/impact/service/{serviceId}`

示例：

`/api/flowtran/impact/service/DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp`

返回结构：

```json
{
  "mode": "service",
  "root": {
    "id": "DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp",
    "name": "检查二次提交状态",
    "type": "pbs",
    "domainId": "deposit",
    "nodeType": "service"
  },
  "levels": [
    [
      {
        "id": "AaaPcs.BbbSvc",
        "name": "上游服务A",
        "type": "pcs",
        "domainId": "deposit",
        "nodeType": "service"
      }
    ],
    [
      {
        "id": "TX:dp2524:STEP:svc_03",
        "name": "检查二次提交状态",
        "desc": "dp2524.flowtrans.xml",
        "domainId": "deposit",
        "nodeType": "orchestration",
        "type": "pbs",
        "txId": "dp2524",
        "txName": "二次提交交易"
      }
    ],
    [
      {
        "id": "dp2524",
        "name": "二次提交交易",
        "code": "dp2524",
        "domainId": "deposit",
        "nodeType": "transaction"
      }
    ]
  ],
  "edges": [
    { "from": "AaaPcs.BbbSvc", "to": "DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp", "isIntraLayer": true },
    { "from": "DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp", "to": "TX:dp2524:STEP:svc_03" },
    { "from": "TX:dp2524:STEP:svc_03", "to": "dp2524" }
  ],
  "stats": {
    "upstreamServices": 5,
    "orchestrations": 3,
    "transactions": 2
  }
}
```

## 8. 返回字段语义

### 8.1 root

- 固定是用户选中的服务方法
- `nodeType = "service"`
- `type` 只允许 `pbs / pcs / service`

### 8.2 levels[0]：上游服务层

- 只表示“谁调用了当前服务”
- 节点 `nodeType = "service"`
- `type` 保留原始服务类型：
  - `pbs`
  - `pcs`
  - `service`

### 8.3 levels[1]：流程编排层

- 单独使用 `nodeType = "orchestration"`
- 但 `type` 仍保留原始编排步骤类型：
  - `method`
  - `pbs`
  - `pcs`
- 流程编排层节点必须带：
  - `txId`
  - `txName`
  - `desc`，优先显示 `flowtrans.xml` 文件或交易标识

流程编排层不做服务定义级去重，保留实例语义。

### 8.4 levels[2]：联机交易层

- 固定 `nodeType = "transaction"`
- 联机交易按交易名去重

## 9. 去重规则

### 9.1 流程编排实例

不去重。

推荐实例 ID 组成：

- `txId`
- `stepKey`

例如：

- `TX:dp2524:STEP:svc_03`

### 9.2 联机交易

按交易名去重。

稳定性规则：

1. 同名交易合并为一个联机交易节点
2. 节点 `name` 取交易名
3. 节点 `id/code` 取同名交易中字典序最小的 `tx.id`

## 10. 聚合算法

### 10.1 步骤 1：定位根服务

从 `ServiceOperation(serviceTypeId, serviceId)` 定位目标服务方法，读取：

- `longname`
- `nodeKind`
- `domainKey`
- `IMPLEMENTS_BY` 对应的方法实现签名

### 10.2 步骤 2：反向收敛上游服务

以根服务的实现方法为起点，沿以下关系反向回溯：

- `CALLS`
- `SYS_UTIL_CALLS`
- `SELF_CALLS`

只保留最终能映射回 `ServiceOperation` 的节点，形成“上游服务层”。

规则：

- 上游服务层只表达服务间调用
- 若某个方法无法映射回 `ServiceOperation`，不直接展示为节点
- 服务到服务的同层关系以 `isIntraLayer=true` 返回

### 10.3 步骤 3：识别流程编排实例

不直接找交易，而是先找“哪些 flow step 直接编排了根服务”。

核心匹配关系：

- `FlowServiceStep -[:CALLS_SERVICE]-> ServiceOperation`

每命中一个直接 step，就生成一个流程编排实例节点。

实例节点需要额外补齐：

- 所属 `Transaction`
- step 原始类型
- step 名称
- `flowtrans.xml` 上下文信息

### 10.4 步骤 4：由流程编排实例归并到联机交易

从流程编排实例节点回溯所属交易：

- `Transaction -[:HAS_FLOW]-> FlowBlock`
- `FlowBlock/FlowCase/FlowWhen -[:HAS_STEP|EXECUTES|HAS_BRANCH|NEXT*]-> FlowStep`

流程编排实例节点始终保留，联机交易只做最右侧去重聚合。

## 11. 连线规则

- `service -> service`
  - 同层虚线
  - `isIntraLayer = true`
- `service -> orchestration`
  - 跨层连线
  - 仅当该服务本身是直接 flow step 时建立
- `orchestration -> transaction`
  - 跨层连线
  - 始终保留

不允许以下连线：

- `service -> transaction`
- `upstream service -> transaction`
- `service definition -> orchestration` 的模糊合并线

## 12. 前端展示约束

### 12.1 层级布局

服务分析固定展示 4 段语义：

- 根服务
- 上游服务层
- 流程编排层
- 联机交易层

其中流程编排层在页面中单独成列，标题固定为“流程编排层”。

### 12.2 流程编排层子列

流程编排层内部继续按以下 3 个子列展示：

- `method`
- `pbs`
- `pcs`

这样既保留“它是哪个流程实例”，又保留“它在 flow 中的原始步骤类型”。

### 12.3 关系线与悬停

- 页面中的关系线继续全部按虚线图样式呈现
- 悬停任意节点时：
  - 点亮上一层直接相连节点
  - 点亮下一层直接相连节点
  - 点亮同层直接调用节点
  - 其余节点与连线降噪显示

## 13. 实施边界

- 表级分析接口不改
- 构件分析接口不改
- 服务分析单独新增目录接口与影响图接口
- 前端仅替换服务分析模式的数据源，不重做整页布局

## 14. 验证要点

1. 选中一个 `pbs` 服务，如果它被 3 个不同 `flowtrans.xml` 直接编排，流程编排层必须出现 3 个实例节点。
2. 这 3 个实例继续正确归并到联机交易层，同名交易去重。
3. 如果一个服务只被其他服务调用、但未被任何 flow 直接编排，则页面只显示上游服务层，不显示流程编排层和联机交易层节点。
4. 如果一个服务既有上游服务调用，又自己是直接 flow step，则两条路径都保留。
5. 服务层节点始终保留 `pbs / pcs / service` 原始类型，不因为进入编排层而改型。
6. 表级分析和构件分析行为不能回归。
