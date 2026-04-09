# 影响分析统计与 XML 入库修正设计

日期：2026-04-09

## 1. 背景

当前影响分析左侧导航包含 3 个入口：

- 表级分析
- 构件分析
- 服务分析

其中表级分析已经能显示总数，但构件分析和服务分析的数量仍然依赖前端本地数据推断，无法代表 Neo4j 中的全量已落库结果。

同时，当前 XML 元数据扫描存在两个问题：

- 扫描范围仍包含 `target/classes`，会把源码 XML 和编译产物 XML 重复扫描
- 构件和服务的统计虽然已经接近 `<service>` 节点粒度，但前端没有一个统一、稳定的后端统计入口可用

本设计的目标是把影响分析的 3 个数量统一收口到 Neo4j 已落库结果，并修正 XML 扫描范围与入库字段，保证后续影响分析的构件/服务语义稳定。

## 2. 目标

- 表级分析、构件分析、服务分析都显示 Neo4j 中已落库的全量数量
- 构件与服务的统计粒度统一到 XML 中每个 `<service>` 节点
- 所有相关 XML 扫描都明确排除 `target/**`
- 构件和服务入库时必须带上 `domainKey`
- 前端页面只读取统计接口，不在打开页面时实时扫描磁盘 XML
- 与现有交易链路、表级影响分析图保持隔离，不互相污染

## 3. 非目标

- 本次不新增构件分析、服务分析的全领域影响图接口
- 本次不修改交易链路图的语义和返回结构
- 本次不让前端在页面访问时触发 XML 重扫或 Neo4j 重建
- 本次不调整现有表级影响分析图的布局和交互

## 4. 统计口径

影响分析左侧导航显示的 3 个数量定义如下：

- `tableCount`
  - 统计 Neo4j 中 `Table` 节点总数
- `componentCount`
  - 统计 Neo4j 中构件类 `ServiceOperation` 节点总数
- `serviceCount`
  - 统计 Neo4j 中服务类 `ServiceOperation` 节点总数

构件与服务的分类规则如下：

- 构件
  - `pbcb`
  - `pbcp`
  - `pbcc`
  - `pbct`
- 服务
  - `pbs`
  - `pcs`
  - `service`

其中旧的 `*.serviceType.xml` 统一归并到服务分类，`nodeKind` 统一命名为 `service`。

统计粒度固定为方法级节点，而不是文件级节点：

- 一个 `*.pbcb.xml` 文件里有 8 个 `<service>` 节点，则贡献 8 个构件数量
- 一个 `*.pbs.xml` 文件里有 5 个 `<service>` 节点，则贡献 5 个服务数量

## 5. XML 类型归类规则

### 5.1 构件 XML

以下文件后缀视为构件定义文件：

- `*.pbcb.xml` -> `nodeKind=pbcb`
- `*.pbcp.xml` -> `nodeKind=pbcp`
- `*.pbcc.xml` -> `nodeKind=pbcc`
- `*.pbct.xml` -> `nodeKind=pbct`

### 5.2 服务 XML

以下文件后缀视为服务定义文件：

- `*.pbs.xml` -> `nodeKind=pbs`
- `*.pcs.xml` -> `nodeKind=pcs`
- `*.serviceType.xml` -> `nodeKind=service`

### 5.3 非本次新增口径

实现类 XML 如下：

- `*.pbsImpl.xml`
- `*.pcsImpl.xml`
- `*.pbcbImpl.xml`
- `*.pbcpImpl.xml`
- `*.pbccImpl.xml`
- `*.pbctImpl.xml`

这些实现类 XML 的既有用途保持不变，但不作为本次统计数量的直接来源。统计仍以声明类 XML 中的 `<service>` 节点为准。

## 6. 扫描范围与去重策略

### 6.1 扫描范围

所有 XML 元数据扫描只认工作区源码目录下的 `src/main/resources`。

明确排除：

- 所有 `target/**`
- 所有 `target/classes/**`

### 6.2 去重原则

同一模块只允许从源码元数据根目录读取一次 XML，不允许同时读取编译产物目录。

这意味着以下两处现有逻辑都需要统一为源码优先且不扫描 `target`：

- `FlowServiceMetadataResolver`
- `FlowtransMetaGraphBuilder`

### 6.3 共享扫描规则

为避免两套扫描逻辑继续漂移，XML 元数据根目录发现规则应抽成一套共享实现，供以下流程复用：

- XML 元数据缓存预热
- Flowtrans 元数据图构建

共享规则至少要满足：

- 递归扫描工作区
- 发现 `src/main/resources`
- 在遍历阶段直接跳过 `target` 子树
- 输出稳定排序后的文件列表

## 7. 入库模型

### 7.1 节点粒度

每个 XML 声明文件：

- 对应一个 `ServiceType`

文件内每个 `<service>` 节点：

- 对应一个 `ServiceOperation`

### 7.2 必填字段

`ServiceType` 必须包含：

- `id`
- `nodeKind`
- `domainKey`
- `packagePath`
- `filePath`
- `module`

`ServiceOperation` 必须包含：

- `key`
- `serviceTypeId`
- `serviceId`
- `methodName`
- `longname`
- `nodeKind`
- `domainKey`
- `packagePath`
- `interfaceFqn`

### 7.3 领域归属

所有这批 XML 落库的 `ServiceType` 和 `ServiceOperation` 都必须带上 `domainKey`。

领域归属解析口径统一沿用现有项目规则：

1. 优先使用 XML 根节点的 `package/packagePath`
2. 结合父工程名 `parentProject`
3. 最终通过 `DomainKeyResolver.resolveByProjectOrPackage(...)` 计算 `domainKey`

`ServiceOperation` 也需要冗余写入 `domainKey`，不依赖查询时回跳 `ServiceType` 推导，原因如下：

- 统计查询更直接
- 后续按领域做影响分析过滤更直接
- 避免运行时聚合时产生多跳依赖

## 8. 性能策略

### 8.1 文件收集

目录遍历阶段直接剪枝：

- 遇到 `target` 目录时不进入其子树

这样可以避免无效 IO 和重复扫描。

### 8.2 XML 解析

XML 文件解析采用并行处理，输出内存中的 `ServiceTypeMeta` 与 `ServiceOperationMeta`。

并行范围仅限于：

- 文件解析
- XML DOM 构建
- `<service>` 节点提取

### 8.3 Neo4j 写入

Neo4j 写入继续采用批量 `MERGE`，保持单线程批量提交，不做并发写库。

原因：

- 避免并发事务把写入顺序打散
- 保持现有批量写入模型稳定
- 降低重复关系和锁竞争风险

### 8.4 表扫描并发

表相关 Java AST 扫描已经在 `Neo4jGraphBuilder` 中通过 `ForkJoinPool + parallelStream` 并行处理，本次不再额外改动表扫描的并发模型。

## 9. 后端接口设计

新增独立统计接口：

- `GET /api/flowtran/impact/stats`

返回结构：

```json
{
  "tableCount": 1234,
  "componentCount": 5678,
  "serviceCount": 4321,
  "componentBreakdown": {
    "pbcb": 2100,
    "pbcp": 1800,
    "pbcc": 900,
    "pbct": 878
  },
  "serviceBreakdown": {
    "pbs": 1700,
    "pcs": 1900,
    "service": 721
  }
}
```

说明：

- 前端本次只展示 `tableCount`、`componentCount`、`serviceCount`
- `componentBreakdown` 和 `serviceBreakdown` 本次先保留后端输出，前端不强制展示
- 保留分项结构是为了后续分类筛选、提示信息或分组统计扩展

## 10. Neo4j 统计查询口径

### 10.1 表统计

```cypher
MATCH (t:Table)
RETURN count(t) AS tableCount
```

### 10.2 构件统计

```cypher
MATCH (op:ServiceOperation)
WHERE op.nodeKind IN ['pbcb', 'pbcp', 'pbcc', 'pbct']
RETURN count(op) AS componentCount
```

### 10.3 服务统计

```cypher
MATCH (op:ServiceOperation)
WHERE op.nodeKind IN ['pbs', 'pcs', 'service']
RETURN count(op) AS serviceCount
```

### 10.4 分类统计

```cypher
MATCH (op:ServiceOperation)
WHERE op.nodeKind IN ['pbcb', 'pbcp', 'pbcc', 'pbct', 'pbs', 'pcs', 'service']
RETURN op.nodeKind AS nodeKind, count(op) AS total
```

## 11. 服务职责拆分

### 11.1 保持现有职责

- `FlowtranImpactService`
  - 继续只负责影响图查询和组装
- `FlowtranService`
  - 继续只负责领域和交易链路相关能力

### 11.2 新增职责

新增独立统计服务，例如：

- `FlowtranImpactStatsService`

职责：

- 查询表/构件/服务总数
- 查询构件与服务的分类统计
- 对外提供统一的影响分析统计结果

控制器新增：

- `FlowtranController#getImpactStats()`

这样可以保证：

- 统计接口与影响图接口分离
- 不污染 `/api/system/stats`
- 不复用 `/api/flowtran/cache/stats` 的缓存内部口径

## 12. 前端接入设计

### 12.1 API

在 `frontend/src/api/index.js` 中新增：

- `getFlowtranImpactStats()`

### 12.2 页面状态

在 `TransactionAnalysis.vue` 中新增独立状态，例如：

```js
const impactStats = ref({
  tableCount: 0,
  componentCount: 0,
  serviceCount: 0,
  componentBreakdown: {},
  serviceBreakdown: {},
})
```

页面初始化时加载一次：

- 与现有 `domains`、`systemStats` 并行或串行均可
- 用户切换 `table/component/service` 模式时不重复查询

### 12.3 侧边栏展示

`ChainImpactSidebar.vue` 新增 `impactStats` prop，并在 3 个影响分析菜单项右侧展示数量：

- `table` -> `impactStats.tableCount`
- `component` -> `impactStats.componentCount`
- `service` -> `impactStats.serviceCount`

本次仅增加数量展示，不改已有主视觉结构。

## 13. 兼容性边界

- 不修改现有交易链路接口语义
- 不修改现有表级影响分析图接口语义
- 不在本次里新增构件分析、服务分析的全领域影响图
- 不把页面访问变成后端 XML 重扫入口
- 不改变现有系统健康检查接口职责

## 14. 验证要点

### 14.1 扫描范围

- 工作区扫描不进入任何 `target/**`
- 同一模块源码 XML 不会因编译产物重复入库

### 14.2 入库粒度

- 一个 XML 文件中的多个 `<service>` 节点会生成多个 `ServiceOperation`
- `*.serviceType.xml` 会按 `nodeKind=service` 入库
- 构件 XML 只归到 `pbcb/pbcp/pbcc/pbct`
- 服务 XML 只归到 `pbs/pcs/service`

### 14.3 领域字段

- 每个 `ServiceType` 都有 `domainKey`
- 每个 `ServiceOperation` 都有 `domainKey`
- `domainKey` 与 `DomainKeyResolver` 当前规则保持一致

### 14.4 统计接口

- `tableCount` 与 Neo4j `Table` 节点数量一致
- `componentCount` 与构件类 `ServiceOperation` 数量一致
- `serviceCount` 与服务类 `ServiceOperation` 数量一致

### 14.5 前端显示

- 影响分析左侧 3 个入口都能显示数量
- 页面首次加载成功后，切换分析模式不重复统计
- 接口失败时数量安全降级为 `0`，不影响页面主流程

## 15. 实施顺序建议

1. 收敛并统一 XML 扫描规则，彻底排除 `target`
2. 修正 `ServiceOperation` 入库字段，确保带 `nodeKind` 和 `domainKey`
3. 新增影响分析统计服务与 `/api/flowtran/impact/stats`
4. 前端接入统计接口并在侧边栏展示数量
5. 执行一次全量重建，校验 Neo4j 中的统计结果

