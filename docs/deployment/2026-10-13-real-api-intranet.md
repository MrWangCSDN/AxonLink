# 2026-10-13 内网部署说明

本次源码包已包含构建后的前端 `src/main/resources/static`，页面统一请求同源 `/api`。前端运行时模拟接口、演示数据和接口失败时的模拟回退已移除；Monaco 编辑器资源由后端 `/monaco/vs` 提供，无需外网 CDN。

## 数据库升级

沿用已有结果库与字段登记表；确认之前的升级脚本已经执行。在结果库中按下面顺序执行**尚未执行过**的脚本：

1. `src/main/resources/db/daoindex/V77__replay_database_issues.sql`
2. `src/main/resources/db/daoindex/V78__replay_database_issue_review_projection.sql`
3. `src/main/resources/db/daoindex/V79__replay_database_issue_repair_date_projection.sql`
4. `src/main/resources/db/daoindex/V80__replay_database_issue_collaborator_projection.sql`

本项目不会启动自动迁移。V78–V80 包含增加列语句，不可在已经完成对应升级的库重复执行。此说明不要求删除或重新导入已有业务数据。

## 配置与构建

使用 Java 17 与内网 Maven 仓库。解压后将内网现有的配置放回 `src/main/resources/application-local.yml`，或继续通过环境变量/外部 Spring 配置加载；本次包排除开发机专用的 `application-local.yml`，保留 `.example` 配置模板。

真实登录需要 `AXON_SECURITY_ENABLED=true`，认证方式、LDAP/UIAS 和结果库继续使用内网配置。字段登记需要 `REPLAY_BASE_TARGET_ENV` 指向真实母库环境、`REPLAY_BASE_DB_SCHEMA` 指定 Schema，并具备元数据读取权限。人员候选读取现有人员表；领域、责任人和科技负责人读取唯一有效字段登记。初始化导入与版本生成继续使用已有操作口令。

前端已构建，无需安装 Node 或再次运行 `build.sh`。在后端源码目录执行：

```bash
mvn package -DskipTests
```

使用原部署方式启动 `target/axon-link-server-1.0.0.jar`；项目默认端口为 8123。先完成上述 SQL 升级与配置，再启动新版本。更新浏览器缓存后访问页面。

## 部署核对

- 登录后打开“回放数据库比对字段登记”和“回放数据库问题清单”，确认读取实际登记与问题数据。
- 查询、排序、分页和 Excel 导出范围一致；新增/编辑后刷新能读取服务端结果。
- 字段登记科技负责人搜索只返回有工号的有效人员；问题审核按对应字段登记科技负责人授权。
- 接口或母库不可用时页面提示真实错误，不展示模拟记录。

本地验证覆盖隔离数据库中的真实 Java 控制器、服务和 DAO，以及前端 API 调用和错误处理；内网数据源/认证连通性需在内网环境核对。
