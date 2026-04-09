# 表级影响分析全领域反查设计

## 背景

当前“影响分析 -> 表级分析”来自前端本地聚合，输入只包含当前页面已加载的交易链路。这个实现和“从任意表出发，全领域向上溯源到构件、服务、联机交易”的目标不一致，存在三个根本问题：

1. 结果范围不完整，只覆盖当前页面已加载的交易。
2. 交易链路是单交易、单向展示；表级影响分析需要全领域、反向聚合，语义不同。
3. 现有前端聚合逻辑无法稳定表达 `flowtrans.xml` 直接编排步骤与代码调用步骤的区别。

本次改造的目标是新增一套完全独立的后端表级影响分析能力，由 Neo4j 直接返回 4 层影响图，前端继续沿用当前页面与 Figma Make 的视觉结构渲染。

## 目标

1. 新增一个后端接口，输入 `tableId`，返回全领域的表级影响图。
2. 结果严格保持 4 层：`表 -> 构件 -> 服务 -> 联机交易`。
3. 构件层继续按 `pbcb/pbcp`、`pbcc`、`pbct` 三类落位。
4. 服务层继续按 `method`、`pbs`、`pcs` 三类落位。
5. 只有属于某个 `xxx.flowtrans.xml` 直接 `flow` 步骤的服务节点，才能向上归集为联机交易。
6. 联机交易按交易名去重。
7. 现有交易链路页面和接口保持原语义，不受本次改造影响。

## 非目标

1. 本次不改“交易链路”单交易页面的后端组装逻辑。
2. 本次不重做构件级、服务级影响分析。
3. 本次不在页面展示 DAO 层。
4. 本次不修改 Figma 对齐的主视觉结构、子列布局和关系图交互原则。

## 设计原则

1. 表级影响分析与交易链路彻底隔离，避免数据语义互相污染。
2. DAO 只作为查询跳点使用，不进入返回结果。
3. 后端直接返回前端可消费的影响图结构，前端不再根据当前页交易做二次聚合。
4. 服务是否能归集到联机交易，不按 `type=method` 判断，而按“是否属于某个交易的直接 flow step”判断。
5. 同名交易去重必须稳定、可重复，避免节点 ID 在多次查询间抖动。
6. 页面中的调用关系全部采用虚线关系图呈现，悬停节点时只强调该节点的一跳邻接关系，不展开整条链路。

## 现有语义澄清

### 交易链路

- 交易链路页面是“从单个交易出发”的单向展开。
- `flowtrans.xml` 的 `flow` 节点里本来就可能出现两类直接步骤：
  - `FlowMethodStep`，前端类型为 `method`
  - `FlowServiceStep`，前端类型为 `pbs` 或 `pcs`

### 表级影响分析

- 表级影响分析不是复用单交易链路，也不是把当前页所有交易链路拼起来。
- 它是“从表出发”的全领域反向聚合。
- 这里的“流程编排”语义不是单独一种 `type`，而是“直接出现在某个交易 `flowtrans.xml` flow 节点中的服务层节点集合”。
- 这些直接 flow step 在返回结果中仍然保留原始类型：
  - `method`
  - `pbs`
  - `pcs`
- 代码中通过 `getInstance(xxx)` 或其他实现方式再调用出来的 `pbs/pcs`，如果不是某个交易的直接 flow step，则只留在服务层，不直接挂联机交易。

## 总体方案

采用独立后端接口方案：

- 新增 `FlowtranImpactService`
- 新增 `GET /api/flowtran/impact/table/{tableId}`
- 该接口直接基于 Neo4j 全量图执行反向溯源与聚合
- 返回结构直接对齐前端当前 `ImpactResult` 语义

交易链路现有实现保留不动，不复用 [FlowtranServiceImpl.java](/Users/wangshanhe/Desktop/myproject/axon-link-server/src/main/java/com/axonlink/service/impl/FlowtranServiceImpl.java) 的 `getChain(txId)` 组装路径。

## 接口设计

### 路由

`GET /api/flowtran/impact/table/{tableId}`

### 请求参数

- `tableId`: 表英文名，例如 `DpAccQuery`

可选扩展参数暂不引入，例如深度、领域过滤、分页等，先保证一版完整结果。

### 返回结构

返回数据直接对齐前端当前影响分析组件的消费模型：

```json
{
  "mode": "table",
  "root": {
    "id": "DpAccQuery",
    "name": "存款账户主档",
    "desc": "ccbs-dept-impl",
    "domainId": "deposit",
    "nodeType": "table"
  },
  "levels": [
    [
      { "id": "pbcb_xxx", "name": "业务构件A", "type": "pbcb", "domainId": "deposit", "nodeType": "component" }
    ],
    [
      { "id": "dpxx.queryMethod", "name": "查询方法", "type": "method", "domainId": "deposit", "nodeType": "service" },
      { "id": "pbstype.serviceA", "name": "服务A", "type": "pbs", "domainId": "deposit", "nodeType": "service" },
      { "id": "pcstype.serviceB", "name": "服务B", "type": "pcs", "domainId": "deposit", "nodeType": "service" }
    ],
    [
      { "id": "dp2524", "name": "账户查询交易", "code": "dp2524", "domainId": "deposit", "nodeType": "transaction" }
    ]
  ],
  "edges": [
    { "from": "DpAccQuery", "to": "pbcb_xxx" },
    { "from": "pbcb_xxx", "to": "pbcc_xxx", "isIntraLayer": true },
    { "from": "pbcc_xxx", "to": "pbstype.serviceA" },
    { "from": "pbstype.serviceA", "to": "dp2524" }
  ],
  "stats": {
    "components": 12,
    "services": 9,
    "transactions": 3
  }
}
```

## 返回字段语义

### root

- 固定是表节点
- `desc` 使用表来源工程名，便于用户区分同名或同域场景

### levels[0]

- 构件层
- 所有节点 `nodeType = "component"`
- `type` 只允许：
  - `pbcb`
  - `pbcp`
  - `pbcc`
  - `pbct`

### levels[1]

- 服务层
- 所有节点 `nodeType = "service"`
- `type` 只允许：
  - `method`
  - `pbs`
  - `pcs`
- 服务节点始终保留原始 `type`，不因为它属于某个交易的直接 `flow` 步骤而改型或换列
- 也就是说，直接 `flow` 步骤里的 `pbs/pcs` 仍然展示在各自子列中，只是额外具备向上归集联机交易的资格

### levels[2]

- 联机交易层
- 所有节点 `nodeType = "transaction"`
- 交易节点按“交易名”去重

### transactions 去重规则

联机交易节点的去重键使用 `Transaction.longname`。

为了保持前端节点稳定：

1. 同名交易合并成一个交易节点。
2. 合并后节点的：
   - `name` 取该交易名
   - `id` 取同名交易里字典序最小的 `tx.id`
   - `code` 同样取该代表 `tx.id`
3. 可以在后端内部附带 `mergedTxIds` 作为调试信息，但本期不返回给前端。

这样既满足“按交易名去重”，又能保证前端节点底部仍有稳定 `code` 可展示。

## 交互与可视化约束

### 关系线

- 页面中的调用关系全部使用虚线呈现
- 适用范围包括：
  - 跨层边：`table -> component`、`component -> service`、`service -> transaction`
  - 同层边：`component -> component`、`service -> service`
- 不因为边类型不同切换成实线；差异只通过颜色、透明度、箭头和层位表达

### 悬停节点的“左邻右舍”

- 鼠标悬停任意节点时，只展示该节点的一跳邻接关系
- 其中必须点亮该节点在上一层和下一层的直接调用关系，以及对应连线
- “左邻右舍”定义为：
  - 左侧上一层直接连到该节点的节点
  - 右侧下一层由该节点直接连出的节点
  - 同层与该节点有直接调用关系的节点
- 不在悬停时自动展开两跳及以上路径，避免把全图重新点亮

### 悬停时的视觉效果

- 当前节点高亮
- 与当前节点直接相连的节点和边高亮
- 非直接相连的节点与边降噪显示
- 高亮只改变强调程度，不改变节点所在层、子列和连线语义

## 查询模型

### 目标图层

最终输出仍然是：

`Table -> Component -> Service -> Transaction`

其中 DAO 不展示，但仍作为反向查询跳点：

`Table -> DaoMethod <- Method`

### 查询思路

不复用交易链路组装逻辑，直接在 Neo4j 执行全领域反向聚合，分四步完成：

1. 从表找到直接访问该表的方法入口
2. 将这些方法归并到构件层与服务层逻辑节点
3. 继续向上扩展同层与跨层调用，形成完整服务/构件影响子图
4. 识别哪些服务节点属于某个交易的直接 flow step，再聚合到联机交易

## 详细算法

### 步骤 1：表到直接代码入口

起点：

- `MATCH (table:Table {id: $tableId})`

第一跳：

- `MATCH (table)-[:EXPOSES_DAO]->(dao:DaoMethod)`

第二跳：

- 反向查找直接调用 DAO 的方法
- `MATCH (caller:Method)-[:DAO_CALLS]->(dao)`

这一步得到的是“直接访问该表”的 Java 方法集合。

### 步骤 2：方法映射为逻辑节点

这一层不直接把 `Method` 暴露给前端，而是映射为当前系统已有的逻辑节点：

- 逻辑服务节点
- 逻辑构件节点

映射策略复用现有 `BoundaryResolver` 的解析语义，但放到新服务中，不直接依赖交易链路组装过程。

优先级如下：

1. 若 `Method` 能映射到 `ServiceOperation` 实现，则归为服务节点
2. 若映射出的 `ServiceType.nodeKind` 为 `pbs/pcs`，进入服务层
3. 若映射结果可进一步归到构件语义，则进入构件层
4. 若无法映射到逻辑节点，则不进入返回结果

这里的关键是“返回结果只保留逻辑层节点，不暴露纯技术 AST 方法节点”。

### 步骤 3：扩展构件层与服务层

#### 构件层扩展

基于已命中的构件节点，继续沿构件内部调用关系扩展：

- 保留 `component -> component` 的同层边
- 返回时标记 `isIntraLayer = true`

构件类型分栏规则：

- `pbcb / pbcp` 归“业务/产品构件”
- `pbcc` 归“公共构件”
- `pbct` 归“技术构件”

后端不做视觉分栏，只返回 `type`。

#### 服务层扩展

基于已命中的服务节点，继续沿服务内部调用关系扩展：

- 保留 `service -> service` 的同层边
- 返回时标记 `isIntraLayer = true`

服务类型分栏规则：

- `method`
- `pbs`
- `pcs`

这里不把“流程编排”做成额外 `type`，而是保留原始类型，由“是否是交易直接 flow step”决定能否继续向上归集交易。

### 步骤 4：服务向上归集联机交易

这是本次设计最重要的规则。

#### 可归集条件

某个服务节点可以向上归集到联机交易，当且仅当：

- 它本身就是某个 `Transaction -> FlowBlock -> Step` 路径中的直接步骤
- 且该步骤类型可以是：
  - `FlowMethodStep`
  - `FlowServiceStep`

换句话说，不按 `type == method` 判断是否能挂交易，而按“是否为直接 flow step”判断。

#### 不可归集条件

以下节点不能直接挂联机交易：

- 仅在代码实现类中通过 `getInstance(xxx)` 或其他方式被调用出来的 `pbs/pcs`
- 不是任何交易直接 flow step 的中间服务节点

#### 归集方式

对于已命中的服务节点，查询它是否与某个交易直接 flow step 对应：

- `FlowMethodStep` 通过 `RESOLVES_TO_METHOD` 对应
- `FlowServiceStep` 通过 `CALLS_SERVICE` 对应到 `ServiceOperation(serviceTypeId, serviceId)`

命中后，记录该服务节点到交易节点的跨层边：

- `{ from: serviceId, to: representativeTxId }`

这意味着服务到交易的边可以来自三类节点：

- `method -> transaction`
- `pbs -> transaction`
- `pcs -> transaction`

是否存在这条边，只由“它是不是某个交易的直接 flow step”决定，不由服务列标题决定。

### 同名交易去重

多个交易 `longname` 相同则合并：

- 只保留一个交易节点
- 所有来自服务层的边都指向这个代表节点

## 后端模块划分

建议新增以下独立模块：

### Controller

[FlowtranController.java](/Users/wangshanhe/Desktop/myproject/axon-link-server/src/main/java/com/axonlink/controller/FlowtranController.java)

新增接口：

- `GET /api/flowtran/impact/table/{tableId}`

### Service

新增独立 service，例如：

- `FlowtranImpactService`
- `FlowtranImpactServiceImpl`

职责：

1. 表级影响图查询入口
2. Neo4j 查询与聚合
3. 组装前端可直接消费的返回结构

### Assembler / Query Helper

建议在 service 内部拆小类或私有 helper：

1. `ImpactBoundaryResolver`
   - 把 `Method / ServiceOperation / ServiceType` 映射到逻辑服务或构件
2. `TableImpactGraphAssembler`
   - 负责组装 `root / levels / edges / stats`
3. `FlowStepMatcher`
   - 负责判断服务节点是否为某个交易的直接 flow step

这样做的目的是让新能力不侵入现有交易链路 service。

## 前端接入方案

### API

在 [index.js](/Users/wangshanhe/Desktop/myproject/axon-link-server/frontend/src/api/index.js) 新增：

- `getFlowtranTableImpact(tableId)`

### 页面接入

在 [TransactionAnalysis.vue](/Users/wangshanhe/Desktop/myproject/axon-link-server/frontend/src/views/TransactionAnalysis.vue) 中：

1. 当 `impactMode === 'table'` 且用户在下拉框选择表时：
   - 不再调用本地 `analyzeImpact(...)`
   - 改为调用 `getFlowtranTableImpact(tableId)`
2. 将接口返回结果直接写入 `impactResult`
3. 若接口返回空图，则沿用当前空状态视觉

### 保持不动的前端结构

以下组件只做数据适配，不改主结构：

- [ImpactAnalysisPage.vue](/Users/wangshanhe/Desktop/myproject/axon-link-server/frontend/src/components/impact/ImpactAnalysisPage.vue)
- [ImpactFlowDiagram.vue](/Users/wangshanhe/Desktop/myproject/axon-link-server/frontend/src/components/impact/ImpactFlowDiagram.vue)
- [TargetDropdown.vue](/Users/wangshanhe/Desktop/myproject/axon-link-server/frontend/src/components/impact/TargetDropdown.vue)

它们继续按当前 Figma 对齐的子列布局渲染。

当前子列顺序保持如下：

- 构件层：`pbcb/pbcp -> pbcc -> pbct`
- 服务层：`pbs -> pcs -> method`

## 错误处理

### 后端

- Neo4j 不可用：返回空结果或明确错误信息，不影响现有交易链路接口
- 表不存在：返回空结果，不抛 500
- 映射失败的技术节点：跳过，不把脏数据带到前端

### 前端

- 接口失败时保留当前页面框架，提示“加载表级影响分析失败”
- 不回退到本地聚合，避免用户误以为结果是全量

## 性能考虑

1. 首版不做分页，优先保证结果正确性。
2. 需要在后端查询中控制遍历深度，避免无界向上扩散。
3. 对 `Method -> LogicalSeed`、`ServiceOperation -> FlowStep` 的映射可做本次请求内缓存。
4. 若某个表命中的交易数量非常大，后续再考虑：
   - 限制最大节点数
   - 返回裁剪标记
   - 前端提示“结果已截断”

本期先不引入截断策略，除非测试发现明显性能问题。

## 验证计划

### 后端验证

1. 选择一个已知表，确认能查到全领域影响结果
2. 确认返回的构件 `type` 只落在 `pbcb/pbcp/pbcc/pbct`
3. 确认返回的服务 `type` 只落在 `method/pbs/pcs`
4. 确认只有直接 flow step 对应的服务节点才连向交易
5. 确认同名交易只出现一个节点

### 前端验证

1. 选择表后页面能显示完整 4 层图
2. 构件层三列顺序与现有 Figma 一致
3. 服务层三列顺序与现有 Figma 一致
4. 同层边仍按 `isIntraLayer` 正确绘制
5. 现有交易链路页面不回归

## 风险

1. `Method -> 逻辑服务/构件` 映射覆盖率不足时，可能导致影响图比实际小。
2. 某些 `flowtrans.xml` 中步骤信息不完整，可能导致服务节点无法正确归属交易。
3. 同名交易去重后，代表节点 `id/code` 选取不当可能造成用户困惑。

## 风险缓解

1. 采用稳定代表节点规则：同名交易取字典序最小 `tx.id`
2. 保留请求内缓存与日志，便于定位映射缺失点
3. 先只做表级分析，避免同时改构件级/服务级逻辑带来额外变量

## 实施顺序

1. 新增表级影响分析 service 与接口
2. 完成 Neo4j 查询与返回结构组装
3. 前端表级分析切换到新接口
4. 联调并验证 4 层图正确性
5. 回归交易链路页面

## 决策结论

采用“完全分离实现”的方法：

- 不复用交易链路组装逻辑
- 新增独立后端接口
- 全领域反向聚合由 Neo4j 直接完成
- 前端继续沿用当前 Figma 对齐页面结构
