# 回放问题发送交易量 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Reuse existing isolated backend/frontend workspaces and preserve original user changes during sync.

**Goal:** 在回放问题“出现笔数”前展示发送交易量，从本次导入的接口比对明细按交易码匹配，每次覆盖。

**Architecture:** 当前问题表新增独立导入派生列。导入前从本次工作簿聚合明细，在原子写事务内覆盖同模式全部当前问题；普通编辑不触碰该列。列表 API 用 string/null 保持整数精度，页面及清单 Excel 使用同一列顺序。

**Tech Stack:** Java 17、Spring JDBC、MySQL/H2、POI、Vue 3、Vitest、JUnit 5。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-{系统设计,数据模型,API接口}.md` 中 2026-11-04 发送交易量章节。

## Global Constraints

- 用户确认：同交易码 DETAIL 多行汇总、排除 TOTAL，未匹配覆盖 NULL。
- 仅 trim 交易码首尾空白，保留大小写及前导零；空代码不匹配。同码任一数量未知则结果 NULL，与行序无关；其余安全求和，溢出在写入前报 400。
- V87 为人工一次升级，给 `dii_replay_issue` 增加 nullable BIGINT `sent_transaction_count`；历史不回填。
- 该列独立维护，不扩 `ReplayIssueRow` 或人工编辑请求；保持现有人工字段、历史和原始跟踪投影。
- 覆盖当前 batch_no 与本次规范化批次同族（RPT/DZ）的全部问题，包括本批未出现的问题；优先取dailyData批次，否则首个问题批次。QUERY通常为RPT但原生DZ文件仍更新DZ，DZ模式先按既有规则正规化批次。日报-only也可更新此列；无接口明细时相同实际批次族的列覆盖NULL，不读取旧日报补值；无法识别族的旧日报-only保持原兼容行为并跳过本列覆盖。
- 同一事务完成问题合并、可选日报替换及新数量覆盖，失败全部回滚；DAO 一次读取 scoped id/code，按 id 每2000条分批覆盖。
- GET/POST 列表返回 `sent_transaction_count:string|null`；清单 Excel 和页面均在出现笔数前增加发送交易量；0明确展示、NULL使用已有空样式、长整数无精度损失。
- 无新增筛选或排序，不改变出现笔数排序和统计口径。

## Review Focus

- 重复导入由500覆盖为21或NULL，不累加也不继承过期值。
- 同码、多S码、跨问题重复出现不重复累加到单条问题；每条问题获得同一个交易级总量。
- NULL优先于中间求和溢出，MAX/1/NULL的行序不能影响结果。
- 日报-only、问题-only、旧构造器分支都遵守覆盖；另一模式和人工编辑字段不受影响。
- 数量更新失败必须回滚之前的问题/日报写入；API、复制和Excel保持完整十进制整数。

## Tasks

- [x] 设计：完成已有三件套及联动文件，自审、远端PR3和canonical按hash安全同步。
- [x] Backend agent：导入及迁移测试先RED，V87、聚合与DAO覆盖实现，root统一GREEN。
- [x] Frontend agent：8项先RED后GREEN，完整33文件572项通过；两处最小列及复制配置改动。
- [x] Root：共享fixture、新API/清单导出边界测试先RED，导出新列实现，专项13项和独立审查通过；全回放回归继续独立记录。
- [x] 交付：安全同步、前端生产构建至后端、升级说明及源码ZIP完整性验证。

## Verification Evidence

- 初始RED 10项：覆盖缺失、溢出未拒绝、列表类型、V87缺失等9失败1错误均符合预期。
- QUERY+原生DZ联合/问题-only两例先RED，RPT原值被误更后修复为实际批次族；其余10项同时GREEN。
- 最终专项13项0失败/错误/跳过，包含真实本地MySQL临时库迁移、主键分批覆盖、精度和事务回滚。
- 最终回放回归112类865项，0失败/错误、4项可选MySQL跳过；排除已有基线失败的ReplayDatabaseIssueTest及ReplayDatabaseIssueControllerTest，未改两旧类。
- 前端新增8项先RED后GREEN，完整33文件572项通过；独立静态审查通过。
- 设计提交081f617、85839b1已推送现PR3，三件套及联动文件已按hash/唯一锚点同步canonical，用户原修改保留。
- 11个后端/2个前端任务文件已按原始hash保护同步。原前端33文件572测试再次通过，生产构建输出后端static。ZIP中1159文件逐字节匹配原后端、233静态资源和7首页引用齐全，V87/说明及UTF-8/CRC验证通过。
