# 主键与唯一索引切换 Implementation Plan

> Use superpowers:subagent-driven-development with explicit file ownership. Preserve all existing dirty changes, do not commit unrelated source files.

**Goal:** 主键默认、唯一索引可切换、无主键唯一索引可登记，版本与SQL一致。
**Architecture:** 真实物理主键元数据与所选比对键分离；完整索引候选+持久化快照。
**Tech Stack:** Java17, PostgreSQL catalog, MySQL result DB, Vue3, JUnit/Vitest.
**Spec:** /Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-主键与唯一索引切换-系统设计.md

## Global constraints

- 可空列不得过滤；完整复合索引，不混拼多个索引。
- 默认主键、无主键时名称稳定排序首个唯一索引。
- 修改Axon，不修改BD实际NULL处理；保存原物理primaryKey元数据语义。
- 保留之前发版时间功能和已有未提交修改。

## Contracts

- Metadata: ReplayBaseKeyOption(String indexName,String indexType,List<String> columnNames), indexType PRIMARY_KEY/UNIQUE_INDEX. ReplayBaseMetadataSnapshot.keyOptions(), ReplayBaseValidatedTable.keyOptions(). Old constructors derive PRIMARY candidate. ReplayBaseMetadataService.listKeys(String).
- Requests: optional comparisonKeyName. Responses: comparisonKey={indexName,indexType,columnNames}. SQL comparison_key_json nullable column on registration and version_table.
- Frontend: GET .../metadata/tables/{tableName}/keys; editor loadKeys prop; save payload comparisonKeyName; preserve old loadColumns array API.

## Review focus

可空唯一索引、INCLUDE/表达式/部分索引；无主键fallback；保存后不被同步覆盖；字段完整性与索引删除漂移；旧历史生成不得读取当前键。

## Tasks

- [x] 1. 元数据候选与向后兼容DTO（metadata agent owns metadata service and base metadata DTOs only）：TDD、目录查询过滤、有序复合索引、公开listKeys。
- [x] 2. 持久化/保存/同步/审计/版本/脚本（backend agent owns other backend dbcompare files; coordinates endpoint）：新增迁移与兼容构造器、按chosen key校验、SQL标志及LIMIT、相关回归。
- [x] 3. 前端选择与查看/版本（frontend agent owns frontend files only）：索引下拉及补齐保护、请求传参、读展示、Vue/API回归。
- [x] 4. Root integration：review contracts, tests, independent code review, front build into backend static, source delivery note. Wiki only own branch commit/push.

## Review ledger

- 元数据独立核对发现PK INCLUDE覆盖列误标physical primary，已改pg_constraint.conkey并加回归。
- 业务TDD发现DAO.withFieldPreviews丢comparisonKey，已修复。
- legacy NULL且无PK按唯一索引同步补列，新版本冻结默认唯一键，当前NULL记录保留兼容语义。
- 全链路独立审查无重要发现；详情旧NULL文案改为“未记录索引选择（按母库默认）”，避免误称一定主键。
- 前端11组335项已通过；build已写后端static。后端最终统一套件进行中。
- 不新增BD代码改动；字段允许NULL可选，实际NULL键仍受BD现有校验限制。

## Final verification

- 后端最终36类432项通过，0失败0错误：/tmp/replay-comparison-keys-backend-final.log。包括目录21项、真实结果库/版本/双脚本验收、legacy唯一键及发版时间回归。
- 前端11文件335项通过：/tmp/replay-comparison-keys-frontend-final.log。
- build通过，已写入后端static：/tmp/replay-comparison-keys-build.log。
- 独立审查无重要缺陷，唯一显示建议已按真实legacy NULL语义修正文案。
- Wiki设计及三件套补充已推送codex/replay-comparison-keys-20261015，提交2667a76；未提交混合前后端源码。
- 内网结果库需要执行V81__replay_db_compare_comparison_key.sql，新增登记/版本comparison_key_json；未连接或修改真实数据库。
