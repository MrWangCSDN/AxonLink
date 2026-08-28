# 回放问题查询/动账导入模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在正式回放问题 Excel 导入中增加默认查询与动账两种模式，动账模式统一把明细和汇总批次的 `RPT` 前缀转换为 `DZ`，并让日报只在同批次族内滚动，同时退役前端全量更新入口。

**Architecture:** 使用一个后端枚举作为导入模式和批次规范化的单一事实源；明细解析器与汇总解析器在生成 DTO 时调用同一规范化函数，确保数据库、出现批次和日报批次一致。日报服务依据当前批次的 `RPT`/`DZ` 族筛选上一份日报；前端只负责默认选择模式并随 multipart 上传，不在浏览器修改工作簿。

**Tech Stack:** Java 17、Spring Boot MVC、Apache POI、JUnit 5、H2、Vue 3、Vitest、Vite。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` 的“查询与动账导入模式（2026-08-21）”章节，以及 `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md` 的 `POST /import` 契约。

## Global Constraints

- “导入回放问题”默认模式必须是 `QUERY`；未传 `replayType` 的旧调用也按 `QUERY`。
- `DZ` 模式只替换批次字符串开头的一次 `RPT`；已是 `DZ` 或其他前缀时保持原值。
- 明细 `batch_no` 与“汇总信息”上下区域 `batchNo` 必须在合并和日报生成前完成相同转换。
- RPT 日报只能继承 RPT 日报，DZ 日报只能继承 DZ 日报；两类可交替导入但绝不互相比较。
- 同批次重复导入覆盖同名日报；选择上一日报时排除当前文件。
- 删除前端全量更新按钮、弹窗、状态和 API 调用；后端 `/full-refresh` 暂时保留。
- 不新增数据库表或字段，不迁移历史数据，不改变除批次前缀外的导入状态机。
- 工作区已有大量用户修改；不得执行 `git reset`、`git checkout --`、`git clean`，不得提交或修改无关文件。

---

### Task 1: 建立导入模式与批次规范化单一事实源

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportMode.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueExcelParser.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParser.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueExcelParserTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParserTest.java`

**Interfaces:**
- Produces: `ReplayIssueImportMode.fromRequest(String)`，非法值抛 `IllegalArgumentException`。
- Produces: `ReplayIssueImportMode.normalizeBatch(String)`，`QUERY` 原样返回，`DZ` 将开头 `RPT` 替换成 `DZ`。
- Produces: `ReplayIssueExcelParser.parse(MultipartFile, ReplayIssueImportMode)`，旧 `parse(MultipartFile)` 委托 `QUERY`。
- Produces: `ReplayIssueSummaryParser.parse(MultipartFile, ReplayIssueImportMode)`，旧 `parse(MultipartFile)` 委托 `QUERY`。

- [ ] **Step 1: 写导入模式和两个解析器的失败测试**

在明细解析测试中增加：

```java
@Test
void dzModeNormalizesRptBatchWithoutChangingAlreadyDzOrQueryRows() throws Exception {
    MockMultipartFile rpt = ReplayIssueTestFixtures.workbook(
            ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of("批次", "RPT20260820-142055-9860")),
            ReplayIssueTestFixtures.HEADERS, true);

    assertEquals("RPT20260820-142055-9860",
            parser.parse(rpt, ReplayIssueImportMode.QUERY).rows().get(0).batchNo());
    assertEquals("DZ20260820-142055-9860",
            parser.parse(rpt, ReplayIssueImportMode.DZ).rows().get(0).batchNo());
}

@Test
void importModeRejectsUnknownValue() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ReplayIssueImportMode.fromRequest("OTHER"));
    assertEquals("未知回放类型：OTHER", error.getMessage());
}
```

在汇总解析测试中复用现有 `writeTwoLevelSection` 构造两段横排工作簿：

```java
@Test
void dzModeNormalizesBothSummarySections() throws Exception {
    MockMultipartFile file;
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        Sheet sheet = workbook.createSheet("汇总信息");
        writeTwoLevelSection(sheet, 0, "RPT20260819-100000-0001", "存款组", 528, 1000);
        writeTwoLevelSection(sheet, 5, "RPT20260820-142055-9860", "公共组", 256, 2000);
        workbook.write(out);
        file = new MockMultipartFile("file", "summary.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
    }

    ReplayIssueSummaryParser.ParsedSummary parsed =
            parser.parse(file, ReplayIssueImportMode.DZ);

    assertEquals("DZ20260819-100000-0001", parsed.upperRows().get(0).batchNo());
    assertEquals("DZ20260820-142055-9860", parsed.lowerRows().get(0).batchNo());
}
```

- [ ] **Step 2: 运行测试并确认因接口不存在而失败**

Run:

```bash
mvn -q -Dtest=ReplayIssueExcelParserTest,ReplayIssueSummaryParserTest test
```

Expected: FAIL，提示 `ReplayIssueImportMode` 或带模式的 `parse` 方法不存在。

- [ ] **Step 3: 实现枚举和解析器重载**

新增枚举核心实现：

```java
public enum ReplayIssueImportMode {
    QUERY, DZ;

    public static ReplayIssueImportMode fromRequest(String value) {
        if (value == null || value.isBlank()) return QUERY;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知回放类型：" + value.trim());
        }
    }

    public String normalizeBatch(String batch) {
        if (batch == null || this != DZ || !batch.startsWith("RPT")) return batch;
        return "DZ" + batch.substring(3);
    }
}
```

明细解析器保持旧入口兼容：

```java
public ParsedWorkbook parse(MultipartFile file) throws IOException {
    return parse(file, ReplayIssueImportMode.QUERY);
}

public ParsedWorkbook parse(MultipartFile file, ReplayIssueImportMode mode) throws IOException {
    try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
        // 保留现有页签、表头和行读取流程；唯一差异是把 mode 传给 toReplayIssueRow。
    }
}
```

构造 `ReplayIssueRow` 时把 `values.get(2)` 改为 `mode.normalizeBatch(values.get(2))`。汇总解析器采用同样重载，在 `parseSheet` 完成后统一转换上下区域：

```java
private ParsedSummary normalizeBatches(ParsedSummary summary, ReplayIssueImportMode mode) {
    return new ParsedSummary(
            summary.upperRows().stream().map(row -> normalizeBatch(row, mode)).toList(),
            summary.lowerRows().stream().map(row -> normalizeBatch(row, mode)).toList(),
            summary.sheetFound(), summary.upperTotals(), summary.lowerTotals());
}

private ReplayIssueSummaryRow normalizeBatch(ReplayIssueSummaryRow row, ReplayIssueImportMode mode) {
    return new ReplayIssueSummaryRow(mode.normalizeBatch(row.batchNo()), row.domain(),
            row.coveredInterfaceCount(), row.sentTransactionCount(), row.c528SuccessCcbsFail(),
            row.ccbsFailureDetail(), row.c528FailCcbsSuccess(), row.bothFailSameCode(),
            row.bothFailDiffCode(), row.bothSuccess(), row.codeIgnored(), row.successRate(),
            row.matchPassRate(), row.part(), row.rawJson());
}
```

- [ ] **Step 4: 运行解析器测试并确认通过**

Run:

```bash
mvn -q -Dtest=ReplayIssueExcelParserTest,ReplayIssueSummaryParserTest test
```

Expected: PASS。

---

### Task 2: 把模式贯穿正式导入接口、数据合并和日报生成

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryImportIntegrationTest.java`

**Interfaces:**
- Consumes: `ReplayIssueImportMode.fromRequest(String)` 与两个解析器的模式重载。
- Produces: `ReplayIssueImportService.importFile(MultipartFile, ReplayIssueImportMode)`。
- Preserves: `ReplayIssueImportService.importFile(MultipartFile)` 委托 `QUERY`，避免现有测试和调用方大面积改变。
- HTTP contract: multipart 可选字段 `replayType=QUERY|DZ`。

- [ ] **Step 1: 写控制器非法模式和动账端到端失败测试**

控制器增加：

```java
@Test
void importRejectsUnknownReplayType() throws Exception {
    mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                    .file(ReplayIssueTestFixtures.validWorkbook(1))
                    .param("replayType", "OTHER")
                    .header("X-DII-Trigger-Token", "secret"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("未知回放类型：OTHER"));
}
```

集成测试构造明细和汇总均为 `RPT20260820-142055-9860`，调用：

```java
ReplayIssueImportResult result = service.importFile(file, ReplayIssueImportMode.DZ);

assertEquals(8, result.totalRows());
assertEquals(8, jdbc.queryForObject(
        "SELECT COUNT(*) FROM dii_replay_issue WHERE batch_no=?",
        Integer.class, "DZ20260820-142055-9860"));
assertEquals(8, jdbc.queryForObject(
        "SELECT COUNT(*) FROM dii_replay_issue_occurrence_batch WHERE batch_name=?",
        Integer.class, "DZ20260820-142055-9860"));
assertTrue(Files.exists(reportDirectory.resolve("DZ20260820-142055-9860日报.xlsx")));
```

- [ ] **Step 2: 运行接口和集成测试并确认失败**

Run:

```bash
mvn -q -Dtest=ReplayIssueControllerTest#importRejectsUnknownReplayType,ReplayIssueSummaryImportIntegrationTest test
```

Expected: FAIL，控制器未读取 `replayType`，服务尚无带模式重载。

- [ ] **Step 3: 实现控制器参数与服务编排**

控制器签名增加：

```java
@RequestParam(value = "replayType", required = false) String replayType
```

并在调用服务前执行：

```java
ReplayIssueImportMode mode = ReplayIssueImportMode.fromRequest(replayType);
return ResponseEntity.ok(R.ok(importService.importFile(file, mode)));
```

服务保持兼容入口：

```java
public ReplayIssueImportResult importFile(MultipartFile file) throws IOException {
    return importFile(file, ReplayIssueImportMode.QUERY);
}

public ReplayIssueImportResult importFile(MultipartFile file, ReplayIssueImportMode mode) throws IOException {
    // 文件大小和互斥门保持原逻辑
    ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file, mode);
    // merge 保持不变
    ReplayIssueSummaryParser.ParsedSummary summary = summaryParser.parse(file, mode);
    // 日报使用已经规范化的 currentBatch
}
```

- [ ] **Step 4: 运行控制器和导入集成测试并确认通过**

Run:

```bash
mvn -q -Dtest=ReplayIssueControllerTest#importRejectsUnknownReplayType,ReplayIssueSummaryImportIntegrationTest test
```

Expected: PASS，动账模式的当前问题、出现批次和日报文件均为 DZ。

---

### Task 3: 日报上一批次只在同批次族中选择

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Produces: package-private/static `batchFamily(String)`，返回 `RPT`、`DZ` 或 `LEGACY`。
- Changes: `findPreviousReport(String currentBatch)` 只保留 `sameBatchFamily(currentBatch, candidateBatch)` 的文件。
- Preserves: 非 RPT/DZ 的既有 `BATCH-*` 测试与历史日报仍属于 `LEGACY`，继续按原修改时间滚动。

- [ ] **Step 1: 写 RPT/DZ 交替导入隔离失败测试**

使用测试类现有 `summaryRow` 和 `sheetToString` 方法，并直接打开结果工作簿断言：

```java
@Test
void rptAndDzReportsRollForwardOnlyWithinTheirOwnFamily() throws Exception {
    service.generateNext("RPT20260819-100000-0001", LocalDateTime.now(),
            new ReplayIssueSummaryParser.ParsedSummary(
                    List.of(summaryRow("RPT20260818-100000-0001", "存款组", 100L)),
                    List.of(summaryRow("RPT20260819-100000-0001", "存款组", 201L)), true));
    service.generateNext("DZ20260819-110000-0001", LocalDateTime.now(),
            new ReplayIssueSummaryParser.ParsedSummary(
                    List.of(summaryRow("DZ20260818-110000-0001", "存款组", 300L)),
                    List.of(summaryRow("DZ20260819-110000-0001", "存款组", 401L)), true));

    Path rptNext = service.generateNext("RPT20260820-100000-0001", LocalDateTime.now(),
            new ReplayIssueSummaryParser.ParsedSummary(List.of(),
                    List.of(summaryRow("RPT20260820-100000-0001", "存款组", 502L)), true));
    Path dzNext = service.generateNext("DZ20260820-110000-0001", LocalDateTime.now(),
            new ReplayIssueSummaryParser.ParsedSummary(List.of(),
                    List.of(summaryRow("DZ20260820-110000-0001", "存款组", 602L)), true));

    try (XSSFWorkbook workbook = new XSSFWorkbook(rptNext.toFile())) {
        assertTrue(sheetToString(workbook.getSheet("汇总信息"))
                .contains("批次号：RPT20260819-100000-0001（上一批次）"));
    }
    try (XSSFWorkbook workbook = new XSSFWorkbook(dzNext.toFile())) {
        assertTrue(sheetToString(workbook.getSheet("汇总信息"))
                .contains("批次号：DZ20260819-110000-0001（上一批次）"));
    }
}
```

再增加“目录只有 RPT、首次导入 DZ 仍使用本次 Excel 上半区”的断言，以及同名文件排除测试。

- [ ] **Step 2: 运行日报测试并确认当前实现串族失败**

Run:

```bash
mvn -q -Dtest=ReplayIssueDailyReportServiceTest test
```

Expected: FAIL，RPT 下一批次错误继承最近修改的 DZ 日报，或 DZ 首次导入错误被 RPT 影响。

- [ ] **Step 3: 实现批次族筛选**

在日报服务中从安全文件名反解候选批次（移除固定 `日报.xlsx` 后缀），并增加：

```java
private static String batchFamily(String batch) {
    if (batch != null && batch.startsWith("RPT")) return "RPT";
    if (batch != null && batch.startsWith("DZ")) return "DZ";
    return "LEGACY";
}

private static boolean sameBatchFamily(String current, Path candidate) {
    String fileName = candidate.getFileName().toString();
    String candidateBatch = fileName.substring(0, fileName.length() - "日报.xlsx".length());
    return batchFamily(current).equals(batchFamily(candidateBatch));
}
```

`findPreviousReport` 的 stream 在排除当前同名文件后增加 `sameBatchFamily` 过滤，再按修改时间取最大值。只处理由 `reportFileName` 约定产生的 `*日报.xlsx`，避免其他 xlsx 干扰。

- [ ] **Step 4: 运行日报专项测试并确认通过**

Run:

```bash
mvn -q -Dtest=ReplayIssueDailyReportServiceTest test
```

Expected: PASS，RPT/DZ 各自继承同族最近日报，LEGACY 回归不变。

---

### Task 4: 前端增加模式单选并退役全量更新入口

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`

**Interfaces:**
- Changes: `importReplayIssues(file, token, replayType = 'QUERY')`，multipart 增加 `replayType`。
- UI state: `const importReplayType = ref('QUERY')`；每次打开导入弹窗重置为 `QUERY`。
- Removes: `fullRefreshReplayIssues` 导出、页面 import、全量更新按钮/弹窗/状态/方法。

- [ ] **Step 1: 写默认查询、选择动账、删除全量入口的失败测试**

页面测试增加：

```javascript
it('defaults formal import to query, submits dz when selected, and has no full refresh entry', async () => {
  const wrapper = mount(ReplayIssuePage)
  await flushPromises()

  expect(wrapper.find('[data-testid="open-full-refresh"]').exists()).toBe(false)
  const selectedFile = await openImport(wrapper)
  expect(wrapper.get('[data-testid="import-type-query"]').element.checked).toBe(true)

  await wrapper.get('[data-testid="import-type-dz"]').setValue(true)
  await wrapper.get('[data-testid="submit-import"]').trigger('click')
  await flushPromises()

  expect(importReplayIssues).toHaveBeenCalledWith(selectedFile, 'secret', 'DZ')
  expect(wrapper.find('[data-testid="full-refresh-modal"]').exists()).toBe(false)
})
```

API 测试捕获 `fetch` 的 `FormData` 并断言：

```javascript
await importReplayIssues(file, 'secret', 'DZ')
const options = fetch.mock.calls[0][1]
expect(options.body.get('replayType')).toBe('DZ')
```

- [ ] **Step 2: 运行前端定向测试并确认失败**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js
```

Expected: FAIL，单选框不存在、API 未上传模式、全量入口仍存在。

- [ ] **Step 3: 实现导入模式单选和清理全量更新代码**

导入弹窗在文件字段前增加：

```vue
<fieldset class="replay-import-type" :disabled="importing">
  <legend>回放类型</legend>
  <label><input v-model="importReplayType" data-testid="import-type-query" type="radio" value="QUERY" />查询</label>
  <label><input v-model="importReplayType" data-testid="import-type-dz" type="radio" value="DZ" />动账</label>
</fieldset>
```

`openImport()` 设置 `importReplayType.value = 'QUERY'`，提交改为：

```javascript
const result = await importReplayIssues(importFile.value, importToken.value, importReplayType.value)
```

API multipart 增加：

```javascript
formData.append('replayType', replayType || 'QUERY')
```

删除工具栏全量更新按钮、整个 `full-refresh-modal`、`fullRefreshReplayIssues` import、所有 `fullRefresh*` refs/functions 及仅供该入口使用的样式。保留 `RefreshCw` 仅当页面其他位置仍使用；否则同步删除图标 import。

- [ ] **Step 4: 运行前端定向测试并确认通过**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js
```

Expected: PASS。

---

### Task 5: 全链路回归、生产构建与交付核验

**Files:**
- Generated: `src/main/resources/static/**`
- Generated: `target/axon-link-server-1.0.0.jar`
- Modify after implementation evidence: `/Users/java/obsidian/log.md`

**Interfaces:**
- Verifies: 后端导入、日报、控制器、前端交互和生产静态资源形成一致交付物。

- [ ] **Step 1: 运行所有受影响后端测试**

Run:

```bash
cd /Users/java/axon-link-server
mvn -q -Dtest=ReplayIssueExcelParserTest,ReplayIssueSummaryParserTest,ReplayIssueImportServiceTest,ReplayIssueSummaryImportIntegrationTest,ReplayIssueDailyReportServiceTest,ReplayIssueControllerTest test
```

Expected: PASS。若控制器仍存在已确认的批次跟踪基线失败，必须列出精确用例并证明与本改动无关；不得笼统宣称全套通过。

- [ ] **Step 2: 运行完整前端测试**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test
```

Expected: 全部 PASS，无 Vitest worker 超时。

- [ ] **Step 3: 构建生产前端到后端 static**

Run:

```bash
cd /Users/java/axon-link-frontend
VITE_USE_MOCK=0 npm run build
```

Expected: Vite exit 0，产物写入 `/Users/java/axon-link-server/src/main/resources/static`。

- [ ] **Step 4: 打包后端并核验 JAR 资源**

Run:

```bash
cd /Users/java/axon-link-server
mvn -q -DskipTests package
jar tf target/axon-link-server-1.0.0.jar | rg 'BOOT-INF/classes/static/index.html|ReplayIssueImportMode.class'
```

Expected: 打包 exit 0，JAR 同时包含新模式类和生产前端入口。

- [ ] **Step 5: 执行残留与范围检查**

Run:

```bash
rg -n "open-full-refresh|full-refresh-modal|fullRefreshReplayIssues" /Users/java/axon-link-frontend/src
rg -n "replayType|ReplayIssueImportMode|sameBatchFamily" src/main/java src/test/java /Users/java/axon-link-frontend/src
git diff --check
```

Expected: 第一条无结果；第二条只命中设计范围文件；`git diff --check` 无新增空白错误。

- [ ] **Step 6: 更新实施日志并交付真实验证结果**

使用 `apply_patch` 在 `/Users/java/obsidian/log.md` 追加一条 2026-08-21 `[IMPL]` 记录，写明实际通过的测试数量、生产构建、JAR 核验和任何既有失败。最终列出修改文件，明确无数据库结构变更，不提交、不推送。
