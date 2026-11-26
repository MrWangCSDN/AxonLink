# 固定配置标识的 Axon / BD 联动实施计划

> **For agentic workers:** Use superpowers:executing-plans with independent, explicitly owned component tasks. User approved the complete design in this chat; proceed through implementation and verification without another approval gate.

**Goal:** 同一真实表多条条件配置，各自比对、问题跟踪和统计，原单表标识及 issue_id 保持兼容。

**Spec:** Obsidian 两项目现行三件套的“同表多配置与固定标识（2026-11-26）”章节；设计只保存在 Obsidian。

## Binding contracts

- 现有 `tableName` / 数据库 `table_name` / `comparisonCode` 继续作为固定配置标识。首条优先真实表名 `a`，额外配置 `a_1`,`a_2`…；允许跳号，删除不复用，编辑/重新登记保留原标识，复制/新增分配新标识。持久序号、事务锁及已有/历史标识和真实表名碰撞检查。
- `sourceTableName` 为真实物理表。现有 `source_table_name` 扩展至普通 TABLE，旧行回填自身表名；新增 `configuration_name` 作人类可读配置名称。
- 登记、版本 DTO 追加可空 `@JsonIgnore ReplayDbCompareConfigurationIdentity configurationIdentity`，包含 `sourceTableName,configurationName`；旧完整构造器保留，所有 with/copy 方法保留 identity。公共 `sourceTableName()` / `configurationName()` 返回有效值；`comparisonCode()` 仍为 tableName。新增 `withConfigurationIdentity(...)`。
- 普通 SaveRequest 追加 `String configurationName, Boolean createAdditionalConfiguration`，自定义SQL SaveRequest 追加 `Boolean createAdditionalConfiguration`，其已有 displayName 用作配置名称。null/false 保持老的重复创建校验；true 明确创建同源额外配置。编辑按 id，真实来源不能更换，保持配置标识。
- NEW 脚本现有14列后追加3列，顺序固定为 `source_table_name,configuration_name,configuration_scope`；BCOMP_NAME 仍写固定配置标识；SQL FROM 使用真实来源；scope 是普通 whereSql 或自定义 SQL 原文的展示快照。旧 NEW14 及 LEGACY 文件照常消费；新的多配置不支持缺少身份元信息的 LEGACY 生成。
- 脚本 bcomp_index 仍为包内关联号；BD 导入必须永久绑定“固定 BCOMP_NAME → 内部 BCOMP_INDEX”，重映射配置/字段/SQL三表，绝不因排序漂移覆盖其他配置的任务、参数或结果。新固定映射使用不可回收注册表，旧编号从现有配置及历史引用保留。BCOMP_NAME 区分大小写规范与现有表名一致；新格式重复标识拒绝。
- BD 配置/执行开始上下文/结果发布快照增加上述3个元信息，历史导出读取快照；任务和 Spark 现有内部 index/run/attempt 隔离保持。一天每配置最后成功结果规则保持。
- BD Excel 保留既有“表”列为固定配置标识（a,a_1），追加“真实表名、配置名称、查询条件”；表明细每配置一行，字段明细每配置+字段一行，领域汇总直接加总（重叠数据分别算）。旧文件列顺序及旧文件读取保持兼容。
- Axon issue_id 不改格式：表级=固定配置标识，字段级=固定配置标识-字段名；数字主键及全部历史不改。所有导入、自动修复、表状态、报表集合以固定标识隔离，领域/人员查真实表。新额外配置使用绑定登记版本还原来源；不能把 a_1 当成数据库真实表查询。
- Axon 列表一配置一行，显示真实表、配置名称/固定标识、条件；复制/新增同表配置入口明确，编辑/删除仅当前配置，展开/复制等状态按 id/code，搜索真实表可看全部配置。light/dark 使用既有 tokens。
- 字段总数与一致字段数按配置计算；a.amount 和 a_1.amount 是两字段；遗留问题只在同一固定标识下跨批匹配，当前缺陷修复日期为空。
- 独立命名脚本方案仍维持其现有一表一项接口，不随本次改造新增同表多条件入口；共用生成器继续兼容其旧构造及14列消费者。

## Tasks

- [x] 先更新 Obsidian Axon 登记/问题及 BD 脚本接入/报表现行设计，自审后实施；完成文档 commit/push/PR。
- [x] Axon backend core: identity DTO、保存请求、V99迁移/序号分配、DAO/metadata/service、版本保存与 hash；TDD 验证兼容、锁/碰撞/删除不复用、TABLE/CUSTOM_SQL及来源锁定。
- [x] Axon scripts/issues/report: 新脚本元信息、真实 FROM、冻结版本往返、问题导入/人员来源/配置级报表及兼容测试；不得改旧 issue_id。
- [x] Axon frontend: 页面配置名、真实表展示、复制/新增同表配置、按配置隔离行状态、编辑请求兼容；专项/全套测试及 build，浏览器 light/dark 核查。
- [x] BD: 新17列导入/严格白名单、稳定内部编号映射、元数据与结果快照/Excel/UI、PG/GDB迁移、旧格式与任务/历史隔离；TDD及专项/全套构建。
- [x] 跨工程真实产物验证：Axon 冻结脚本生成 a/a_1 → BD 实际导入与重排重导 → 独立两侧条件统计/Excel → Axon 回导/遗留及字段总数，保留可审查样例。
- [x] 独立整体审查、各工程完整相关检查、静态产物更新、提交与明确升级说明；不自动部署或执行生产SQL。

## Workspaces and coordination

- Axon server: `/Users/wangshanhe/.codex/worktrees/dbissue-report-carryover-fields/axon-link-server`, branch `codex/database-multi-config`, base `a224fd2` (includes preceding report metrics).
- Axon UI: `/Users/wangshanhe/.codex/worktrees/axon-multi-config-ui/axon-link-frontend`, base `993538b`.
- BD: `/Users/wangshanhe/.codex/worktrees/bd-multi-config/bd`, base `6f97c18` (current origin/main with full differences; unrelated retention branch not merged).
- Preserve all dirty original checkouts; no CLAUDE.md or ~/.claude writes. Run Maven serially per checkout; distinct repos can run concurrently. Parent commits server changes after coordination; other repo owners may commit their own completed work.

## Implementation rulings

- 新多配置判断使用 identity 非空、原始 configuration_name 非空且 code 与 source 不同；V99 旧行原始名称留空以兼容旧 CUSTOM_SQL 别名。新多配置首次冻结 NEW17，LEGACY 请求在读取 blob 前明确拒绝；原有单配置与旧冻结 blob 保留。
- 本批出现同真实来源的兄弟 code 时，未出现的兄弟配置保持原问题状态和最近表状态；同源只从绑定冻结来源或可信 metadata 判定，不通过后缀猜测。其它物理表仍沿用原整批“不参与比对”范围语义。本次不新增退出配置入口。

## Final verification (2026-11-26)

- Axon server `mvn verify` with `REPORT_MYSQL_REGRESSION=true`: 1734 tests,1729 passed,5 unrelated opt-in/AD skips,0 failures/errors; packaged with final UI bundle.
- Axon frontend19e1ce0:875 tests/build passed and final independent review closed.
- BD0c4eded:244 service tests(1 real-vendor skip),102 Spark,112 UI,10 browser; verify passed.
- Actual unmodified producer SQL→BD API→Spark controlled H2→normal Excel→Axon parser/import/report passed. Executed a/a_1 total4fields,2consistent,carryover1→0 after a repair date (previous batch uses controlled history fixture). Reorder preserves internal IDs1/2,not-executed a0 remains excluded.
- Operational instructions: `docs/operations/database-multi-configuration-upgrade.md`. Production schema changes/deployment not executed.
