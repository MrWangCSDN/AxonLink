# 周报按最新结论回算实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax for tracking.

**Goal:** 保留用户选定的两个批次，以同类全部批次最新问题结论回算各批次的无需处理和比率，历史证据不足时按指标留空。

**Architecture:** 最新结论与批次原量分别读取，由 resolver 组合为交易指标输入；历史问题统计独立保留。计算器支持独立的交易指标输入，context 统一驱动 Excel、JSON 与邮件的缺口展示。

**Tech Stack:** Java 17 / Spring JDBC / Apache POI / Vue 3 / JUnit / Vitest。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`，以及同目录对应数据模型和 API 接口。

## Global Constraints

- 日报维持批次历史及统计起点机制；周报使用用户批准的最新结论回算。
- 数量只取所选批次：latest round > 同批 V88 > 当前记录所属同批；latest appeared=false 为已知0，禁止回捞。
- 不新增表、不回填历史、不将全批次数量求和、不替换用户的起止批次。
- 交易归属以本端接口比对明细领域和交易码为准；保留前导零。
- 缺失数量不伪造0；逐指标、领域、合计传播 null，Excel 写真正 BLANK。

## Review Focus

- 同 issue_key 多条记录只使用最新结论，历史未修复日期不能阻塞当前字段资格。
- 原量身份变更或 JSON 损坏不得套用新批次数量。
- latest appeared=false 不得回捞前次 true。
- 两端任一缺历史快照时保留静态数量和可可靠回算的比率。
- 缓存下载保留原生成快照；显式重新生成才读取最新结论。

### Task 1: 最新结论及各批次原量

**Files:** `ReplayIssueDao.java`、新 `ReplayBatchOriginalIssueQuantity`、`ReplayWeeklyLatestIssueResolver` 及测试。
**Interfaces:** DAO `findCurrentReportIssues(start,end)`、`findBatchOriginalReportQuantities(batch)`；resolver `resolve(batch, comparisons, latestIssues)` 产出 metricIssues、三组 unknownDomains、notices。

- [x] 写回归并运行 RED：旧量9/新量25、缺量、false0、同族、字段全部修复。
- [x] 实现有界证据读取与组合，不修改每日历史快照。
- [x] 定向测试 GREEN。

### Task 2: 周报计算与缺口上下文

**Files:** `ReplayDailyReportCalculator.java`、`ReplayWeeklyReportService.java`、`ReplayReportSummaryViewFactory.java`、新 `ReplayReportCalculationContext` 及测试。
**Interfaces:** 计算器8参重载分离历史问题与交易指标；context `(previous Side,current Side,notices)`，Side 包含历史可用性与三组 unknownDomains；可序列化到 totalRow.values.calculationContext。

- [x] 写回归并运行 RED：旧历史缺失仍生成、最新结论且各端原量独立、缺口 null 传播、日报不变。
- [x] 服务在同一只读事务读取两端原量/最新结论，只捕获历史缺口专用异常。
- [x] 计算器旧重载保持兼容，factory 对当前及合计精确遮罩。
- [x] 定向测试 GREEN。

### Task 3: 报表展示及前端说明

**Files:** WorkbookWriter、MailHtmlRenderer 及测试；前端 ReplayIssuePage.vue/spec。
**Interfaces:** `ReplayReportCalculationContext.from(view)` 读取持久化上下文；旧 view 默认完整。lower 直接使用 factory null。

- [x] 写 RED：上下两表均保留、未知数字是真正 BLANK、说明正确转义、邮件保持 null。
- [x] 实现 upper 遮罩与说明行，保留 lower 批次标题 `（本批次）` 后缀。
- [x] 前端说明最新结论/各端独立原量/缺量留空，日报文案保持现状。
- [x] 后端回归与前端目标测试 GREEN。

### Task 4: 验证及交付

- [x] 新上下文 JSON 往返、legacy extractor 兼容及相关回归验证。
- [x] 独立审查，无关历史失败复用已确认基线证据。
- [x] SHA 保护同步任务文件至原工作区，构建前端至后端 static。
- [x] 生成新源码 ZIP 并核对逐文件字节、CRC、资源引用、排除项。

## 执行证据

- 设计已推送 Obsidian `6ea97a4`（PR #3），未在代码库复制设计规范。
- 后端131项计算与展示回归、160项DAO与控制器回归均通过；前端158项通过。
- 独立审查两项P2已RED→GREEN：说明过多冻结数据、有效交易码DETAIL缺失却输出已知0。无未处理的审查发现。
- 日报旧入口不变；本次不重复运行已确认存在40项无关基线失败的全量套件。
