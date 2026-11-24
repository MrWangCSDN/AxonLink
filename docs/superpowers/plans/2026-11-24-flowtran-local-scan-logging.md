# 2026-11-24 本地交易链路扫描日志

本地扫描通过 `POST /api/scan` 连续完成 Java 和 XML 解析、调用建边及 SQLite 写入。控制台使用 `[scan=…]` 关联该轮的扫描参数、进度、诊断和快照结果。进程启动时会记录 SQLite 文件和已载入的快照时间，查询交易链路时的 `DEBUG` 日志会带上该快照时间和聚合耗时。[扫描和聚合入口](../../../tools/flowtran-local/src/main/java/com/axonlink/local/LabController.java)

## 日志记录

| 环节 | 内容 |
| --- | --- |
| 文件发现 | 工作区、额外 Java 源码根、包前缀、大小上限，以及 Java、服务 XML、交易 XML 的候选数。`DEBUG` 列出发现的目录。 |
| Java 和 XML 解析 | 开始和完成耗时，文件序号与总量，类、接口、方法、服务操作、交易和 XML 步骤数量。文件循环在首末文件、每 100 个文件及距上次进度输出至少五秒的文件边界打印进度。 |
| 连边和过滤 | `DEBUG` 记录实际写入的边类型、起终节点、文件和可用的调用行。包过滤、未知调用接收者及服务操作缺实现也可按文件或节点查询。Java 调用候选计数对应待解析的调用缓冲区，DAO 在调用采集阶段直接入图并单独计数。 |
| 诊断 | 每类诊断的前三条实时记录为 `WARN`，其余为 `DEBUG`，结束时按规则汇总。SysUtil 诊断区分类型索引缺失和已有定义的分类未命中。Java 和 XML 语法错误在解析器提供位置时带上行号。 |
| SQLite 快照 | 记录写入开始、耗时、数据库文件、快照时间和各类型节点、边的数量。中断时输出失败阶段与异常堆栈。 |

诊断沿用 `Graph.report` 和 `graph_issue`。控制台按本次构建记录新诊断，重启时载入历史诊断供查询。Java 解析错误行号从 `Problem` 的起始令牌位置提取，XML 语法错误行号由 `SAXParseException` 提供。[扫描进度](../../../tools/flowtran-local/src/main/java/com/axonlink/local/ScanProgress.java) · [JavaParser Problem 实现](https://github.com/javaparser/javaparser/blob/javaparser-parent-3.25.10/javaparser-core/src/main/java/com/github/javaparser/Problem.java)

新增 `logging.level.com.axonlink.local` 的注解和启动说明。默认级别为 `INFO`，以 `DEBUG` 启动可查看现场匹配和过滤细节。[使用说明](../../../tools/flowtran-local/README.md#控制台日志) · [配置文件](../../../tools/flowtran-local/src/main/resources/application.yml)

本轮完成源码开发和日志使用说明，运行验证按工作区约定留给本地操作。
