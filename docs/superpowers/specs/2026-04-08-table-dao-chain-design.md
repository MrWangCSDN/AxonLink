# 表与 DAO 链路增强设计

## 背景

当前交易链路的数据层只展示从 `Dao` 名称去掉 `Dao` 后缀得到的字符串，实际并没有解析 `*.tables.xml`。这带来三个问题：

1. 数据层无法展示表中文名、所属领域、来源工程。
2. `odb` 方法和表被混在一起，链路不清晰。
3. 点击数据层节点时，无法稳定定位到对应的 Java DAO 方法。

目标是把数据层拆成 `Table层` 和 `Dao层`：

- `Table层` 展示表英文名、表中文名、所属领域。
- `Dao层` 展示与该表关联的 `odb` 方法。
- 代码定位入口只放在 `Dao层` 节点上。
- 只有能匹配到 `*.tables.xml` 的表才进入链路展示；未匹配到的 DAO 调用不展示在链路上。

## 范围

本次改造只覆盖以下工程中的 `*.tables.xml`：

- `ccbs-dept-impl`
- `ccbs-loan-impl`
- `ccbs-sett-impl`
- `ccbs-comm-impl`

领域映射按工程名确定：

- `ccbs-dept-impl` -> `deposit`
- `ccbs-loan-impl` -> `loan`
- `ccbs-sett-impl` -> `settlement`
- `ccbs-comm-impl` -> `public`

本次不处理未在上述工程中的表定义，不处理动态拼装 DAO 名称的特殊场景，不处理运行时 SQL。

## 设计原则

1. 保持现有编排层、服务层、构件层模型稳定，只扩展数据层。
2. 把“表”和“DAO 方法”拆成独立节点，避免一个节点同时承担两种语义。
3. Neo4j 中允许保留未匹配 `Table` 的 `DaoMethod` 调用，但交易链路接口不返回这些未匹配数据。
4. 数据层的代码定位只通过 `DaoMethod` 节点完成，避免在前端推断定位逻辑。

## 数据模型

### Neo4j 节点

#### Table

用于表示 `*.tables.xml` 中的 `<table>` 定义。

- `id`: 表英文名，对应 `<table id="...">`
- `longname`: 表中文名，对应 `<table longname="...">`
- `domainKey`: 所属领域
- `projectName`: 来源工程名
- `xmlFilePath`: `tables.xml` 文件路径
- `daoClassName`: 由表英文名派生的 DAO 类名

DAO 类名派生规则：

- 使用 `table.id` 的首字母大写形式，加后缀 `Dao`
- 示例：`kanb_ac_num_analy` -> `Kanb_ac_num_analyDao`

这里保持和用户现有代码生成规则一致，不额外做驼峰转换。

#### DaoMethod

用于表示 Java 代码中实际被调用的 DAO 方法。

- `id`: `daoClassName#methodName`
- `daoClassName`: 如 `Ktxp_psmcsDao`
- `methodName`: 如 `selectOne_odb1`
- `displayName`: 默认等于 `methodName`

后续如需要可继续补充：

- `classFqn`
- `filePath`
- `lineNo`

但本次链路展示不强依赖这些字段直接持久化到图中，源码定位仍优先通过 `ProjectIndexer` 与 `/api/source/resolve/flow` 完成。

### Neo4j 关系

- `(caller:Method)-[:DAO_CALLS { lineNo, methodName }]->(dao:DaoMethod)`
- `(table:Table)-[:EXPOSES_DAO]->(dao:DaoMethod)`

保留现有 `Method / Class / Interface / ServiceOperation` 相关关系，不调整其语义。

### 运行时链路结构

`getChain(txId)` 的数据层从单数组改成双子层：

```json
{
  "chain": {
    "orchestration": [],
    "service": [],
    "component": [],
    "data": {
      "table": [],
      "dao": []
    },
    "relations": {
      "rootServices": [],
      "serviceToService": {},
      "serviceToComponent": {},
      "componentToComponent": {},
      "nodeToTable": {},
      "tableToDao": {}
    }
  }
}
```

#### Table 层节点

- `code`: 表英文名
- `name`: 表中文名
- `tableId`: 表英文名
- `tableLongname`: 表中文名
- `domainKey`
- `domain`
- `projectName`
- `daoClassName`

#### Dao 层节点

- `code`: `tableId#methodName`
- `name`: `methodName`
- `methodName`
- `tableCode`: `tableId`
- `daoClassName`
- `domainKey`
- `domain`
- `prefix`: 固定为 `dao`

## 后端解析流程

### 1. 解析 `tables.xml`

新增专门的表元数据解析器，扫描 `project.workspace-roots` 下目标工程的 `*.tables.xml`。

单文件处理流程：

1. 识别文件所属工程名。
2. 只保留四个目标工程。
3. 读取 XML 中所有 `<table>`。
4. 提取 `id` 和 `longname`。
5. 根据工程名推断 `domainKey`。
6. 由 `id` 推导 `daoClassName`。
7. 产出 `TableMeta` 列表。

解析失败时只记录 warn 日志，不中断整批构建。

### 2. AST DAO 调用识别

现有 `Neo4jGraphBuilder` 在识别 `XxxDao.someMethod()` 时，当前会创建 `Dao` 节点。改造后：

1. 识别 `daoClassName`
2. 识别 `methodName`
3. 产出 `DaoMethod` 节点
4. 记录 `Method -> DaoMethod` 关系

现有 `stripDaoSuffix()` 仅作为兼容工具保留，不再作为链路最终展示依据。

### 3. 关联表与 DAO 方法

构建阶段在内存中建立：

- `daoClassName -> TableMeta`

当 AST 识别出 `DaoMethod` 时：

- 如果其 `daoClassName` 能命中 `TableMeta`
  - 建立 `Table -> DaoMethod`
- 如果不能命中
  - `DaoMethod` 仍可落图
  - 但后续 `getChain()` 不返回该节点

这样可以保证：

- 图数据库保留完整技术事实
- 前端链路只展示对业务有解释价值的表与 `odb` 方法

## Neo4j 构建改造

### Neo4jGraphBuilder

需要修改的点：

1. 增加 `Table` 节点缓冲区
2. 将原 `Dao` 节点缓冲区替换或升级为 `DaoMethod` 节点缓冲区
3. 在 `Neo4jGraphBuilder.doBuild()` 开始阶段、收集 Java 文件之前扫描 `tables.xml`
4. 写入 `Table` 节点与 `EXPOSES_DAO` 关系
5. 将原 `DAO_CALLS` 边目标从 `Dao` 改为 `DaoMethod`

索引建议：

- `CREATE INDEX IF NOT EXISTS FOR (t:Table) ON (t.id)`
- `CREATE INDEX IF NOT EXISTS FOR (t:Table) ON (t.daoClassName)`
- `CREATE INDEX IF NOT EXISTS FOR (d:DaoMethod) ON (d.id)`
- `CREATE INDEX IF NOT EXISTS FOR (d:DaoMethod) ON (d.daoClassName, d.methodName)`

### 向后兼容

重新构建图后，新模型替代旧 `Dao` 终端模型。旧图不做迁移脚本；依赖 `buildSync/startBuildAsync` 进行全量重建。

## 交易链路组装

### discoverDirectTargets

当前逻辑遇到 `Dao` 就直接把表名字符串加入 `result.tables`。改造后：

1. 查询 `DaoMethod`
2. 通过 `Table -> DaoMethod` 反查表
3. 向 `BoundaryResult` 填充两类结果：
   - `tables`
   - `daoMethods`

其中：

- `tables` 用于 Table 层展示
- `daoMethods` 用于 Dao 层展示

### loadOutgoingCalls

需要返回更多字段：

- `targetLabel`
- `targetName`
- `targetMethodName`
- `targetDaoClassName`
- `targetTableId`
- `targetTableLongname`
- `targetDomainKey`
- `targetProjectName`

### BoundaryResult

从当前结构：

- `serviceTargets`
- `componentTargets`
- `tables`

扩展为：

- `serviceTargets`
- `componentTargets`
- `tableTargets`
- `daoTargets`

其中 `tableTargets` 和 `daoTargets` 应使用结构化对象，而不是简单字符串。

### getChain

数据层改成：

- `data.table`
- `data.dao`

关系改成：

- `nodeToTable`
- `tableToDao`

本次直接切换到新关系结构，不再输出旧的 `componentToData`、`nodeToData`。

## 源码定位设计

### 目标

点击 `Dao` 层节点时，直接打开对应 DAO Java 文件，并定位到对应 `odb` 方法。

### 后端接口

扩展 `/api/source/resolve/flow`：

- 当前支持：
  - 编排节点
  - method 节点
  - service/component 节点
- 改造后新增：
  - `nodeType=dao`

输入：

- `txId`
- `nodeCode`
- `nodeType=dao`

解析流程：

1. 从 `nodeCode` 中解析出 `tableId` 与 `methodName`，或直接从 Neo4j 查询 `DaoMethod`
2. 取得 `daoClassName`
3. 优先通过 `ProjectIndexer.findBySimpleName(daoClassName)` 找到文件；如后续在图中补充了 `DaoMethod.classFqn`，再升级为 `findByFqn(...)`
4. 返回：
   - `filePath`
   - `methodName`
   - `locateText`
   - `language=java`

如果找不到源码：

- 返回 404
- 前端展示“未找到源码”

## 前端交互设计

### 数据层布局

“数据层”仍为一个大层，但内部拆成两个子区域：

1. `Table层`
2. `Dao层`

### 交互

#### 过滤规则

- 选中服务/构件时：
  - 先根据 `nodeToTable` 过滤可见表
  - Dao 层展示这些表关联的所有 Dao 方法

- 单选某张表时：
  - `activeTable = table.code`
  - Dao 层只展示 `tableToDao[table.code]`

- 再次点击同一张表时：
  - 取消单选
  - 恢复当前范围内所有 Dao 方法

#### 代码查看

- `Table` 节点不提供代码查看按钮
- `Dao` 节点提供“查看代码”按钮
- 点击后走 `/api/source/resolve/flow?nodeType=dao`

### 视觉信息

#### Table 节点

展示：

- 表英文名
- 表中文名
- 领域标签

- 来源工程名

#### Dao 节点

展示：

- `odb` 方法名
- 副标题 `daoClassName`
- 代码按钮

## 错误处理

1. `tables.xml` 文件损坏
   - warn 日志
   - 跳过该文件

2. DAO 方法存在但没有匹配表
   - 保留在图里
   - 不进入交易链路返回

3. 表存在但没有匹配到任何 DAO 方法
   - 不进入交易链路返回

4. Dao 节点定位不到源码
   - 前端允许打开面板并显示“未找到源码”

5. 未配置 `project.workspace-roots`
   - 跳过表元数据扫描
   - 数据层返回空
   - 不影响其他层链路

## 测试策略

### 后端

1. `tables.xml` 解析单测
   - 正常提取 `id`
   - 正常提取 `longname`
   - 正常映射 `domainKey`
   - 正常派生 `daoClassName`

2. DAO 调用识别单测
   - `Ktxp_psmcsDao.selectOne_odb1()` 识别为 `DaoMethod`

3. 表与 DAO 关联单测
   - `ktxp_psmcs.tables.xml` 能关联 `Ktxp_psmcsDao`

4. 链路组装单测
   - `nodeToTable`
   - `tableToDao`
   - 未匹配表的 DAO 方法不进入链路

5. 源码定位单测
   - `nodeType=dao` 可返回 DAO 文件路径和方法名

### 前端

1. 数据层能渲染 `Table层` 和 `Dao层`
2. 点表可联动 Dao 过滤
3. 取消点选可恢复 Dao 全量
4. Dao 节点点击可打开代码并定位方法

## 实施顺序

1. 新增 `tables.xml` 解析器与 `TableMeta`
2. 改造 `Neo4jGraphBuilder`，落 `Table` / `DaoMethod`
3. 改造 `FlowtranServiceImpl.getChain()` 返回新数据层结构
4. 改造 `SourceController.resolveFlowSource()` 支持 `dao`
5. 改造 `TransactionCard.vue` 数据层渲染与联动
6. 构建并联调

## 风险与取舍

### 风险

1. `daoClassName` 派生规则依赖外部工程实际生成规则；若存在特殊命名，可能匹配不到。
2. 同名 `Dao` 类跨工程冲突时，仅靠简单类名定位源码可能不够，需要补 `fqn` 或更强约束。
3. 当前前端 `chain.data` 是数组，改成对象后需要同时调整所有消费点。

### 取舍

- 本次优先保证链路展示和代码定位正确，不额外做历史图迁移。
- 本次按“只展示匹配到 `Table` 的数据节点”执行，减少噪声，不追求技术全量暴露。

## 验收标准

满足以下条件即视为完成：

1. `*.tables.xml` 中定义的表能落入 Neo4j `Table` 节点。
2. Java 中的 `XxxDao.odbMethod()` 能落入 Neo4j `DaoMethod` 节点。
3. 交易链路的数据层拆为 `Table层` 和 `Dao层`。
4. 点某张表后，只显示该表关联的 `odb` 方法。
5. `Dao` 节点可打开并定位对应 Java 方法。
6. 未匹配到表的 DAO 调用不出现在链路展示中。
