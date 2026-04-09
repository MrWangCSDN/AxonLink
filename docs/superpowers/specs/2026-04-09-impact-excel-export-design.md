# 影响分析 Excel 导出设计

日期：2026-04-09

## 1. 目标

为影响分析页面增加 Excel 导出能力，支持：

- 单个导出：导出当前选中的表级分析、构件分析或服务分析结果
- 全量导出：导出当前分析模式下的全部结果

本次导出只关注“影响到了哪些节点”，不导出图上的连线关系。

## 2. 范围

本次仅覆盖以下 3 个分析模式：

- 表级分析
- 构件分析
- 服务分析

不在本次范围内的内容：

- 图上边关系导出
- 交易链路页面导出
- 浏览器端生成 Excel
- 实时全量重新计算导出内容

## 3. 设计原则

- 导出内容直接复用当前影响分析缓存，不重新实时扫描 Neo4j
- 单个导出与全量导出都走后端生成 Excel
- Excel 只展示“受影响节点集合”
- 每一层节点都必须去重
- 联机交易层继续沿用当前影响分析语义，按交易名去重

## 4. 前端交互

导出入口放在影响分析页头，位置在目标选择框左侧。

新增两个按钮：

- 导出当前
- 导出全部

交互规则：

- 导出当前
  - 当前存在已选目标时可点击
  - 当前无选中目标时禁用
- 导出全部
  - 当前模式目录为空时禁用
  - 当前模式目录非空时可点击

按钮行为按当前 `impactMode` 生效：

- `table`
  - 导出当前：导出当前选中的表
  - 导出全部：导出全部表级影响结果
- `component`
  - 导出当前：导出当前选中的构件方法
  - 导出全部：导出全部构件方法影响结果
- `service`
  - 导出当前：导出当前选中的服务方法
  - 导出全部：导出全部服务方法影响结果

前端职责：

- 发起下载请求
- 接收后端返回的 Excel blob
- 触发浏览器下载

前端不负责：

- 拼装 Excel
- 全量遍历影响图
- 去重逻辑计算

## 5. 后端接口

新增导出接口，统一挂在 `/api/flowtran/impact/export` 下。

### 5.1 单个导出

`GET /api/flowtran/impact/export/{mode}/{id}`

说明：

- `{mode}` 允许 `table`、`component`、`service`
- `{id}` 为当前选中的根节点标识

返回：

- `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`

### 5.2 全量导出

`GET /api/flowtran/impact/export/{mode}/all`

说明：

- `{mode}` 允许 `table`、`component`、`service`
- 导出当前模式下的全部影响结果

返回：

- `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`

## 6. 数据来源

Excel 导出必须只读当前影响分析缓存。

缓存来源：

- 表级分析：`table impact cache`
- 构件分析：`component impact cache`
- 服务分析：`service impact cache`

单个导出流程：

- 通过当前模式和根节点 ID 读取单条 `ImpactResult`
- 将结果按层展平并写入 workbook

全量导出流程：

- 遍历当前模式下的整张缓存 map
- 将每个根节点的 `ImpactResult` 转为导出行
- 按 sheet 写入 workbook

不允许在导出请求期间：

- 重新构建影响图
- 重新跑全量 Neo4j 聚合
- 从前端传整张图回来

## 7. Excel 内容模型

导出内容只展示每个根节点影响到的层级节点集合。

不导出：

- `edges`
- `from`
- `to`
- 节点之间的路径关系

### 7.1 表级分析

sheet：

- 汇总
- 构件
- 服务
- 交易

抽取规则：

- 构件：取构件层全部节点，按 `id` 去重
- 服务：取服务层全部节点，按 `id` 去重
- 交易：取交易层全部节点，按交易名去重

### 7.2 构件分析

sheet：

- 汇总
- 服务
- 交易

抽取规则：

- 服务：取服务层全部节点，按 `id` 去重
- 交易：取交易层全部节点，按交易名去重

### 7.3 服务分析

sheet：

- 汇总
- 上游服务
- 流程编排
- 交易

抽取规则：

- 上游服务：取上游服务层节点，按 `id` 去重
- 流程编排：取流程编排层节点，按 `id` 去重
- 交易：取交易层全部节点，按交易名去重

## 8. Sheet 列定义

### 8.1 汇总 sheet

每一行代表一个根节点。

列定义：

- 根节点ID
- 根节点名称
- 根节点领域
- 构件数
- 服务数
- 流程编排数
- 交易数

说明：

- 表级分析：构件数、服务数、交易数有值；流程编排数留空
- 构件分析：服务数、交易数有值；构件数、流程编排数留空
- 服务分析：服务数表示“上游服务数”，流程编排数和交易数有值

### 8.2 层级 sheet

每一行代表一个“根节点 -> 目标节点”的去重结果。

列定义：

- 根节点ID
- 根节点名称
- 根节点领域
- 目标ID
- 目标名称
- 目标类型
- 目标领域

说明：

- 目标类型直接使用当前节点原始类型
- 服务分析的“流程编排” sheet 允许展示 `method`、`pbs`、`pcs`
- 交易 sheet 中目标类型固定为 `transaction`

## 9. 去重规则

统一去重规则如下：

- 构件、服务、流程编排：按 `目标ID` 去重
- 联机交易：按交易名去重

交易层补充规则：

- Excel 以交易名作为最终去重口径
- 如需稳定标识，可保留一个代表性的 `txId/code` 作为 `目标ID`
- 同名交易只保留一行

## 10. 文件命名

### 10.1 单个导出

- `impact-table-{id}-{timestamp}.xlsx`
- `impact-component-{id}-{timestamp}.xlsx`
- `impact-service-{id}-{timestamp}.xlsx`

### 10.2 全量导出

- `impact-table-all-{timestamp}.xlsx`
- `impact-component-all-{timestamp}.xlsx`
- `impact-service-all-{timestamp}.xlsx`

时间戳格式采用：

- `yyyyMMdd_HHmm`

## 11. 实现边界

新增独立导出服务：

- `FlowtranImpactExportService`

职责：

- 从缓存读取 `ImpactResult`
- 将 `ImpactResult` 展平为导出行
- 用 Apache POI 生成 workbook

不把 Excel 逻辑塞进：

- `FlowtranImpactServiceImpl`
- `FlowtranImpactProjectionCache`

控制器层仅负责：

- 解析 `mode` 和 `id`
- 调用导出服务
- 返回文件流和下载头

## 12. 验证点

- 表级分析支持导出当前和导出全部
- 构件分析支持导出当前和导出全部
- 服务分析支持导出当前和导出全部
- 导出文件能被 Excel 正常打开
- Excel 中不包含图边关系字段
- 各层节点已按规则去重
- 交易层按交易名去重
- 导出请求不触发实时全量影响分析重算
- 当前缓存未就绪时，导出接口返回明确错误或空结果文件，不长时间阻塞

