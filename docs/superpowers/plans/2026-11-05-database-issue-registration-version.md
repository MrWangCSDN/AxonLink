# 数据库问题批次关联登记历史版本实施计划

> 执行时遵循 superpowers:executing-plans，按任务验证并记录结果。导入、报表和前端由明确文件归属并行实施，最终统一审查。

## 目标

导入数据库问题时关联不可变的登记历史版本，当前登记删除后仍可重现该批次的领域和参与表字段总数。

## 设计来源

/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库问题清单-系统设计.md

/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库问题清单-数据模型.md

/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库问题清单-API接口.md

## 全局约束

- 复用 V63 不可变版本表，V89 为批次增加可空 registration_version_id 及索引。
- 绑定、完整导入与校验在原 writing 事务和全局写锁内完成。
- 同批已绑定时沿用原版本，显式换版本返回 409。旧调用缺参兼容未绑定路径。
- 对每个实际导入表按小写表名唯一匹配版本表。缺失与跨 schema 歧义拒绝，零字段有效。
- 已绑定报表领域和字段数来自版本快照，仅累加参与表。问题类型沿用当前值。
- 存量关联交付 SQL，无独立维护页面及写接口。
- 不覆盖原工作区未知修改，完成后 SHA 校验同步并验证源码 ZIP。

## 验证重点

- 重导与人工 SQL 并发时绑定保持一致，失败不留下部分导入。
- 旧版未绑定批次无需读取 V63，旧 API 保持兼容。
- 当前登记删除、领域变更、参与表只是整版子集时仍用正确快照。
- 版本目录分页、已绑定旧版、请求乱序及网络失败时版本选择准确。
- SQL 按 DB20261028-1 精确键维护，避免混淆 DB20261028-01。

## 任务 1 导入与绑定

修改 ReplayDatabaseIssueDao、Service、Controller，增加 V89 与导入回归测试。

- [x] 写失败测试，覆盖版本绑定、import-context、重导、换版、缺版本、缺表、歧义、零字段、回滚。
- [x] 执行 ReplayDatabaseIssueRegistrationVersionTest，确认缺新接口失败。
- [x] 增加 registrationVersionNo 五参重载及 multipart 参数，只读 import-context 返回五字段版本摘要。
- [x] 改批次 DELETE/INSERT 为保留绑定的 UPDATE/INSERT，事务内校验完整导入表。
- [x] 执行新测试、ImportTokenTest 与 PlanDateTest，核对既有数据库问题测试基线。
- [x] 仅提交任务归属文件。

## 任务 2 报表与维护 SQL

修改 report 包四文件，增加 ReportRegistrationVersionTest，按需更新旧测试迁移夹具。

- [x] 写删除当前登记、分批版本、缺表、歧义、零字段及失效绑定的失败测试并执行。
- [x] DAO 按批次读绑定，未绑定沿用原逻辑，已绑定读取版本表与字段聚合。
- [x] Service 按快照领域汇总，并在 DTO、Excel 主表和统计口径展示版本。
- [x] 新增 docs/sql/2026-11-05-replay-database-issue-bind-registration-version.sql，包含前置检查、受保护更新及回查。
- [x] 执行数据库报表测试组并提交归属文件。

## 任务 3 前端版本选择

修改 replayDatabaseIssues API 与测试、ReplayDatabaseIssuePage 与测试、ReportDialog。

- [x] 写 API 参数、版本默认与分页、绑定锁定、失败重试及乱序保护的失败测试并执行。
- [x] 复用 loadVersions，新增 getDatabaseIssueImportContext，导入第五参数传版本。
- [x] 未绑定批次默认最新并可选历史，已绑定显示且锁定原版，未知状态阻止提交。
- [x] 更新报表说明并执行相关 Vitest 后提交。

## 任务 4 集成验证与交付

- [x] 审查全部改动，执行前后端回归及构建，记录已有基线失败。
- [ ] SHA 保护同步任务文件到原工作区，前端构建进入后端静态目录。
- [ ] 使用既有 package-source.sh 生成含前端的后端源码 ZIP，验证 CRC、路径、排除项和实际源码一致性。
- [ ] 交付 ZIP、SQL 和生效条件，说明默认最新版、快照统计来源与验证限制。

## 验证记录

- 前端全量 596 项测试通过，隔离构建和原目录构建均通过。
- 后端相关 50 项功能测试通过，包含匿名上下文鉴权、事务回滚和 MySQL 历史版本统计。
- 两个旧测试类共 40 项失败，与既有基线的数量、名称、类型和错误信息逐条一致。
- 维护 SQL 在临时 MySQL 9.4 数据库验证了首次绑定、幂等执行、已有异版本绑定保护和相似批次键隔离。
- 最终独立代码审查无遗留可操作问题。
