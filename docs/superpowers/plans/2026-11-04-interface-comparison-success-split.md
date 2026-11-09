# 接口比对明细成功数量拆分 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Work in the existing isolated backend checkout and verify each boundary before syncing to the original workspace.

**Goal:** 接口比对明细支持“二者均成功且无字段差异”“二者均成功且有字段差异”两列按表头读取、分别保存并在日报和周报导出。

**Architecture:** 复用现有导入事务、行模型、DAO及报表生成器。仅扩展接口比对数据，两项新值使用nullable BIGINT，历史总数保留；新模板完整两项安全求和维持内部总数，旧模板仍可读。导出使用截图的19列顺序。

**Tech Stack:** Java 17、Spring JDBC、Apache POI、MySQL 8、H2、JUnit 5。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`及对应数据模型、API接口文档中“接口比对明细成功交易拆列（2026-11-04）”。

## Global Constraints

- 仅接口明细拆列，汇总页及其现有计算继续使用原有数据。
- 新列：`both_success_no_field_difference`、`both_success_with_field_difference`；V86人工执行一次，不改旧迁移、不回填、不删除旧`both_success`。
- DTO末尾追加`Long bothSuccessNoFieldDifference`、`Long bothSuccessWithFieldDifference`，保留旧21参数构造。
- 两个新表头完整时优先；仅有一个新表头时拒绝整次导入；没有新表头时兼容旧“二者均成功”。
- 空值保留null；新两项都非null时精确求和，溢出返回带Sheet/行/字段上下文的导入错误。
- 接口明细输出19列：前8列不变，接两项拆分数，再接两项成功失败、两项失败响应码、忽略、两比例、两耗时。历史无拆分记录导出空格。
- 保留原工作区所有既有修改；同步前按hash核对原文件未被并发修改。

## Review Focus

- 调换列顺序及插入无关列仍按表头正确识别；新列与旧列并存时不得读错总数。
- 249/1、250/0和合计行均能落库及导出，0与null不可混淆。
- 旧记录新增字段必须是未知null，不能猜测原总数分配。
- 单个新表头、非法数值和求和溢出都应在写库前失败。
- 日报/周报复用生成器，接口明细格式和原始合计值保持一致，其他Sheet统计不受影响。

## Tasks

- [x] 设计：更新Obsidian既有三件套及联动文件，自审、提交并推送；确认设计完成后开始生产代码。
- [x] Parser agent：测试先行，扩展表头识别与新两项读取，测试新/旧格式、乱序、半套表头、坏值、溢出和未知值。
- [x] Storage agent：测试先行，新增V86并扩展DAO读写，覆盖历史迁移、批次替换与null/0/合计保存。
- [x] Root：DTO兼容构造；生成器19列、数值格式及历史空值测试；共享测试fixture加载V86。
- [x] 验证：分阶段统一运行Maven避免共享target并发；跑定向和相关回放回归，独立复核。
- [x] 交付：安全同步原工作区，附迁移说明，更新源码ZIP并验证内容完整。

## Evidence

- 初始RED：新模板缺旧表头、两新计数丢失、单新表头回退、缺V86和旧18列导出均按预期失败。
- 独立复核发现新Long写入Excel数值单元格可能丢精度；3个边界值先RED后修复，仅新两列超过15位时写十进制文本。另加真实Writer→Parser回导测试。
- 最终专项58项通过，0失败/错误/跳过，包含真实本地MySQL UUID临时库迁移与DAO验证；独立二审通过。
- 最终回放回归109个测试类，852项中849项通过、3项可选MySQL测试跳过，0失败/错误。排除先前已确认基线失败的ReplayDatabaseIssueTest、ReplayDatabaseIssueControllerTest，未修改这两个旧测试类。
- 设计提交9d306f3、a444275，文档PR #3，已按hash保护和唯一锚点同步canonical，保留已有用户修改。
- 13个任务文件已按原始hash保护同步；前端生产构建通过并输出到原后端static。源码归档1153个文件逐字节匹配当前后端，233个静态文件及首页7项引用完整，V86与升级说明齐全，中文文件名UTF-8和CRC检查通过。
