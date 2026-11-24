# 2026-11-24 本地交易链路有序 JSON 导出

在本地工程选中交易后，可以在「导出 JSON」中按顺序查看关系、复制完整 JSON 字符串或保存文件。脚本可以读取 `GET /api/chain/export?code=LOCAL001` 的 JSON 正文。[使用说明](../../../tools/flowtran-local/README.md#按调用顺序导出-json)

## 顺序和来源

导出按已保存的 XML 结构深度优先展开，保留方法步骤、服务步骤以及 `case/when` 的条件。Java 调用范围直接取自现有聚合遍历，沿用服务与构件的操作匹配和 DAO 补查规则。每个 Java 方法的出边按源码行排列，同一行按关系类型、原始目标及方法名定序。XML 绑定到的多个实现按签名排列，每条候选关系均保留其位置。

`relations` 的 `sequence` 是展示序号，`parentSequence` 表明该行从哪个上游展开。每条重复引用均有记录，以 `referenceSequence` 指回首次展开。调用行保留 `callSite.rawTarget` 以便对照实际扫描边。DAO 的目标表来自同一次聚合中该 DAO 的表绑定，缺表时保留 DAO 与缺失绑定提示。[有序导出](../../../tools/flowtran-local/src/main/java/com/axonlink/local/OrderedChainExport.java) · [聚合遍历](../../../tools/flowtran-local/src/main/java/com/axonlink/local/ChainProjection.java)

## 快照和交付

页面打开导出视图时按需读取，携带查看交易时的 `snapshotAt`。导出期间服务端使用同一份快照，页面切换交易后会丢弃过期响应。预览、剪贴板和下载使用同一份导出数据，内网浏览器限制自动复制时可直接选取文本。此次交付使用可在内网 IDEA 或 Maven 中启动的源码 ZIP。[接口](../../../tools/flowtran-local/src/main/java/com/axonlink/local/LabController.java) · [导出页面](../../../tools/flowtran-local/src/main/resources/public/chain-export.js)

本轮完成源码分析、开发和文档记录。编译与运行验证按工作区约定留待本地。
