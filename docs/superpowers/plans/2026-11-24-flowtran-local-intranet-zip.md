# 2026-11-24 本地交易链路工具内网迁移包

`flowtran-local 0.1.0` 以 Maven 源码工程 ZIP 交付，供内网开发机从 IDEA 或命令行直接启动。压缩包使用独立顶层目录 `flowtran-local/`，包含 Spring Boot 后端、内置页面、有序 JSON 导出、SQLite 配置、最新 `LOCAL001` 示例、本地启动说明及四份扫描和链路分析文档。[迁移包](../../../../flowtran-local-0.1.0-intranet-src-20261124.zip)

| 项目 | 交付值 |
| --- | --- |
| 文件名 | `flowtran-local-0.1.0-intranet-src-20261124.zip` |
| 归档文件数 | 35 |
| 大小 | 72,062 字节 |
| SHA-256 | `C4C1EC5B0AA263FD85BAECD5394C410C2B0D97BD80CBCFB029AB595EB94D605A` |

归档时核对工程文件摘要及包内文档链接。内网开发机用 JDK 17 导入 `pom.xml`，通过内网 Maven 仓库或完整本地缓存解析依赖和插件。直接运行 `FlowtranLocalApplication.main` 或在工程目录执行 `mvn spring-boot:run`。默认使用本机 8133 端口和启动目录下的 SQLite 文件。[内网本地启动说明](../../../tools/flowtran-local/README.md)
