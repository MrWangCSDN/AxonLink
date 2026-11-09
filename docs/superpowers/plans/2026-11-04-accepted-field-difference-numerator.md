# 字段差异认可交易计入比对通过率分子 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in the existing isolated backend worktree; share no Maven target writes with other agents. Preserve original user changes with SHA guards.

**Goal:** 所选批次内，同领域及沙箱、同交易码至少有一条字段级无需处理且审核通过，并且全部字段级问题缺陷修复日期非空时，将该交易的有字段差异成功数量补入比对通过率分子。

**Architecture:** 问题内部投影追加交易码、缺陷修复日期。日报/周报同时读取两端接口明细供计算器使用；新模板使用 DETAIL 的无字段差异事实数作基础分子，合格交易的有差异事实数按交易资格汇总一次。私有行计算结果携带补量供合计重算，保持原纯无差异字段及对外结构。

**Tech Stack:** Java17、Spring JDBC、JUnit5、H2、POI。

**Spec:** Obsidian `01 Engineering/axon-link-server/并行回放问题清单-{系统设计,数据模型,API接口}.md` 中的字段差异认可分子规则，PR3提交 `a2d2d9a`，canonical已安全同步。

## Global Constraints

- 范围按所选出现批次、规范化领域（含沙箱）、trim交易码；保留前导零，按问题ID去重后检查完整字段级问题集合。
- 至少一条字段级无需处理+APPROVED，全部字段级问题日期非空；其余已修复问题不要求也是无需处理。历史审核通过但缺陷修复日期为空仍阻塞。
- 仅同批次同领域DETAIL参与；同码多S码行相加，TOTAL不参与，不按问题条数重复加。
- 完整拆分事实（两个数量均已知且非负）才替代反推基础数并补量；无明细或领域拆分不完整时继续旧反推且不补未知数量；0是已知数量。
- 交易成功率、交易级APPROVED无需处理分母及其他问题统计规则保持；无新增数据库字段、迁移或对外JSON字段。
- 私有RowCalculation独立存认可差异补量；合计先加计数再计算比例，不改纯无差异字段语义。
- 既有快照不自动重写，显式重新生成后更新。

## Review Focus

- 一条字段缺日期、没有已审核无需处理、旧审核空日期，不得认可整个交易。
- 多字段/多S码、TOTAL、前导零、不同批次/领域/沙箱均不可重复或串算。
- 新直接基础数不能继续叠加旧反推率，旧模板/缺拆分不可把总成功当有差异数。
- 日报与周报两端同口径、合计不丢补量、纯无差异计数不篡改。
- 两端新查询仍在原只读一致性事务内；writer只输出结束批次接口明细，旧缓存契约保持。

## Task 1: 计算链与验证

**Files:** DTO `ReplayDailyIssueStatisticRow`、DAO `ReplayIssueDao`、`ReplayDailyReportCalculator`、日报/周报Service；相关Calculator/DAO/日报/周报测试及新增分子专项测试。

**Interfaces:** DTO末尾追加 `String transactionCode, LocalDate defectRepairDate`；新六参计算入口按previousSummary/previousIssues/previousComparisons、currentSummary/currentIssues/currentComparisons分组；旧四参委托空接口明细。

- [x] 两路只读调查，确定基础分子反推误差与字段/两端接口数据缺口；记录原工作区9个文件SHA保护。
- [x] 在现有Obsidian设计定稿后实施；用户已明确确认只检查所选批次。
- [x] RED：真实数据库到日报，字段未全部修复只计直接无差异基础数；全部修复后只补一次有差异量，旧实现失败。
- [x] 补齐内部投影和两端接口数据链，并适配旧构造夹具。
- [x] RED/GREEN专项测试覆盖完整资格、未解决/未审核、旧数据、领域沙箱/批次与TOTAL隔离、多接口行、合计和溢出。
- [x] 相关DAO/Calculator/日报/周报及工作簿、汇总JSON回归通过，独立复核无可行动缺陷。
- [x] 文档保鲜、SHA保护同步原工作区、仅提交隔离工作区本任务文件。

## Evidence

- 本任务基于 `d55467e`，前一轮相同源码的四类相关回归79项通过。
- 日报集成RED：应为 `0.82828283` 的认可通过率仍是旧值 `0.7979798`；新增8项专项在仅接通类型/输入链时7失败1通过、无运行错误，证实原算法缺少直接事实与认可补量。
- GREEN：专项8、原计算器10、DAO36、日报26、周报9，共89项通过，失败/错误/跳过均为0。真实DB到Excel及汇总JSON验证字段未全修复时不补、全修复后只补一次、快照显式重新生成生效。
- 独立复核与 `git diff --check` 通过；无额外产品字段、数据库迁移或前端变更。
