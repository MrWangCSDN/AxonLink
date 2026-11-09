# 发送交易量排序及清单导出 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Preserve original user changes and coordinate shared Maven target usage.

**Goal:** 发送交易量支持与出现笔数一致的服务端全量排序，并在清单导出 Excel 中展示。

**Architecture:** V87已有BIGINT继续作为事实列；列表/导出DAO共用新排序参数，前端三态互斥。发送量沿用本次接口 DETAIL 按码汇总及覆盖，不扩展问题解析器。

**Tech Stack:** Java17、Spring JDBC、POI、MySQL/H2、Vue3、Vitest、JUnit5。

**Spec:** Obsidian现有并行回放问题清单三件套及全局流水号排序设计，PR3更正后提交1fd3e67。

## Global Constraints

- `sentTransactionCountOrder`位于GET列表/导出参数及POST列表JSON顶层，支持ASC/DESC，缺失/null/空白默认，非空非法值含JSON数字0返回400。
- BIGINT数值在过滤后分页前排序，双向NULL末尾，ties按group_name,is_sandbox,row_order,id稳定；多参数优先sent>global>affected，旧global>affected保留。
- 前端默认→ASC→DESC→默认，三种排序互斥，重置/切类型清状态，翻页/每页/筛选/导出携带当前排序。
- 用户澄清“导入”为“导出”：不增加问题页数量导入或校验，不扩ParsedWorkbook、ReplayIssueRow或V87。
- 接口DETAIL按码汇总、TOTAL排除、实际RPT/DZ族覆盖、未匹配/无接口明细NULL及原子事务继续沿用。
- 清单Excel发送量位于出现笔数前，0/NULL/完整整数文本和全量排序保持一致。

## Review Focus

- 排序为全量数值顺序，含0、2、10、Long最大值及NULL；筛选分页和超过200行导出保持同序。
- 新参数不能落在query内丢失，三态/三个互斥状态所有reset入口都覆盖。
- 问题页额外发送量列即使存在，也不改变数量来源或引入额外校验；不跨实际RPT/DZ族，不回退到数据库旧来源。

## Tasks

- [x] 设计：七页既有设计及联动更新、自审、远端PR3与canonical安全同步。
- [x] 排序与导出实现及原回归通过。
- [x] 用户纠正后，两项来源/额外列回归先RED；撤回问题可选列、旁路map和专用校验，生产解析器及导入服务精确恢复a01059a。
- [x] 更正后的完整导入/解析/Controller回归及独立复核。

交付沿用 `scripts/package-source.sh`，在保留用户其他修改及已构建前端静态资源的前提下生成新的更正版源码 ZIP，不覆盖旧包。

## Evidence

- 原排序版本前端33文件585项通过，本次不改前端。
- 更正RED两项均按预期失败：问题页999误作来源、额外列触发不必要校验。更正后保留原10项接口来源/覆盖回归并加入两项防回退测试。
- 更正GREEN：完整Parser15项、ImportService17项、发送量导入11项、Controller97项，共140项通过，失败/错误/跳过均为0。独立复核未发现可行动缺陷。
- 设计PR3按用户澄清更新至1fd3e67，canonical安全同步；撤回的可选问题列设计不再是现行契约。
