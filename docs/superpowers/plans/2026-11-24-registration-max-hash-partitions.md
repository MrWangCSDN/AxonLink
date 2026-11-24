# 登记最大HASH分区数 Implementation Plan

> **For agentic workers:** Use TDD for behavioral changes, delegate frontend/backend boundaries, and independently review the integrated change before delivery.

**Goal:** 在普通表及自定义SQL登记完整维护最大hash分区数，并将快照值输出到bd可导入的NEW脚本。

**Architecture:** 独立maxPartitionNum贯穿登记、查询、审计、配置指纹和不可变版本快照。V96为登记和版本表增加默认64列；NEW由选定快照输出14列，旧冻结脚本保持历史兼容，界面只提供NEW及独立命名配置。

**Tech Stack:** Java17/Spring JDBC/Jackson/JUnit/MySQL/H2，Vue3/Vitest/ExcelJS；复用bd现有14列白名单导入器。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-系统设计.md` 及配套数据模型/API；命名脚本和bd导入设计同步更新。

## Global Constraints

- maxPartitionNum默认64，严格整数1..256（允许100），独立于partitionNum默认1和sampleLimit默认100。
- 新建missing/null为64，编辑/重新登记/自定义SQL更新missing/null保留原值。人员登记维护权限可修改max，不新增管理名单门槛。
- 初始化Excel不新增必填列，新登记64，合并更新保留原值；所有内部投影均保留max。
- 查询maxPartitionNums为整数数组，登记/版本search及header-filter均支持；候选自排除本列条件，与其他筛选交集。
- V96为registration/version_table增加max_partition_num NOT NULL DEFAULT64；旧快照补64，旧hash不改，升级后允许自然生成新版本。
- NEW输出bcomp_max_partition_num第14列，使用真实快照值；命名配置既有路由保持。前后端默认NEW，删除旧版本（原表）界面选项，后端显式LEGACY仅作历史兼容。
- 未知本地修改及备份不暂存/覆盖；设计仅在Obsidian；按既有授权推送main。

## Review Focus

- 小数/越界/巨大JSON数字不能被Jackson截断；非法筛选同样拒绝。
- 普通编辑、SQL编辑、重新登记、初始化合并、人员映射、主键同步及详情投影不得恢复为64。
- max单独变化必须写审计/CAS并改变配置hash；旧版本仍下载自己的值。
- NEW脚本不能继续输出13列；旧缓存不得掩盖新增cap；max不覆盖partitionNum或sampleLimit。
- 筛选候选计数、自排除、分页/重置；Excel长SQL链接与新增合并列索引不能错位。

## Tasks

### 1. 登记存储及生命周期（backend agent）

**Files:** `dto/ReplayDbCompare*`、`persistence/ReplayDatabaseComparisonDao.java`、`ReplayDatabaseComparisonVersionDao.java`、`service/ReplayDatabaseComparisonService.java`、`ReplayDatabaseComparisonVersionService.java`、`ReplayDatabaseComparisonImportService.java`、`ReplayDatabaseComparisonConfigurationHasher.java`、`controller/ReplayDatabaseComparisonController.java`、V96及相应测试。

- [x] RED：64默认/100与256保存/省略保留/无管理员max修改/严格数字拒绝/审计及乐观锁/内部投影保持/快照及指纹/新旧筛选候选。
- [x] 实现max请求及响应、兼容构造器和withMaxPartitionNum、V96、DAO、服务及筛选；controller默认NEW。
- [x] GREEN：定向回归通过，并用完整后端测试检验所有旧迁移测试夹具。

### 2. NEW脚本（root）

**Files:** `ReplayDatabaseComparisonConfigScriptGenerator.java`、`ReplayDatabaseComparisonConfigScriptService.java` 及对应测试。

- [x] RED：内置NEW默认64及显式100/256、普通/SQL快照、独立named值、LEGACY冻结不变、自由文本保真。
- [x] NEW无论是否命名均输出14列；内置值取snapshot.maxPartitionNum，named值取已有方案map；普通文本复用UTF8HEX约定。
- [x] GREEN：generator/service测试，并实际生成NEW文件供bd Controller→Service→Repository交叉导入。

### 3. 前端完整操作（frontend agent）

**Files:** `ReplayDatabaseComparisonPage.vue`、`ReplayDatabaseComparisonEditor.vue`、`ReplayDatabaseComparisonCustomSqlEditor.vue`、`ReplayDatabaseComparisonVersionHistory.vue`、`versionDetailWorkbook.js`、`api/replayDatabaseComparison.js` 及对应测试。

- [x] RED：普通和SQL默认/回显/校验/保存、父页不剔除max、列表筛选数字数组、历史值、七列Excel合并、NEW默认及profile分流。
- [x] 输入max放在样本数附近，登记及版本显示；筛选及重置/候选贯穿；Excel增加表级合并max列；删除LEGACY选项。
- [x] GREEN：定向及全前端测试；生产构建写入后端static；浏览器验证主要操作。

### 4. 集成、复核和交付（root）

- [x] 权威设计先更新，代码完成后按实际契约回核设计；独立审查完整diff。
- [x] bd真实导入内置NEW，核对100/256及默认64覆盖；当前bd不需新生产代码。
- [x] Java17完整package、static与JAR精确集合/字节校验，git diff --check。
- [x] 精确提交推送后端/前端main；源码ZIP使用固定提交交付流程生成并核验SHA/CRC/源文件字节。

## Ledger

- 授权：沿用用户此前明确要求的完整实现、推送GitHub主分支及源码打包；本次要求明确删除旧版本界面选项。
- 分工：backend agent拥有除generator/script-service以外的后端，frontend agent拥有前端及Excel导出，root拥有脚本生成/集成验证，docs agent先维护Obsidian设计。


## Verification

- 前端行为RED覆盖新增字段、NEW默认和Excel列；最终39个文件、804项测试通过，生产构建已写入后端static。
- 后端行为RED：generator18项中2项预期失败；后端5项新增契约/保存测试预期失败。定向第三轮31 suites、339项通过；Java17完整package：180份报告、1652项，0失败/0错误、6项跳过。
- 定向筛选测试发现H2将CAST AS CHAR截断为一字符，候选改为CONCAT保留完整整数，普通/版本候选与精确过滤均回归通过。
- 打包清理target中旧前端残留后重装包，JAR与source static的233项精确集合/逐字节一致；V96及全部dbcompare编译类逐字节一致。
- Playwright真实组件与模拟API验证：普通人员编辑普通项256/SQL项100、阻断64.5、普通/SQL新增默认64、数字数组筛选、快照保留旧值100、仅NEW下拉和显式NEW下载。临时页、浏览器和Vite均已关闭清理；未连接生产接口。
- 真实NEW SQL SHA256：`20e192dc8b82fba6180432168cfc8ab0169647fbc3f67c917581d2fea2039281`。bd在H2/隔离原生MySQL导入，将128/32/16覆盖为100/256/64；六字段文本/主键/位置和三组双侧SQL逐字核验，自定义result_q外层排序limit25保留；非法0/257/NULL/'64'/64.5全部400并完整回滚。隔离MySQL已清理，bd本轮无生产代码改动。
- 独立完整diff和后端边界复核均无遗留发现。Obsidian权威设计main：`017694135de942d9d347bf4a0b12b38599e83f72`。
- 源码包装使用固定Git对象及最新static，逐文件核验Git blob/字节、清单、CRC、路径和执行权限。bd归档已验证2b554d9对象，保留别的任务正在修改的工作区和HEAD。

## Delivery

- 后端功能commit：`3fe98c6`；前端功能commit：`c8b3f79`，均已正常推送GitHub main。
- 本次发布目录为 `output/releases/20261124-registration-max-hash-partitions/`，不覆盖此前命名配置发布包。源码包完整HEAD、文件清单和SHA记录在发布README与manifest中。
- 部署前人工执行V96。修改登记后生成新的不可变版本再下载NEW；历史快照迁移后的cap为64，不从当前登记回灌。
