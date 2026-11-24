# 2026-11-24 本地交易链路的构件调用与表访问

原页面在「聚合链路」中按层展示关系。下游超过八条或祖先节点达到四个时需要手动展开，顶部计数也无法指向具体调用行。此前的 `LOCAL001` 示例包含服务调用构件和构件访问表，只有一个构件，无法观察构件间调用。

## 关系从哪里来

- 查询 `/api/chain` 时，`ChainProjection` 使用 SQLite 快照中的原始图。`componentCalls` 按逻辑入口和目标操作归集，保留命中操作的原始调用边。XML 直接引用构件的记录带有步骤入口。已识别的构件调用服务也可在明细中查看，聚合树继续采用生产的层级规则。
- `tableAccesses` 按逻辑入口和命中的 DAO 方法归集，每项保留实际调用边和该 DAO 绑定的表。不同构件访问同一张表时，各行对应各自追踪到的 DAO。缺少表定义时仍可查看 DAO 和调用位置。
- 两类明细复用原聚合的遍历深度，入口可以经多跳抵达实际调用方。重复遍历到相同原始边时在同一条明细内去重。已有快照可在查询时生成明细。[聚合实现](../../../tools/flowtran-local/src/main/java/com/axonlink/local/ChainProjection.java)

## 页面和示例

选中交易后，点击「构件调用」或「表访问」，可直接查看关系两端。点击 Java 方法及行号会打开原始调用方，标出该次调用的下游边。如果结果为空，页面会区分缺少编排入口、未绑定 Java 入口及没有可达调用。[前端入口](../../../tools/flowtran-local/src/main/resources/public/index.html) · [页面交互](../../../tools/flowtran-local/src/main/resources/public/lab.js)

更新后的 `LOCAL001` 增加 `CustomerLookup` 公共构件。示例源码预期的路径为 `TransferService.submit → CheckComponent.verifyAccount → CustomerLookup.findCustomer`，最后在 `CustomerLookupImpl#load()` 中通过 `CustomerDao#selectByKey` 访问 `customer`。[示例源码](../../../tools/flowtran-local/examples/workspace/ccbs-dept-demo/src/main/java/cn/sunline/ltts/busi/demo/LOCAL001.java) · [查看说明](../../../tools/flowtran-local/README.md#查看构件调用和表访问)

本轮完成源码分析、开发及文档记录，运行结果留待本地验证。
