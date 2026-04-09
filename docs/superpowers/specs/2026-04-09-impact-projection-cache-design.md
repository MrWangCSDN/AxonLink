# 影响分析投影缓存性能优化设计

日期：2026-04-09

## 1. 背景

当前表级分析、构件分析、服务分析的真实影响图接口虽然已经接通，但请求性能不可接受。

现状根因在于 [FlowtranImpactServiceImpl.java](/Users/wangshanhe/Desktop/myproject/axon-link-server/src/main/java/com/axonlink/service/impl/FlowtranImpactServiceImpl.java) 中：

- `getTableImpact(tableId)`
- `getComponentImpact(componentId)`
- `getServiceImpact(serviceId)`

都会在请求期触发全量交易扫描与影响模型构建：

- 读取全量 `Transaction`
- 对每笔交易调用 `buildTransactionModel(...)`
- 再从这些全量模型里筛选当前目标根节点

这条路径的复杂度接近：

`全量交易数 × 单交易图遍历成本`

结果就是：

- 目录接口和统计接口很快
- 真实影响图接口很慢
- 表级分析和服务分析在当前数据集上会超时

## 2. 目标

1. 将 `table/component/service` 三类影响图的重计算从请求期移到异步 build 期。
2. 请求期只做内存读取，不再实时扫描全量交易。
3. 构建期只扫描一次全量交易模型，同时产出三套影响图投影。
4. 构建完成后同时生成本地快照文件和内存缓存。
5. 服务启动时优先加载快照，缓存就绪后接口立即可用。
6. 缓存未就绪时直接返回空图，不回退到实时全量计算。
7. 保持现有表级、构件级、服务级的语义和前端展示层级不变。

## 3. 非目标

1. 本次不改影响分析页面的交互和主视觉结构。
2. 本次不改交易链路接口和单交易链路页面的组装逻辑。
3. 本次不把影响图聚合结果回写 Neo4j。
4. 本次不做多实例共享缓存或分布式缓存。
5. 本次不更改影响分析统计接口 `/api/flowtran/impact/stats` 的语义。

## 4. 选型结论

最终采用：

- 异步 build 预计算
- 本地快照持久化
- 启动时快照加载
- 请求期只读内存缓存

不采用以下方案：

### 4.1 请求期实时计算

这是当前方案，已经证明在真实数据量下无法接受。

### 4.2 预计算后回写 Neo4j

虽然可以避免请求期实时计算，但会把聚合结果再次写入图库，带来：

- 数据重复
- 图模型膨胀
- build 与清理逻辑复杂化

### 4.3 只做内存预计算，不落快照

虽然请求期也会很快，但服务重启后缓存全部丢失，必须重新 build 才能恢复，不符合当前运维习惯。

## 5. 总体架构

新增一个独立的影响图投影缓存组件：

- `FlowtranImpactProjectionCache`

职责只包括：

1. 管理三套投影缓存的内存索引
2. 从本地快照文件加载缓存
3. 将构建结果写入快照
4. 原子切换新旧缓存

现有 [Neo4jGraphBuilder.java](/Users/wangshanhe/Desktop/myproject/axon-link-server/src/main/java/com/axonlink/service/Neo4jGraphBuilder.java) 的异步 build 流程新增一个阶段：

- `phase5_impact_projection`

阶段顺序调整为：

1. `collect`
2. `phase1_declarations`
3. `phase2_calls`
4. `phase3_write`
5. `phase4_flowtrans`
6. `phase5_impact_projection`
7. `done`

请求期：

- [FlowtranImpactServiceImpl.java](/Users/wangshanhe/Desktop/myproject/axon-link-server/src/main/java/com/axonlink/service/impl/FlowtranImpactServiceImpl.java) 不再实时构建全量交易模型
- 只从 `FlowtranImpactProjectionCache` 中读取结果

## 6. 缓存模型

### 6.1 内存结构

缓存内部维护三份只读 map：

- `Map<String, Map<String, Object>> tableImpacts`
- `Map<String, Map<String, Object>> componentImpacts`
- `Map<String, Map<String, Object>> serviceImpacts`

每个 value 都是已经可以直接返回前端的 `ImpactResult` 结构。

### 6.2 状态结构

缓存元信息至少包括：

- `ready`
- `builtAt`
- `datasource`
- `workspaceRoots`
- `txCount`
- `tableKeyCount`
- `componentKeyCount`
- `serviceKeyCount`
- `snapshotPath`

## 7. 快照设计

### 7.1 路径

快照不放在 `target/`，避免打包和清理时被覆盖。

默认路径：

- `data/cache/impact-projection-v1.json.gz`

### 7.2 文件结构

```json
{
  "meta": {
    "version": 1,
    "builtAt": "2026-04-09T15:30:00",
    "datasource": "local",
    "workspaceRoots": "/path/a,/path/b",
    "txCount": 1234
  },
  "tables": {
    "DpAccQuery": { "mode": "table", "...": "..." }
  },
  "components": {
    "XxxPbcb.Yyy": { "mode": "component", "...": "..." }
  },
  "services": {
    "XxxPbs.Zzz": { "mode": "service", "...": "..." }
  }
}
```

### 7.3 写入策略

必须使用临时文件 + 原子替换：

1. 写入 `impact-projection-v1.json.gz.tmp`
2. 写入成功后原子 `move`
3. 替换正式文件 `impact-projection-v1.json.gz`

目标是避免 build 中途失败时破坏旧快照。

## 8. 构建阶段设计

### 8.1 新阶段

在 [Neo4jGraphBuilder.java](/Users/wangshanhe/Desktop/myproject/axon-link-server/src/main/java/com/axonlink/service/Neo4jGraphBuilder.java) 增加：

- `phase5_impact_projection`

该阶段只在：

- Neo4j 图已写入完成
- flowtrans / serviceType 元数据补图完成

之后执行。

### 8.2 单次全量模型构建

`phase5` 中不能重复做三遍扫描。

必须先一次性构建：

- `List<TransactionImpactModel> models`

这些模型的语义继续沿用当前 [FlowtranImpactServiceImpl.java](/Users/wangshanhe/Desktop/myproject/axon-link-server/src/main/java/com/axonlink/service/impl/FlowtranImpactServiceImpl.java) 中的 `buildTransactionModel(...)`，不改变表级、构件级、服务级的业务语义。

### 8.3 倒排索引

在遍历 `models` 时同步生成轻量倒排索引：

- `tableId -> impactedTxIndexes`
- `componentId -> impactedTxIndexes`
- `serviceId -> impactedTxIndexes`
- `serviceId -> directOrchestrationRefs`

其中：

- `impactedTxIndexes` 引用 `models` 的索引，不重复拷贝整份模型
- `directOrchestrationRefs` 在 build 期预取，不再在请求期临时查 Neo4j

### 8.4 投影组装

在拿到：

- 全量 `models`
- 倒排索引

之后，再统一产出三套结果：

- `tableImpacts`
- `componentImpacts`
- `serviceImpacts`

组装逻辑要求：

- 表级分析仍保持 `表 -> 构件 -> 服务 -> 交易`
- 构件级分析仍保持 `构件 -> 服务 -> 交易`
- 服务级分析仍保持 `服务 -> 上游服务 -> 流程编排 -> 交易`
- 三者都继续沿用已确认的 `direct flow step` 交易归属规则

## 9. 请求路径设计

请求期完全禁止回退到全量实时计算。

### 9.1 表级分析

`getTableImpact(tableId)`：

1. 查 `tableImpacts.get(tableId)`
2. 命中则返回
3. 未命中则返回空图

### 9.2 构件级分析

`getComponentImpact(componentId)`：

1. 查 `componentImpacts.get(componentId)`
2. 命中则返回
3. 未命中则返回空图

### 9.3 服务级分析

`getServiceImpact(serviceId)`：

1. 查 `serviceImpacts.get(serviceId)`
2. 命中则返回
3. 未命中则返回空图

## 10. 缓存未就绪策略

用户已确认：

- 缓存未就绪时，允许直接返回空图
- 不要求请求期回退到实时全量计算

因此，接口行为统一为：

- 缓存已就绪且命中：返回真实结果
- 缓存已就绪但未命中：返回空图
- 缓存未就绪：返回空图

前端无需区分这三种情况，保持当前空状态展示即可。

## 11. 启动加载策略

服务启动时：

1. 尝试读取 `impact-projection-v1.json.gz`
2. 读取成功则直接加载到内存
3. 读取失败或文件不存在，则缓存保持未就绪

此时：

- 目录接口和统计接口继续可用
- 影响图接口返回空图
- 等下一次异步 build 完成后再切换到真实缓存

## 12. 原子切换策略

build 开始后，线上继续读旧缓存。

只有当以下全部成功后，才切换新缓存：

1. 三套 map 全部构建完成
2. 快照文件写入成功
3. 快照文件原子替换成功

然后再整体替换内存引用。

禁止出现：

- 表级已切新缓存，但服务级还是旧缓存
- 内存已切换，但快照没落成
- 快照损坏覆盖旧文件

## 13. 配置设计

在现有 `flowtran.cache.enabled` 基础上增加独立配置：

```yaml
flowtran:
  cache:
    enabled: true
    impact-projection-enabled: true
    impact-projection-dir: data/cache
    impact-projection-load-on-startup: true
```

语义：

- `impact-projection-enabled`
  - 是否启用影响图投影缓存
- `impact-projection-dir`
  - 快照目录
- `impact-projection-load-on-startup`
  - 启动时是否尝试读取快照

## 14. 状态接口

新增一个轻量状态接口：

- `GET /api/flowtran/impact/cache/status`

返回字段建议：

```json
{
  "ready": true,
  "builtAt": "2026-04-09T15:30:00",
  "datasource": "local",
  "tableKeys": 310,
  "componentKeys": 0,
  "serviceKeys": 1441,
  "snapshotPath": "data/cache/impact-projection-v1.json.gz"
}
```

目的：

- 快速判断为什么页面返回空图
- 快速判断当前缓存是否已经构建完成
- 快速判断当前缓存是否与当前环境匹配

## 15. 实施边界

本次只做：

1. 影响图投影缓存
2. 新增 build `phase5`
3. 启动加载快照
4. 请求期改为只读缓存
5. 新增缓存状态接口

本次不做：

1. 影响图分页
2. 目录接口改造
3. Neo4j 聚合结果持久化
4. 前端额外提示“缓存未就绪”

## 16. 验证重点

1. `phase5_impact_projection` 会在异步 build 中稳定执行。
2. build 期间旧缓存仍可读。
3. build 成功后新缓存原子切换。
4. 启动时若存在快照，服务无需再次 build 即可提供影响图查询。
5. 缓存未就绪时，表级/构件级/服务级接口直接返回空图，不再超时。
6. 请求期不再触发全量交易扫描与 `buildTransactionModel(...)`。
7. 表级、构件级、服务级的语义与现有 spec 保持一致，不发生层级回归。
8. 当前外网数据集上，目录/统计接口保持原有响应速度，真实影响图接口在缓存就绪后显著快于当前实现。
