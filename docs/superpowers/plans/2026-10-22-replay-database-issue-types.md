# 回放数据库问题清单问题类型调整实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 将数据库问题清单的问题类型收敛为七类，按问题状态限制可选类型，并迁移当前历史数据。

**Architecture:** 由 `ReplayIssueTypes` 作为唯一目录，数据库问题服务在导入和编辑入口共用状态/类型校验；新增 Flyway 迁移仅回填当前问题表，保留审核操作历史快照原值。

**Tech Stack:** Java 17、Spring Boot、JUnit 5、Flyway、JdbcTemplate、H2/MySQL 兼容 SQL。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库问题清单-系统设计.md` 的“数据库问题清单问题类型调整（2026-10-22）”。

## Global Constraints

- 七类固定顺序：不参与比对、修改比对规则、修改迁移规则、修改代码、规则性差异、前后交易差异、其他问题。
- “无需处理”只允许不参与比对、规则性差异。
- 其他状态只允许修改比对规则、修改迁移规则、修改代码、前后交易差异、其他问题。
- 历史迁移只更新当前问题投影，不更新审核/操作历史快照。
- 未列出的旧值不擅自改写。

## Review Focus

- 无需处理选择旧类型时必须在服务端失败；测试新的两个允许值和五个拒绝值。
- 其他状态选择不参与比对或规则性差异时必须失败；测试五个允许值。
- 导入初始类型与当前记录更新都必须使用新目录；历史旧值回填后不能继续出现在当前清单。
- Flyway 迁移中的快照表/历史表不得被更新。
- 前端筛选选项和后端 options 必须使用同一七类目录。

### Task 1: 更新问题类型目录和状态校验

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueTypes.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueControllerTest.java`

- [ ] 写失败测试：断言 options 返回七类；无需处理只接受不参与比对/规则性差异；其他状态接受另外五类并拒绝前两类。
- [ ] 运行定向测试确认旧目录/旧限制导致失败。
- [ ] 修改唯一目录及校验逻辑，保持审核流程和非类型字段不变。
- [ ] 运行数据库问题清单测试确认通过。

### Task 2: 更新导入/编辑兼容和历史当前数据迁移

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueExcelParser.java`
- Create: `src/main/resources/db/daoindex/V84__normalize_replay_database_issue_types.sql`
- Test: `src/test/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueExcelParserTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueTest.java`

- [ ] 增加导入旧类型到新类型的规范化测试；保留未列出的值原样，后续由服务校验拒绝编辑/保存。
- [ ] 实现导入类型映射，确保新导入数据只进入七类目录。
- [ ] 新增 V84，按精确旧值更新当前问题表：代码问题→修改代码、规则性差异问题→规则性差异、迁移问题→修改迁移规则、防腐问题/需规问题/平台问题/合理差异→其他问题；不更新历史/审计表和 JSON 快照。
- [ ] 运行解析器、数据库问题服务及迁移测试。

### Task 3: 更新相关测试数据和回归验证

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueControllerTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/dbissue/ReplayDatabaseIssueExcelParserTest.java`

- [ ] 将测试中的旧合法类型替换为新目录，保留专门的旧值迁移兼容测试。
- [ ] 增加审计历史快照保留旧类型的断言。
- [ ] 运行 `mvn -Dtest='*ReplayDatabaseIssue*Test' test`，再运行受影响的 replay 服务测试。
- [ ] 检查 `git diff --check`、迁移 SQL 和工作区未提交改动边界。
