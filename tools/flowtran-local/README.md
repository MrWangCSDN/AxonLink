# 交易链路本地验证

这个独立 Maven 工程从本机的 Java 和 XML 源码构建交易链路。浏览器页面随 Java 进程启动，扫描结果写入本地 SQLite 文件。项目依赖 Spring Boot Web、JavaParser 和 SQLite JDBC，业务源码只供 AST 和 XML 解析。[SQLite JDBC 版本](https://central.sonatype.com/artifact/org.xerial/sqlite-jdbc/3.54.1.0)

## 解压 ZIP 后在内网本地启动

解压后打开带有 `pom.xml` 的 `flowtran-local` 目录。从原 CCBS 工作区开发时，对应目录是 `AxonLink/tools/flowtran-local/`。项目使用 JDK 17，内网 Maven 私服或本地缓存需要能解析 POM 中的依赖和插件。

**使用 IntelliJ IDEA**

1. 打开解压后的 `flowtran-local/pom.xml`，作为 Maven 工程导入。
2. 将 Project SDK 和 Maven 使用的 JDK 设为 17，加载 Maven 依赖。
3. 运行 `com.axonlink.local.FlowtranLocalApplication` 的 `main` 方法。运行配置的工作目录选包含 `pom.xml` 的 `flowtran-local/`，示例路径和 SQLite 文件都从这里解析。

**使用命令行**时，从解压目录执行以下命令。

```shell
cd flowtran-local
mvn spring-boot:run
```

本机 Maven 已缓存全部依赖及插件时，可使用 `mvn -o spring-boot:run` 离线启动。后端直接提供浏览器页面。启动入口见 [FlowtranLocalApplication.java](src/main/java/com/axonlink/local/FlowtranLocalApplication.java)。

启动目录需要允许写入 `data/`，默认 SQLite 快照为 `./data/flowtran-local.db`。迁移已有快照时，可在原服务正常停止后复制该数据库到新启动目录的 `data/` 下，或用 `LAB_DATABASE` 指向迁入文件。

## 扫描业务源码

打开 [http://127.0.0.1:8133](http://127.0.0.1:8133)，点击 **扫描示例**，选择或查看自动载入的 `LOCAL001`。扫描自己的项目时，把工作区目录改为包含业务模块的本机目录，并填写其 Java 包前缀。自动发现的目录为 `src/main/java` 和 `target/gen`。需指定其他 Java 源码目录时填写第二个输入框。XML 元数据始终通过工作区目录下的 `src/main/resources` 发现。

只填写额外 Java 源码目录时，可以在 **原始节点 → 查找 Java 方法** 中按类名或签名片段查询调用边。通过交易查看完整聚合链路时，工作区中还需提供该交易的 `.flowtrans.xml` 及它引用的业务元数据。

## 查看构件调用和表访问

在左侧选择交易，然后在 **聚合链路** 旁选择相应视图。已有业务快照可以直接查看。

| 想查什么 | 如何查看 |
| --- | --- |
| 哪个构件调用了另一个构件 | 打开 **构件调用**，查看 **构件调用构件**。页面也会列出本交易识别到的服务或方法调用构件、构件调用服务，以及 XML 编排直接引用构件的记录。 |
| 哪个构件访问哪张表，用了哪个 DAO | 打开 **表访问**，在 **构件访问** 下看同一行的访问入口、表和 DAO 方法。**服务或方法访问** 列出从服务或交易方法入口追踪到的访问。 |
| 哪行代码发起了调用 | 点击关系行左侧的 `类#方法 · 第 N 行`。右侧 **原始节点** 显示调用文件和行号，并标出对应的下游边。有多条边时，先展开 **N 条原始调用边**。 |
| 使用只有「聚合链路」「XML 步骤」的旧页面 | 在 **聚合链路** 中逐层展开服务或构件下面的 **N 条下游关系**。点击节点查看 **聚合使用的 Java 入口**，继续沿原始调用边追踪。使用更新后的本地服务即可从同一个 SQLite 文件打开明细。 |

「调用入口」和「访问入口」表示追踪起点，中间可以经过辅助方法。调用行对应实际发起调用的 Java 方法。同一访问行可能从多个入口到达。表访问记录按实际 `DAO_CALLS` 和该 DAO 的表绑定生成，表示代码访问数据表的关系。表间外键及 SQL JOIN 需要另外采集数据库或 SQL 元数据。

如果只有服务，先选中服务查看 Java 入口，再沿 `CALLS_SERVICE` 打开服务操作、查看 `IMPLEMENTS_BY`。有实现却看不到构件时，核对实现方法的原始调用边，以及工作区中对应的 `*.pbcb.xml`、`*.pbcp.xml`、`*.pbcc.xml` 或 `*.pbct.xml`。**表定义待匹配** 表示已经找到 DAO 调用，请核对 `src/main/resources` 的 `tables` 目录中的 `*.tables.xml` 以及日志 `DAO_TABLE`。

## 按调用顺序导出 JSON

选中一笔交易，点击 **导出 JSON**。页面按序号显示调用方、被调方、关系类型、上游序号和可用的调用行。点击节点可以查看原始图，点击「见第 N 条」可以跳到该节点首次展开的位置。**复制 JSON 字符串** 和 **下载 .json** 均导出整笔交易，预览每页显示 50 条。浏览器限制剪贴板写入时，页面会选中完整文本供手动复制。

`relations` 数组按静态源码顺序排列。从快照保存的 XML 文档顺序出发，每遇到一个步骤，先展开其绑定和下游调用，再继续同级步骤。`case/when` 保留各分支及其 `condition`。同一 Java 方法内按调用行升序，未知行号排在最后。同一行的多条静态边按关系类型、原始目标 ID 和边上方法名定序，多个实现候选按签名排列。实际选中的分支和实现取决于运行时。

| 字段 | 读取方式 |
| --- | --- |
| `transaction`、`snapshotAt` | 当前交易和扫描快照时间。旧 SQLite 快照可直接查询。 |
| `sequence`、`parentSequence` | 从 1 开始编号。`parentSequence` 指向上游关系，0 表示交易根。兄弟步骤通过共同的上游序号识别。 |
| `relation`、`from`、`to` | 当前关系及两端的类型、编码、名称和原始节点 ID。Java 调用匹配到业务操作时，`to.kind` 为 `service` 或 `component`。 |
| `callSite` | Java 调用点所在文件、可用行号和建图时的原始目标 `rawTarget`。`DAO_CALLS` 后的 `TABLE_FOR_DAO` 表示该 DAO 实际绑定的表。 |
| `referenceSequence` | 该目标已在所指序号出现。重复步骤和递归调用各自保留当前关系，沿引用可找到已展开的下游。 |
| `missingBinding`、`diagnostics` | 首次展开时可见的缺失绑定或扫描诊断。例如方法步骤找不到实现时，可看到 `RESOLVES_TO_METHOD`。 |

需要在本地脚本中取得原始 JSON 字符串时，可以直接读取 `GET /api/chain/export?code=交易码` 的响应正文。以下命令使用示例交易。

```powershell
$json = (Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8133/api/chain/export?code=LOCAL001').Content
```

## 示例的预期结果

| 想检查的规则 | 在 `LOCAL001` 中查看 |
| --- | --- |
| 从 Java 方法到服务 | 聚合树从 `prepare` 展开，应看到 `TransferService.submit`。原始节点从 `prepare()` 沿 `SELF_CALLS` 到 `validate()`，再沿 `SYS_UTIL_CALLS` 查看调用行和服务操作。`postDirect` 覆盖链式 `getRemoteInstance`。 |
| 操作 ID、实现与继承 | `TransferService.submit` 的 Java 方法名是 `post`，入口应包含 `TransferServiceBase#post()` 与 `TransferServiceImpl#post()`。 |
| 构件调用 | 在 **构件调用** 中应看到 `TransferService.submit → CheckComponent.verifyAccount`，以及 `CheckComponent.verifyAccount → CustomerLookup.findCustomer`。后者的调用位置在 `CheckComponentImpl#verify()`。 |
| 构件访问表 | 在 **表访问** 中应看到 `CustomerLookup.findCustomer` 通过 `CustomerDao#selectByKey` 访问 `customer`。点击 `CustomerLookupImpl#load()` 的调用行可定位到 `DAO_CALLS`。 |
| XML 步骤与聚合去重 | **XML 步骤** 同时显示审核通过、审核拒绝及两次 `TransferService.submit` 引用。两次服务引用在聚合结果里共享同一服务节点。 |
| JSON 顺序与重复引用 | **导出 JSON** 的前两条为交易到 `main` 流程、`main` 到 `prepare` 步骤。`post` 和 `retryPost` 分别保留 `CALLS_SERVICE`，可通过 `referenceSequence` 找到同一个 `TransferService.submit` 的首次展开。 |
| 诊断和边界 | `audit()` 的原始节点应显示 `LegacyService` 未通过 SysUtil 分类筛选。`this.writeAudit()` 属于当前扫描规则没有对应分支的调用形式。`missing` 步骤应显示找不到对应交易方法。 |

早期 `LOCAL001` 示例只有一个构件，当时保存的快照显示 `CheckComponent.verifyAccount` 访问 `customer`。**扫描示例** 会使用当前示例文件替换现有快照。成功扫描后，重启服务会载入同一个 SQLite 文件中的结果。[示例 Java 源码](examples/workspace/ccbs-dept-demo/src/main/java/cn/sunline/ltts/busi/demo/LOCAL001.java) · [交易 XML](examples/workspace/ccbs-dept-demo/src/main/resources/LOCAL001.flowtrans.xml) · [客户查询构件定义](examples/workspace/ccbs-dept-demo/src/main/resources/CustomerLookup.pbcc.xml)

## 控制台日志

在启动后端的终端或 IDE Run 窗口查看日志。点击 **开始扫描** 或 **扫描示例** 后，按 `[scan=…]` 查找同一轮记录。

- `INFO` 记录输入目录、包前缀、扫描阶段、文件序号和耗时。阶段结束会给出计数，扫描结束会汇总节点类型、边类型、诊断条数和 SQLite 快照时间。
- `WARN` 实时输出每类诊断的前三条样例，带规则、文件、可用行号和原因。`issuesByRule` 汇总全部诊断。
- `DEBUG` 补充逐文件、发现的源码目录、过滤原因、实际建边和剩余诊断。排查缺边时可按交易码、方法签名、`SYS_UTIL_KIND`、`METHOD_STEP` 或 `DAO_TABLE` 搜索。`line=-1` 表示该诊断没有行号。
- `ERROR` 给出导致扫描终止的阶段、耗时和异常堆栈。

从工具目录启动 `DEBUG` 日志。

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--logging.level.com.axonlink.local=DEBUG"
```

| 排查现象 | 控制台线索 |
| --- | --- |
| Java 文件少或交易为空 | 核对 `workspaceRoots`、`includePackages`、`candidateFiles` 和 `skippedByPackage`，在 `DEBUG` 中看实际发现的根目录。 |
| XML 步骤找不到 Java 方法 | 搜索 `METHOD_STEP`，根据 XML 路径、步骤键和目标方法核对源码。 |
| SysUtil 调用没有边 | 搜索 `SYS_UTIL_KIND` 查看实际文件分类或类型索引缺失。筛选通过后查看 `SYS_UTIL_CALLS` 的建边日志。 |
| DAO 有调用但图上缺表 | 搜索 `DAO_TABLE`，按诊断中的 DAO 名核对表定义。 |

## 配置

| 配置 | 默认值 | 用途和生效时机 |
| --- | --- | --- |
| `server.address` | `127.0.0.1` | 本机回环监听地址，进程启动时读取。 |
| `server.port` | `8133` | HTTP 端口，进程启动时读取。 |
| `lab.database` | `./data/flowtran-local.db` | SQLite 文件位置，进程启动时读取。相对路径从运行目录解析，可通过 `LAB_DATABASE` 指定不同的快照文件。 |
| `logging.level.com.axonlink.local` | `INFO` | 控制工具包的控制台日志级别，进程启动时读取。`DEBUG` 增加逐文件、建边和过滤明细。 |
| `workspaceRoots` | 空 | 表单中的工作区目录，每次扫描生效。支持逗号或换行分隔。 |
| `sourceRoots` | 空 | 表单中的额外 Java 源码目录，每次扫描生效。支持逗号或换行分隔。 |
| `includePackages` | `cn.sunline.ltts.busi` | Java 包前缀，逗号或换行分隔。每次扫描生效。 |
| `maxFileKb` | `500` | 单个 Java 源文件的大小上限，单位 KB。每次扫描生效。 |
| 示例工作区 | `examples/workspace` | 点击 **扫描示例** 时使用。相对路径从运行目录解析。 |

启动配置见 [application.yml](src/main/resources/application.yml)。从源码提取的节点和关系分别保存在 `graph_node`、`graph_edge`，诊断保存在 `graph_issue`，扫描设置及统计保存在 `graph_scan`。服务在内存中载入一份当前图用于链路查询。

## 用于定位规则的代码

| 要定位的行为 | 入口 |
| --- | --- |
| Java 识别、普通调用、SysUtil、DAO 和实现绑定 | [JavaCallScanner](src/main/java/com/axonlink/local/JavaCallScanner.java) |
| 流程分支、交易方法绑定、服务操作引用 | [FlowXmlScanner](src/main/java/com/axonlink/local/FlowXmlScanner.java) |
| 层级归并、八跳调用展开及 DAO 补查 | [ChainProjection](src/main/java/com/axonlink/local/ChainProjection.java) |
| SQLite 快照事务和重启恢复 | [SqliteSnapshots](src/main/java/com/axonlink/local/SqliteSnapshots.java) |

本地工程聚焦交易链路。元数据来自本次扫描的源码目录，同一方法命中多个操作时按操作键取首条。生产的历史图缓存、影响分析和 XML 字段映射查询属于工具范围之外。
