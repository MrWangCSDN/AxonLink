# 分区数编辑与双标识授权实施计划

> 使用 superpowers:executing-plans 在当前会话执行。

**Goal:** 实现用户明确指定的分区数展示、登记编辑及 username/工号权限。
**Architecture:** 复用现有分区字段、名单配置和版本快照；普通保存统一事务，保留旧接口。
**Tech Stack:** Spring Boot / JDBC / Vue / Vitest / JUnit。
**Spec:** /Users/java/obsidian-worktrees/partition-editor-20260922/01 Engineering/axon-link-server/回放数据库比对字段登记-系统设计.md

## 全局约束
- 1–256 整数，新增默认 1，省略更新字段保留原值。令牌/匿名/禁用用户不可授权。
- 名单匹配可信用户 username 或 emp_no；无权限只读，后端防越权。
- 新增、修改、重新登记原子保存，单次版本递增并审计；SQL 使用生成版本快照。

## Review Focus
- 无权限编辑已配置 16 的登记不得重置为 1。
- 客户端伪造账号、JSON 小数或字符串不得绕过验证。
- 仅改分区数仍持久化并审计；重复保存无变化不增加版本。
- 版本冲突和字段更新失败必须回滚分区数。
- 新增/编辑/重新登记切换、查看及列表保持一致。

## Task 1 后端
- [x] RED：在 ServiceTest / ControllerTest 添加 JSON 保存分区数、username 授权、工号兼容、默认和越权、版本冲突、事务回滚回归。使用 ObjectMapper 构建请求。
- [x] 运行 JAVA_HOME=JDK17 mvn -Dtest='ReplayDatabaseComparisonServiceTest,ReplayDatabaseComparisonControllerTest' test，确认新增断言失败。
- [x] 实现配置双标识判断；SaveRequest/ReregisterRequest 严格 Integer 解析；Service 同事务及审计；DAO 带 partitionNum 的乐观锁更新。保留旧签名兼容导入等调用方。
- [x] 运行全部 dbcompare 测试，确认通过。

## Task 2 前端
- [x] RED：Editor.spec.js 新增默认/只读/授权/边界/恢复/提交；Page.spec.js 增加列表列位置、详情及请求权限数据流。
- [x] npm test -- src/components/replay/ReplayDatabaseComparisonEditor.spec.js src/components/replay/ReplayDatabaseComparisonPage.spec.js，确认新增断言失败。
- [x] Page 查询条件后添加无筛选分区数列，详情比对范围后展示，传递 options 权限给 Editor。
- [x] Editor 比对范围后增加数字输入，权限禁用和校验；有权限才发送 partitionNum，所有模式保留现值。
- [x] 运行相关 Vitest 及生产构建。

## Task 3 验证交付
- [x] 核验新版 SQL 逐表分区数与旧版转换测试；定向后端/前端测试、前后端构建。
- [x] 独立审阅本次差异，修复实际缺陷；记录验证结果。
- [x] Obsidian 文档 git diff --check 并提交推送，原目录未知修改保留。

## 执行记录
- Ruling: 使用当前已有 codex/primary-key-hash-partition 开发分支延续已实现的新旧 SQL 功能；未跟踪历史包不动。
- Ruling: Obsidian 原目录存在未知修改，使用从 origin/main 建立的独立 worktree 发布本次文档，避免覆盖。
- Ruling: 用户已明确授权所列界面和权限行为，直接完成实现，无重复设计审批。

- 验证：后端 dbcompare 24 类 203 测试通过；前端全套 26 文件 455 测试通过（排除仓库内历史 .worktrees）。
- 独立审阅发现新增列影响 nth-child 表头定位；已改为 data-column-key 样式。补充重新登记分区数恢复和提交回归。
- Obsidian 设计提交 10638d1 已推送 main，原目录未知变更保持原样。
