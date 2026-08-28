# 交易链路按领域全量导出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在交易链路页按当前领域跨分页导出交易、服务、构件、数据库表四 Sheet Excel，并确保结果与现有 `getChain` 页面口径一致。

**Architecture:** 新建独立 `FlowtranChainExportService`，实现类通过 `FlowtranService.listTransactions` 每 100 条读取领域交易，并逐笔复用 `getChain` 生成去重、排序后的流式工作簿。`FlowtranController` 仅负责 HTTP 下载契约，Vue 页面只传当前领域并管理按钮加载/失败状态，不在浏览器逐笔加载链路。

**Tech Stack:** Java 17、Spring Boot、Neo4j Driver（经现有服务间接使用）、Apache POI SXSSF、JUnit 5、Spring MockMvc、Vue 3、Vitest、Vue Test Utils、Vite。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/交易链路按领域全量导出-系统设计.md`；字段与接口分别见同目录的 `交易链路按领域全量导出-数据模型.md`、`交易链路按领域全量导出-API接口.md`。

## Global Constraints

- 导出范围固定为当前领域全部交易，忽略页面搜索条件、当前页和卡片展开状态。
- Excel 固定包含 `交易清单`、`服务清单`、`构件清单`、`数据库表清单` 四个 Sheet。
- 服务、构件、数据库表在同一交易内按“交易码 + 节点编码”去重；跨交易引用必须分别保留。
- 所有 Sheet 先按交易码正序，同一交易内再按节点编码正序。
- 单笔链路解析失败写入交易清单的导出状态与失败原因，不中断其他交易。
- 不新增数据库表、异步任务、文件缓存、DAO 方法 Sheet或新的 Neo4j 链路算法。
- 保留用户现有工作区改动；不执行 `git reset`、`git clean`、提交或推送。

---

### Task 1: 领域链路 Excel 导出服务

**Files:**
- Create: `src/main/java/com/axonlink/service/FlowtranChainExportService.java`
- Create: `src/main/java/com/axonlink/service/impl/FlowtranChainExportServiceImpl.java`
- Create: `src/test/java/com/axonlink/service/impl/FlowtranChainExportServiceImplTest.java`

**Interfaces:**
- Consumes: `FlowtranService.listTransactions(String domainKey, int page, int size, String keyword)` and `FlowtranService.getChain(String txId)`.
- Produces: `FlowtranChainExportService.ExportFile exportDomain(String domainKey)`，其中 `ExportFile` 提供 `getFileName()` 与 `getContent()`。

- [x] **Step 1: Write the failing workbook contract test**

创建不使用 Mockito 的 `StubFlowtranService`，让第一页返回两个逆序交易、第二页返回空；为每个交易返回包含 service/component/data.table 的链路 Map。使用 `XSSFWorkbook` 读取导出字节并断言：

```java
@Test
void exportsFourSheetsSortedByTransactionAndNodeCode() throws Exception {
    FlowtranChainExportService.ExportFile file = service.exportDomain("public");
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
        assertEquals(List.of("交易清单", "服务清单", "构件清单", "数据库表清单"),
            IntStream.range(0, workbook.getNumberOfSheets())
                .mapToObj(i -> workbook.getSheetAt(i).getSheetName()).toList());
        assertEquals("TX001", workbook.getSheet("交易清单").getRow(1).getCell(2).getStringCellValue());
        assertEquals("SVC-A", workbook.getSheet("服务清单").getRow(1).getCell(2).getStringCellValue());
    }
}
```

同一测试数据在 `TX001` 中重复放入 `SVC-A`，断言服务 Sheet 只出现一次；再让 `TX002` 也引用 `SVC-A`，断言跨交易保留两行。

- [x] **Step 2: Run the workbook test and verify RED**

Run:

```bash
mvn -q -Dtest=FlowtranChainExportServiceImplTest#exportsFourSheetsSortedByTransactionAndNodeCode test
```

Expected: FAIL because `FlowtranChainExportService` and its implementation do not exist.

- [x] **Step 3: Define the focused export interface**

```java
public interface FlowtranChainExportService {
    ExportFile exportDomain(String domainKey);

    final class ExportFile {
        private final String fileName;
        private final byte[] content;

        public ExportFile(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }

        public String getFileName() { return fileName; }
        public byte[] getContent() { return content; }
    }
}
```

- [x] **Step 4: Implement paged collection and four-sheet projection**

在实现类中固定 `PAGE_SIZE = 100`，首次结果的 `total` 决定总页数；如果总数为 0，抛出：

```java
throw new NoSuchElementException("未找到可导出的领域交易：" + domainKey);
```

逐笔调用 `getChain(txId)`，从以下路径提取节点：

```text
chain.chain.service
chain.chain.component
chain.chain.data.table
```

按 `txId + '\u0000' + nodeCode` 放入 `LinkedHashMap` 去重，排序后写入 `SXSSFWorkbook(100)`。表头必须逐字采用数据模型文档的四组列名；空值写空字符串、数量写 numeric cell。设置冻结首行、自动筛选、固定列宽，最终调用 `dispose()` 删除 SXSSF 临时文件。

- [x] **Step 5: Add failure-isolation and empty-domain tests**

```java
@Test
void keepsFailedTransactionAndContinuesExportingLaterTransactions() throws Exception {
    stub.setTransactions(List.of(tx("TX001"), tx("TX002")));
    stub.setChain("TX001", null);
    stub.setChain("TX002", chain("TX002", List.of(node("SVC-B")), List.of(), List.of()));

    ExportFile file = service.exportDomain("public");

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
        Sheet transactions = workbook.getSheet("交易清单");
        assertEquals("TX001", transactions.getRow(1).getCell(2).getStringCellValue());
        assertEquals("失败", transactions.getRow(1).getCell(9).getStringCellValue());
        assertFalse(transactions.getRow(1).getCell(10).getStringCellValue().isBlank());
        assertEquals("TX002", workbook.getSheet("服务清单").getRow(1).getCell(0).getStringCellValue());
    }
}

@Test
void rejectsDomainWithoutTransactions() {
    stub.setTransactions(List.of());
    assertThrows(NoSuchElementException.class, () -> service.exportDomain("missing"));
}
```

测试内 `StubFlowtranService` 明确定义 `setTransactions(List<FlowtranTransaction>)`、`setChain(String, Map<String,Object>)`；`listTransactions` 按传入 `page/size` 对列表切片并返回 `list/total/page/size`，保证分页行为也由真实测试数据验证。

- [x] **Step 6: Run service tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=FlowtranChainExportServiceImplTest test
```

Expected: all export service tests PASS; generated workbook contains exactly four sheets.

---

### Task 2: HTTP 下载接口与中文文件名

**Files:**
- Modify: `src/main/java/com/axonlink/controller/FlowtranController.java`
- Create: `src/test/java/com/axonlink/controller/FlowtranChainExportControllerTest.java`

**Interfaces:**
- Consumes: `FlowtranChainExportService.exportDomain(String domainKey)` from Task 1.
- Produces: `GET /api/flowtran/domains/{domainKey}/chains/export` returning `.xlsx` bytes.

- [x] **Step 1: Write failing MockMvc tests**

使用 Mockito 构造 `FlowtranController` 的现有依赖和新导出服务，至少覆盖：

```java
@Test
void exportsCurrentDomainWithUtf8FileName() throws Exception {
    when(exportService.exportDomain("public")).thenReturn(
        new ExportFile("公共领域-全量交易链路-20260824_153000.xlsx", new byte[]{1, 2, 3}));
    mvc.perform(get("/api/flowtran/domains/public/chains/export"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(header().string("Content-Disposition", containsString("UTF-8''")))
        .andExpect(content().bytes(new byte[]{1, 2, 3}));
}

@Test
void returnsNotFoundWhenDomainHasNoTransactions() throws Exception {
    when(exportService.exportDomain("missing"))
        .thenThrow(new NoSuchElementException("未找到可导出的领域交易：missing"));
    mvc.perform(get("/api/flowtran/domains/missing/chains/export"))
        .andExpect(status().isNotFound());
}
```

- [x] **Step 2: Run controller tests and verify RED**

Run:

```bash
mvn -q -Dtest=FlowtranChainExportControllerTest test
```

Expected: FAIL because the controller endpoint and constructor dependency are absent.

- [x] **Step 3: Add the controller dependency and endpoint**

在构造器注入 `FlowtranChainExportService`，新增：

```java
@GetMapping("/domains/{domainKey}/chains/export")
public ResponseEntity<?> exportDomainChains(@PathVariable String domainKey) {
    try {
        return asExcel(flowtranChainExportService.exportDomain(domainKey));
    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    } catch (NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    } catch (Exception e) {
        return ResponseEntity.internalServerError().body("交易链路导出失败：" + e.getMessage());
    }
}
```

为新 `ExportFile` 增加独立重载：

```java
private ResponseEntity<byte[]> asExcel(FlowtranChainExportService.ExportFile exportFile) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    headers.setContentDisposition(ContentDisposition.attachment()
        .filename(exportFile.getFileName(), StandardCharsets.UTF_8).build());
    return new ResponseEntity<>(exportFile.getContent(), headers, HttpStatus.OK);
}
```

链路导出服务不得依赖影响分析导出服务。

- [x] **Step 4: Run controller and existing flowtran tests**

Run:

```bash
mvn -q -Dtest=FlowtranChainExportControllerTest,FlowtranChainExportServiceImplTest,FlowtranControllerErrorCodeTest test
```

Expected: all selected tests PASS; existing错误码接口不受影响。

---

### Task 3: 前端下载 API 与当前领域按钮

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/index.js`
- Modify: `/Users/java/axon-link-frontend/src/views/TransactionAnalysis.vue`
- Modify: `/Users/java/axon-link-frontend/src/views/TransactionAnalysis.spec.js`

**Interfaces:**
- Consumes: Task 2 endpoint and shared `download(url, fallbackFileName)`.
- Produces: `exportFlowtranDomainChains(domainKey)` and a `data-testid="export-domain-chains"` page action.

- [x] **Step 1: Write failing frontend interaction tests**

在 API mock 中新增 `exportFlowtranDomainChains: vi.fn()`，让 `getFlowtranDomains` 返回公共领域，并让首屏交易查询返回空页。添加测试：

```javascript
it('exports all chains for the active domain and ignores page filters', async () => {
  getFlowtranDomains.mockResolvedValue([{ id: 'public', name: '公共领域', count: 330 }])
  getFlowtranTransactions.mockResolvedValue({ list: [], total: 330, page: 1, size: 10 })
  exportFlowtranDomainChains.mockResolvedValue({ fileName: '公共领域-全量交易链路.xlsx' })
  const wrapper = mountPage()
  await flushPromises()
  await wrapper.get('[data-testid="export-domain-chains"]').trigger('click')
  await flushPromises()
  expect(exportFlowtranDomainChains).toHaveBeenCalledWith('public')
})
```

再用 pending Promise 断言请求期间按钮 `disabled` 且文字为“导出中…”。

- [x] **Step 2: Run the frontend test and verify RED**

Run:

```bash
npm run test -- src/views/TransactionAnalysis.spec.js
```

Workdir: `/Users/java/axon-link-frontend`

Expected: FAIL because the API export function and button do not exist.

- [x] **Step 3: Implement the API wrapper**

在 `src/api/index.js` 增加：

```javascript
export function exportFlowtranDomainChains(domainKey) {
  return download(
    `/flowtran/domains/${encodeURIComponent(domainKey)}/chains/export`,
    `${domainKey}-全量交易链路.xlsx`,
  )
}
```

- [x] **Step 4: Implement the page action and error state**

在标题栏“全量错误码下载”旁增加按钮，使用现有下载图标风格：

```vue
<button
  class="action-btn"
  data-testid="export-domain-chains"
  :disabled="!activeDomain?.id || chainExporting"
  @click="downloadDomainChains"
>
  {{ chainExporting ? '导出中…' : '全量链路下载' }}
</button>
<span v-if="chainExportError" class="chain-export-error" role="alert">
  {{ chainExportError }}
</span>
```

脚本中增加 `const chainExporting = ref(false)` 和 `const chainExportError = ref('')`。实现固定为：请求前清空错误并置加载态；`downloadDomainChains()` 仅传 `activeDomain.value.id`；`catch (error)` 将 `chainExportError.value` 设置为 `error?.message || '交易链路导出失败'`；`finally` 恢复加载状态。为 `.chain-export-error` 增加红色、单行省略样式和完整 `title`，确保错误可见且不撑坏标题栏。

在 Vitest 中补充失败断言：

```javascript
exportFlowtranDomainChains.mockRejectedValueOnce(new Error('Neo4j 不可用'))
await wrapper.get('[data-testid="export-domain-chains"]').trigger('click')
await flushPromises()
expect(wrapper.get('[role="alert"]').text()).toContain('Neo4j 不可用')
```

- [x] **Step 5: Run frontend tests and verify GREEN**

Run:

```bash
npm run test -- src/views/TransactionAnalysis.spec.js
```

Expected: TransactionAnalysis tests PASS, including domain parameter and loading state assertions.

---

### Task 4: 联合回归、生产打包与设计落地记录

**Files:**
- Modify after implementation: `/Users/java/obsidian/log.md`
- Generated: `src/main/resources/static/**`
- Generated: `target/axon-link-server-1.0.0.jar`

**Interfaces:**
- Consumes: Tasks 1–3 completed code.
- Produces: verified production frontend assets, executable backend JAR, and knowledge-base implementation record.

- [x] **Step 1: Run focused backend regression**

```bash
mvn -q -Dtest=FlowtranChainExportServiceImplTest,FlowtranChainExportControllerTest,FlowtranControllerErrorCodeTest test
```

Expected: all selected tests PASS with zero failures/errors.

- [x] **Step 2: Run frontend regression**

```bash
npm run test -- src/views/TransactionAnalysis.spec.js
```

Workdir: `/Users/java/axon-link-frontend`

Expected: all TransactionAnalysis tests PASS with zero failures.

- [x] **Step 3: Build production frontend into backend**

```bash
VITE_USE_MOCK=0 npm run build
```

Workdir: `/Users/java/axon-link-frontend`

Expected: Vite exits 0 and writes `../axon-link-server/src/main/resources/static/index.html` plus hashed assets.

- [x] **Step 4: Package backend and verify resources**

```bash
mvn -q -DskipTests package
jar tf target/axon-link-server-1.0.0.jar | rg 'BOOT-INF/classes/static/index.html|FlowtranChainExportService.class|FlowtranChainExportServiceImpl.class'
```

Expected: Maven exits 0 and all three required resource/class entries are present.

- [x] **Step 5: Append implementation log after verification**

向 `/Users/java/obsidian/log.md` 追加一行，实际测试数量必须取本次命令输出：

```text
2026-08-24 [IMPL] 交易链路按领域全量导出落地 | 更新后端、前端、静态资源、测试和实施计划 | 当前领域跨分页导出交易/服务/构件/数据库表四 Sheet；复用 getChain，单笔失败继续；后端定向与前端测试通过，生产 JAR 关键资源核验通过
```

- [x] **Step 6: Inspect only task-owned diffs**

```bash
git diff -- src/main/java/com/axonlink/service/FlowtranChainExportService.java \
  src/main/java/com/axonlink/service/impl/FlowtranChainExportServiceImpl.java \
  src/main/java/com/axonlink/controller/FlowtranController.java \
  src/test/java/com/axonlink/service/impl/FlowtranChainExportServiceImplTest.java \
  src/test/java/com/axonlink/controller/FlowtranChainExportControllerTest.java \
  src/main/resources/static
```

并在前端仓库检查 `src/api/index.js`、`src/views/TransactionAnalysis.vue`、`src/views/TransactionAnalysis.spec.js`。不得清理或覆盖其他工作区改动。
