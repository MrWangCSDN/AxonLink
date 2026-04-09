# 构件分析全领域反向聚合设计

日期：2026-04-09

## 1. 背景

构件分析和表级分析相似，都是从某个“已落库的方法级节点”出发，向上聚合影响面；但它的根节点不是表，而是构件方法节点。

这里的“构件”固定指以下四类 XML 落库后的 `ServiceOperation`：

- `pbcb`
- `pbcp`
- `pbcc`
- `pbct`

构件分析的核心目标是：

- 从某个构件方法出发
- 找到所有调用它的服务方法
- 再根据流程编排归属，向上聚合到联机交易

## 2. 目标

- 新增构件分析后端目录接口与影响图接口
- 构件目录粒度固定为 `ServiceOperation`
- 构件分析层级固定为：
  - `根构件 -> 服务层 -> 联机交易层`
- 构件分析不单独拆“流程编排层”
- 但服务能否向上归集联机交易，判定规则必须与服务分析一致

## 3. 非目标

- 本次不改表级分析接口语义
- 本次不改服务分析的独立“流程编排层”设计
- 本次不在页面展示 DAO 层
- 本次不把构件分析扩成 `构件 -> 服务 -> 流程编排 -> 交易` 四层页面

## 4. 设计原则

1. 构件分析继续保持当前 Figma 页面可接受的紧凑层级，不额外新增流程编排列。
2. 构件分析中的服务层仍然是“服务定义层”，不是流程编排实例层。
3. 只有直接 `flow step` 的服务节点，才允许从服务层继续向右连接到联机交易。
4. 不是任何交易直接 `flow step` 的中间服务节点，只停留在服务层。
5. 构件分析和表级分析共享同一条“服务归集交易”规则。

## 5. 接口设计

### 5.1 构件目录

路由：

`GET /api/flowtran/impact/components`

请求参数：

- `keyword`：可选，支持按构件方法编码或中文名过滤

返回结构：

```json
{
  "total": 1234,
  "items": [
    {
      "id": "DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp",
      "name": "检查二次提交状态",
      "longname": "检查二次提交状态",
      "domainKey": "deposit",
      "nodeKind": "pbcb",
      "serviceTypeId": "DpCheckAffrTwcSubmitBcs"
    }
  ]
}
```

目录范围只允许：

- `pbcb`
- `pbcp`
- `pbcc`
- `pbct`

### 5.2 构件影响图

路由：

`GET /api/flowtran/impact/component/{componentId}`

返回结构：

```json
{
  "mode": "component",
  "root": {
    "id": "DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp",
    "name": "检查二次提交状态",
    "type": "pbcb",
    "domainId": "deposit",
    "nodeType": "component"
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
        "id": "dp2524",
        "name": "二次提交交易",
        "code": "dp2524",
        "domainId": "deposit",
        "nodeType": "transaction"
      }
    ]
  ],
  "edges": [
    { "from": "DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp", "to": "AaaPcs.BbbSvc" },
    { "from": "AaaPcs.BbbSvc", "to": "dp2524" }
  ],
  "stats": {
    "services": 5,
    "transactions": 2
  }
}
```

## 6. 服务归集联机交易规则

构件分析里的服务层不单独拆“流程编排层”，但交易归属规则必须与服务分析完全一致：

- 如果服务节点本身是某个 `flowtrans.xml` 的直接 `flow step`
  - 则允许它连接到联机交易
- 如果服务节点只是编码中的中间调用节点
  - 则不能直接连接到联机交易

换句话说：

- 构件分析与服务分析的区别，在于页面是否显式展示“流程编排实例层”
- 不在于交易归属规则本身

## 7. 聚合算法

### 7.1 步骤 1：定位根构件

从 `ServiceOperation(serviceTypeId, serviceId)` 定位构件方法根节点，要求：

- `nodeKind IN [pbcb, pbcp, pbcc, pbct]`

### 7.2 步骤 2：向上聚合服务层

从调用该构件方法的服务方法出发，构造服务层：

- `service -> component`
- `service -> service`

服务层节点类型保留原始值：

- `pbs`
- `pcs`
- `service`

### 7.3 步骤 3：服务向上归集交易

服务节点向上连接交易时，必须执行“direct flow step”判断：

- 命中某个交易直接 `flow step` 的服务，才允许连到交易
- 不中的服务不连交易

这里不额外生成“流程编排实例节点”，而是把该判断内化为一条资格规则。

## 8. 页面展示约束

- 页面层级保持：
  - 根构件
  - 服务层
  - 联机交易层
- 不新增流程编排列
- 服务层中的服务节点仍按原始类型落位
- 关系线继续全部走虚线图样式
- hover 任意节点时，只点亮一跳上下层与同层直连关系

## 9. 与表级分析的共享规则

构件分析与表级分析共享以下规则：

1. 服务层不显式拆出“流程编排层”
2. 只有直接 `flow step` 的服务节点才允许向上归集联机交易
3. 不是直接 `flow step` 的服务节点，只停留在服务层

## 10. 验证要点

1. 选中一个构件方法时，能看到调用它的服务层节点。
2. 如果这些服务里只有部分服务是直接 `flow step`，则只有这些服务能向上连交易。
3. 如果没有任何服务是直接 `flow step`，页面只显示根构件和服务层，不显示交易节点。
4. 构件分析的服务层不出现单独的“流程编排实例节点”。
5. 表级分析与构件分析在交易归属判定上保持一致。
