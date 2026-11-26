# Full Difference Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Axon登记样本数支持全量，bd完整保存比对范围内全部差异行并可分页核查。

**Architecture:** `sampleLimit=-1`贯穿登记、冻结快照和NEW14列脚本；0与指定数量保持。bd新增独立分区分批JDBC暂存路径，driver只收小manifest，成功attempt明细、每日汇总及任务完成一次原子提交，失败保留旧日结果。

**Tech Stack:** Vue 3/Vitest、Java/Spring/JDBC、MySQL/H2、Spark 3.5.3及3.4.0、bd Gauss/GDB元库、React/Vitest。

**Spec:** Obsidian唯一来源：`01 Engineering/axon-link-server/回放数据库比对字段登记-{系统设计,数据模型,API接口}.md`、`01 Engineering/bd/库表比对拆分-{系统设计,数据模型,API接口}.md`；导入契约见bd `Axon配置脚本接入`三件套。

## Global Constraints

- -1=全量差异行，0=仅汇总，1..10000=限额，新登记默认100；禁止Integer.MAX_VALUE替代全量。
- WHERE/compareLimit、HASH分区上限、原样本数权限和旧采样路径保持。
- 全量保存type2/3/4的全部差额occurrence，超过127种组合及20000条不得截断；单值/字段数/JSON展示裁剪沿用原标记。
- NEW保持14列，从不可变快照输出-1；含全量版本LEGACY在blob处理前返回422 SAMPLE_LIMIT_REQUIRES_NEW。
- 最多4个逻辑writer，retry/speculation可短时额外连接；不改默认线程池或全局连接池。
- 不接触原checkout未知dirty，不修改CLAUDE.md，不强推，不执行现场DDL；设计只在Obsidian。

## Review Focus

- failed/late speculative attempt必须完全排除，run关闭后不能复活孤儿数据。
- 发布中途或提交异常不能清除/暴露半成品，也不能自动重放未知提交结果。
- 大量字段组合不能通过PatternCounts或TreeMap变成driver全集集合；OTHER明细仍可分页遍历。
- 数量和序号跨Integer.MAX_VALUE仍用long/BIGINT；不依赖JDBC int update count判断全部写入数量。
- 历史版本、无权限省略、旧LEGACY和0样本配置不可因全量开关改变。

---

### Task 1: Axon整数契约、双表迁移及冻结脚本

**Files (axon-link-server):**
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareSampleLimitDeserializer.java`
- Create: `src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareSampleLimit.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/controller/ReplayDatabaseComparisonController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigScriptGenerator.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigScriptService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonVersionDao.java`
- Create: `src/main/resources/db/daoindex/V98__replay_db_compare_full_difference_samples.sql`
- Tests: `ReplayDatabaseComparisonServiceTest`、`ReplayDatabaseComparisonCustomSqlServiceTest`、`ReplayDatabaseComparisonMigrationTest`、`ReplayDatabaseComparisonVersionServiceTest`、`ReplayDatabaseComparisonConfigScriptServiceTest`及generator测试。

**Interfaces:** consumes JSON integer `sampleLimit`; produces signed int -1 through registration/snapshot/audit/hash/NEW script. Existing endpoints and14列不变。LEGACY error is HTTP422/code SAMPLE_LIMIT_REQUIRES_NEW。

- [x] RED: 新增-1普通/重新登记/自定义SQL保存及继承、无权限403、严格拒绝-2/10001/小数/字符串/布尔；冻结快照改变当前登记后NEW仍输出原-1。
- [x] RED: V75/V76后应用V98，登记/版本均能保存-1、原0/100/1000/10000不变且默认100；旧CHECK被替换；LEGACY全量错误不生成blob，非全量兼容通过。
- [x] Run targeted existing tests and confirm new assertions fail for unsupported-1/oldCHECK.
- [x] Implement strict allowed range, V98 drop+replace two namedCHECK，NEW generator允许-1，VersionDao冻结EXISTS及LEGACY前置拒绝。
- [x] GREEN: replay dbcompare相关测试；独立临时MySQL验证真实DROP CHECK迁移，H2用支持的DROP CONSTRAINT处理，不改历史V75/V76。
- [x] Commit仅本任务源码、测试、迁移及该计划。

核对结果：Java 17 专项 197 项通过，全套共 1,686 项、零失败、零错误、6 项沿用既有跳过。临时 MySQL 9.4 验证了实际 V98。

### Task 2: Axon样本数交互及历史可读性

**Files (axon-link-frontend, all under src/components/replay):**
- Modify: `ReplayDatabaseComparisonEditor.vue`、`ReplayDatabaseComparisonCustomSqlEditor.vue`、`ReplayDatabaseComparisonPage.vue`、`ReplayDatabaseComparisonVersionHistory.vue`、`ReplayDatabaseComparisonAuditDialog.vue`。
- Tests: 各自同名`.spec.js`。

**Interfaces:** consumes/produces `sampleLimit:number`; selects all => -1; finite box0..10000; no-permission omission unchanged. Display -1 as全量 only for sampleLimit, never alter other numeric audit fields.

- [ ] RED: 新建默认100，选择全量提交-1；加载-1展示全量；切回恢复上次数字，换表重置；普通及自定义SQL同等覆盖。
- [ ] RED: 无权限/保存中模式及输入不可改，无权限请求省略；列表/详情/冻结版本/审计复制显示全量且其他-1审计字段原样。
- [ ] Run five targeted spec files, confirm new interaction/display assertions fail.
- [ ] Implement compact指定数量/全量选项及草稿数量；复用现有授权与请求转发，统一sampleLimit格式化。
- [ ] GREEN targeted tests then `npm test` and `npm run build`；不扩展固定版本Excel结构。
- [ ] Commit only task files; root把已验bundle复制到后端static并校验。

### Task 3: bd导入和结果存储契约

**Files (bd):**
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/AxonConfigScriptParser.java`、`ConfigurationRepository.java`。
- Modify: result package `DailyResultSummary.java`、`DailyResultRepository.java`、field pattern模型及导出fallback中样本计数读取。
- Modify: `sql/create_bcomp_new.sql`、`sql/create_bcomp_gdb.sql`、`compare-service/src/test/resources/legacy-schema.sql`。
- Create: Gauss及GDB全量暂存/BIGINT增量SQL，表固定`TSS_BCOMP_FULL_RUN_NEW`、`TSS_BCOMP_FULL_STAGE_NEW`、`TSS_BCOMP_FULL_WIN_NEW`。
- Tests: `ConfigurationApiTest`、`DailyResultApiTest`、导入parser与结果导出相关测试。

**Interfaces:** explicit signedINT BCOMP_SAMPLE_LIMIT=-1; three sample counters、pattern SAMPLE_COUNT和SAMPLE_NO为BIGINT/long；JSON形状保持，分页size有界。

- [ ] RED: 实际NEW14列带-1导入成功，11/13列同理；-2及10001拒绝并整次回滚；更新省略及复制保留-1。
- [ ] RED: 结果计数和序号超过2147483647仍精确读取；无pattern type4包含Top127外明细。
- [ ] Run targeted tests and confirm prior validator/int path fails.
- [ ] Implement parser range/copy保留；双方言DDL及H2 schema；JDBC getLong/setLong和long模型，不修改全局fallback0..10000。
- [ ] GREEN tests both dialect modes；检查旧结果升级后保持，元库迁移不自动触达现场。
- [ ] Commit import/result/model/SQL tests coherently.

### Task 4: bd全量Spark执行及原子发布

**Files (bd, compare-spark/src/main/java/cn/sunline/tss/tssveri/spark):**
- Create: `FullDifferenceStage.java` with nested serializable `PartitionManifest`。
- Modify: `SparkHandlerTable.java`、`DailyResultPublisher.java`（新增publishFull），必要共享SampleRow/pattern helpers。
- Tests under same test package: `FullDifferenceStageTest.java`、全量Spark integration test、`DailyResultPublisherTest.java`、`SampleLimitConfigurationTest.java`。
- Service cleanup integration: result `ResultRetention.java`及对应测试，仅清理关联终态/消失task的孤儿run。

**Interfaces:** FullDifferenceStage以runId/partition/attempt/localNo暂存；PartitionManifest返回身份、long matched/2/3/4 totals和按配置字段名计数；`DailyResultPublisher.publishFull`发布获胜manifest，旧publish不变。

- [ ] RED: 生成type2/3/4、重复可空键差额，>20000行及>127patterns，断言全量行count与精确汇总/字段统计一致，未通过driverResultAggregate收全集。
- [ ] RED: 两attempt同partition仅赢家发布，失败attempt部分commit及late speculative行不混入；关闭run后晚到insert失败；清理不删除running task的run。
- [ ] RED: 发布删旧后注入异常，旧每日结果/明细/任务状态rollback一致；manifest计数不符拒绝发布；commit异常不重放。
- [ ] Run focused tests and observe bounded sample/truncation or missing stage failure.
- [ ] Implement独立全量mapPartitions，coalesce(min原partition,4),false；每attempt单JDBC连接100条batchcommit，差额逐occurrence用long展开，仅collect小manifest。
- [ ] Implement run/stage/winner隔离；事务锁task/day、选择成功attempt、count校验、long前缀序号、DB GROUP BY Top127+OTHER、全部明细及摘要/字段计数/任务一次commit，finally清run。
- [ ] GREEN `mvn -pl compare-spark -am test`及`mvn -Pspark-3.4 -pl compare-spark -am test`；确认旧0/限额路径边界保持。
- [ ] Commit only full-path/cleanup tests and implementation files.

### Task 5: bd全量结果展示

**Files (bd):**
- Modify: `compare-ui/src/pages/DailyResultDetailPage.tsx`、`DailyResultsPage.tsx`、`DailySampleGrid.tsx`中实际使用样本限额文案处。
- Tests: `DailyResultDetailPage.test.tsx`、`DailyResultsPage.test.tsx`。

**Interfaces:** detailLimit=-1展示全量，原有限样本/0文案保持；type4默认不传patternId而分页浏览全部，Top127快捷入口保持。

- [ ] RED: -1结果显示全量，不出现负数样本提示；全部type4请求无pattern，翻页保留type4且能显示折叠组合行；快捷分组仍发实际pattern。
- [ ] RED: -1模式提示单值展示沿用裁剪，0与有限限额文案无回归。
- [ ] Run targeted UI tests, verify new assertions fail.
- [ ] Implement minimal existing-page wording and default all-pattern browse without无界请求。
- [ ] GREEN UI targeted/full tests and `npm run build`；不修改服务/Spark/SQL以免共享工作区冲突。
- [ ] Commit only compare-ui task files.

### Task 6: Cross-project review and delivery

- [ ] Independent review of complete diffs with five Review Focus conditions; fix each finding with a regression reproducer before claiming closure.
- [ ] Cross-project actual NEW SQL fixture: frozen-1 =>14列=>bd strict import=>stored-1=>Spark full=>all types/counts=>paged UI。
- [ ] Run appropriate full backend/service/Spark3.4+3.5/UI builds once after final changes; repeat only for new changes/failures.
- [ ] Copy verified Axon frontend bundle into local backendstatic and compare file manifests; no unsolicitedZIP。
- [ ] Update Obsidian implementation status with actual evidence, `git diff --check`、frontmatter/links checks；commit docs。
- [ ] Fetch and integrate latest remote main for each repo, push authorized commits withoutforce；preserve all unrelated dirty/source branches。
- [ ] Report concise completion, commits and migration/deployment prerequisites, explicitly separate local verification from production validation。
