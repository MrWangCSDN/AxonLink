# Parallel Replay Issue List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-level “并行回放” menu and a “回放问题清单” page that atomically imports eight Excel sheets and serves a fixed-header, server-paginated issue table.

**Architecture:** A dedicated `com.axonlink.ai.replay` backend module parses the workbook by header name, derives group/sandbox metadata from sheet names, and replaces `dii_replay_issue` in one result-database transaction. A focused Vue page calls separate replay APIs, keeps controls and pagination fixed, and scrolls only the 26-column data viewport.

**Tech Stack:** Java 17, Spring Boot, Apache POI, JdbcTemplate, H2 tests, Vue 3, Vite 8, Vitest, Vue Test Utils, happy-dom.

## Global Constraints

- Design source of truth: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`, `并行回放问题清单-数据模型.md`, and `并行回放问题清单-API接口.md`.
- Only these sheets are imported: `公共组`, `存款组`, `贷款组`, `结算组`, `沙箱-公共组`, `沙箱-存款组`, `沙箱-贷款组`, `沙箱-结算组`; all eight are required and all other sheets are ignored.
- Excel A-Y values are read with `DataFormatter + FormulaEvaluator`; blank rows are skipped, but partially blank business rows remain valid.
- The eight target sheets must contain at least one total data row; an empty template must not clear the current snapshot.
- Import accepts `.xlsx` and `.xls`, rejects files over 50 MiB, reuses `X-DII-Trigger-Token`, and returns HTTP 409 for concurrent imports.
- Database replacement is a single transaction: delete old rows, batch insert all new rows, and roll back on any failure.
- List pagination uses `limit + offset`, defaults to 50, and clamps `limit` to 1-200.
- The visible table contains original Excel A-Y plus derived `是否沙箱`; internal `source_sheet` and `group_name` do not duplicate visible columns.
- Preserve all existing uncommitted backend and frontend changes; stage only files named by the current task.
- Do not add a duplicate design spec to the repository; this plan is an allowed implementation artifact.

---

### Task 1: Result Table, Row Model, and Transactional DAO

**Files:**
- Create: `src/main/resources/db/daoindex/V33__dii_replay_issue.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueFilterOptions.java`
- Create: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Create: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`
- Create: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `ReplayIssueDao.replaceAll(List<ReplayIssueRow>, LocalDateTime)`, `list(ReplayIssueQuery)`, `count(ReplayIssueQuery)`, `options()`, and `stats()`.
- Produces: immutable `ReplayIssueRow` and `ReplayIssueQuery` records used by parser, service, controller, and tests.
- Produces: test-only `ReplayIssueTestFixtures.newJdbc()`, `createSchema(JdbcTemplate)`, `row(String, boolean, int, String, String)`, `validWorkbook(int)`, `validWorkbook(int, boolean)`, `oneRowPerTargetSheet(Map<String,String>)`, and `workbook(Map<String,List<Map<String,String>>>, List<String>, boolean)`. Workbook helpers create all eight target sheets unless a test explicitly removes one.

- [ ] **Step 1: Write the failing DAO tests**

Create an H2 MySQL-mode table matching V33 and write behavior tests that catch a non-transactional delete, wrong filter binding, wrong page bounds, and unstable ordering:

```java
@Test void replaceFailureKeepsPreviousSnapshot() {
    dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")), IMPORTED_AT);
    ReplayIssueRow bad = ReplayIssueTestFixtures.row(null, false, 2, "6209", "bad");
    assertThrows(DataAccessException.class,
            () -> dao.replaceAll(List.of(bad), IMPORTED_AT.plusDays(1)));
    assertEquals("old", dao.list(new ReplayIssueQuery(50, 0, null, null, null, null, null))
            .get(0).get("issue_description"));
}

@Test void listCombinesGroupSandboxLevelTypeAndKeyword() {
    dao.replaceAll(List.of(
            ReplayIssueTestFixtures.row("贷款组", false, 1, "6208", "CCBS响应不一致"),
            ReplayIssueTestFixtures.row("贷款组", true, 2, "6208", "沙箱数据缺失"),
            ReplayIssueTestFixtures.row("存款组", false, 3, "1001", "CCBS响应不一致")), IMPORTED_AT);
    ReplayIssueQuery q = new ReplayIssueQuery(50, 0, "贷款组", false,
            "交易级", "数据差异", "CCBS");
    assertEquals(1, dao.count(q));
    assertEquals("6208", dao.list(q).get(0).get("transaction_code"));
}
```

- [ ] **Step 2: Run the DAO test and verify RED**

Run: `mvn -Dtest=ReplayIssueDaoTest test`

Expected: compilation fails because `ReplayIssueDao`, `ReplayIssueRow`, and `ReplayIssueQuery` do not exist.

- [ ] **Step 3: Implement V33, immutable DTOs, and the DAO**

Use the exact table from the data-model design. Build the DAO transaction from its injected result `JdbcTemplate`, matching `DiiErrorCodeDao`:

```java
public ReplayIssueDao(JdbcTemplate diiResultJdbcTemplate) {
    this.jdbc = diiResultJdbcTemplate;
    this.txTemplate = new TransactionTemplate(
            new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
}

public void replaceAll(List<ReplayIssueRow> rows, LocalDateTime importedAt) {
    txTemplate.executeWithoutResult(status -> {
        jdbc.update("DELETE FROM dii_replay_issue");
        for (int from = 0; from < rows.size(); from += 2000) {
            batchInsert(rows.subList(from, Math.min(from + 2000, rows.size())), importedAt);
        }
    });
}
```

Implement all filters with placeholders, clamp `limit`, clamp `offset`, and use the stable order `group_name, is_sandbox, row_order, id`. Return snake_case maps to match existing JdbcTemplate APIs.

- [ ] **Step 4: Run the DAO tests and verify GREEN**

Run: `mvn -Dtest=ReplayIssueDaoTest test`

Expected: all DAO replacement, rollback, filter, pagination, options, and stats tests pass.

- [ ] **Step 5: Commit Task 1 only**

```bash
git add src/main/resources/db/daoindex/V33__dii_replay_issue.sql \
  src/main/java/com/axonlink/ai/replay/dto \
  src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java \
  src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java \
  src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java
git commit -m "feat(replay): add issue snapshot persistence"
```

---

### Task 2: Header-Driven Eight-Sheet Excel Parser

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueExcelParser.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueExcelParserTest.java`

**Interfaces:**
- Consumes: `ReplayIssueRow` from Task 1.
- Produces: `ReplayIssueExcelParser.parse(MultipartFile)` returning nested record `ReplayIssueExcelParser.ParsedWorkbook(List<ReplayIssueRow>, Map<String,Integer>, int, int)` in the order rows, rowsBySheet, sandboxRows, nonSandboxRows.

- [ ] **Step 1: Write failing parser tests with real in-memory workbooks**

Build workbooks with Apache POI and literal expected values. Cover all required behaviors in separate tests:

```java
@Test void importsOnlyEightNamedSheetsAndDerivesSandbox() throws Exception {
    MockMultipartFile file = ReplayIssueTestFixtures.validWorkbook(1, true);
    ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file);
    assertEquals(8, parsed.rows().size());
    assertEquals(4, parsed.sandboxRows());
    assertEquals("公共组", parsed.rows().get(0).groupName());
    assertFalse(parsed.rows().get(0).sandbox());
    assertEquals("公共组", parsed.rows().get(4).groupName());
    assertTrue(parsed.rows().get(4).sandbox());
}

@Test void matchesHeadersAfterReorderingAndPreservesDisplayedIdentifiers() throws Exception {
    List<String> reversed = new ArrayList<>(ReplayIssueExcelParser.HEADERS);
    Collections.reverse(reversed);
    MockMultipartFile file = ReplayIssueTestFixtures.workbook(
            ReplayIssueTestFixtures.oneRowPerTargetSheet(
                    Map.of("流水号", "001012213710102", "issue_id", "000845")),
            reversed, false);
    ReplayIssueRow row = parser.parse(file).rows().get(0);
    assertEquals("001012213710102", row.serialNo());
    assertEquals("000845", row.issueId());
}
```

Also assert: missing sheet lists the missing name, missing header identifies sheet/header, auxiliary sheets are ignored, formulas use evaluated display text, fully blank rows are skipped, partially blank rows remain, duplicate headers fail, and all-empty target sheets fail.

- [ ] **Step 2: Run parser tests and verify RED**

Run: `mvn -Dtest=ReplayIssueExcelParserTest test`

Expected: compilation fails because `ReplayIssueExcelParser` is absent.

- [ ] **Step 3: Implement the parser**

Define the exact header order and immutable sheet metadata:

```java
static final List<String> HEADERS = List.of(
    "领域", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日期", "字段名",
    "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期",
    "需协同组", "解决人员", "流水号", "数据修复日期", "备注", "该问题出现在的交易笔数",
    "issue_id", "issue_key", "历史出现次数", "首次出现日期", "上次出现日期");
```

Scan only the first 20 rows for a header containing `领域` and `issue_key`; trim every header, normalize ASCII case only for `issue_id` and `issue_key`, and reject duplicates. Use `WorkbookFactory.create`, `FormulaEvaluator`, and `DataFormatter`; close workbook and input stream with try-with-resources.

- [ ] **Step 4: Run parser tests and verify GREEN**

Run: `mvn -Dtest=ReplayIssueExcelParserTest test`

Expected: every parser behavior passes with no database dependency.

- [ ] **Step 5: Commit Task 2 only**

```bash
git add src/main/java/com/axonlink/ai/replay/service/ReplayIssueExcelParser.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayIssueExcelParserTest.java
git commit -m "feat(replay): parse replay issue workbooks"
```

---

### Task 3: Serialized Import Coordinator

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueImportResult.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportBusyException.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`

**Interfaces:**
- Consumes: parser output from Task 2 and `ReplayIssueDao.replaceAll` from Task 1.
- Produces: `ReplayIssueImportService.importFile(MultipartFile)` and deterministic `ReplayIssueImportResult`.

- [ ] **Step 1: Write failing service integration tests**

Use a real parser and H2 DAO. Inject `Clock.fixed(Instant.parse("2026-08-04T02:00:00Z"), ZoneOffset.UTC)` and a `Semaphore` so timing and busy behavior are observable:

```java
@Test void importReplacesSnapshotAndReturnsPerSheetCounts() throws Exception {
    ReplayIssueImportResult result = service.importFile(ReplayIssueTestFixtures.validWorkbook(2));
    assertEquals(16, result.totalRows());
    assertEquals(2, result.rowsBySheet().get("沙箱-贷款组"));
    assertEquals(8, result.sandboxRows());
    assertEquals(16, dao.count(new ReplayIssueQuery(50, 0, null, null, null, null, null)));
}

@Test void concurrentImportIsRejectedWithoutChangingRows() {
    ReplayIssueImportService busy = new ReplayIssueImportService(parser, dao,
            Clock.fixed(IMPORTED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), new Semaphore(0));
    assertThrows(ReplayIssueImportBusyException.class,
            () -> busy.importFile(ReplayIssueTestFixtures.validWorkbook(1)));
    assertEquals(1, dao.count(ALL));
}
```

Also test 50 MiB rejection before parsing and verify parser failures leave previous data intact.

- [ ] **Step 2: Run service tests and verify RED**

Run: `mvn -Dtest=ReplayIssueImportServiceTest test`

Expected: compilation fails because service/result/busy exception are absent.

- [ ] **Step 3: Implement the coordinator**

Use a non-blocking permit for duplicate clicks and always release it:

```java
public ReplayIssueImportResult importFile(MultipartFile file) {
    if (file.getSize() > MAX_FILE_BYTES) throw new IllegalArgumentException("文件不能超过 50MB");
    if (!importPermit.tryAcquire()) throw new ReplayIssueImportBusyException();
    try {
        ReplayIssueExcelParser.ParsedWorkbook parsed = parser.parse(file);
        LocalDateTime importedAt = LocalDateTime.now(clock);
        dao.replaceAll(parsed.rows(), importedAt);
        return new ReplayIssueImportResult(parsed.rows().size(), parsed.rowsBySheet(),
                parsed.sandboxRows(), parsed.nonSandboxRows(), importedAt);
    } finally {
        importPermit.release();
    }
}
```

Keep the package-private constructor that accepts `Clock` and `Semaphore`; the Spring constructor uses `Clock.systemDefaultZone()` and `new Semaphore(1)`.

- [ ] **Step 4: Run service tests and verify GREEN**

Run: `mvn -Dtest=ReplayIssueImportServiceTest test`

Expected: import, replacement, size guard, parse-failure preservation, and busy rejection all pass.

- [ ] **Step 5: Commit Task 3 only**

```bash
git add src/main/java/com/axonlink/ai/replay/dto/ReplayIssueImportResult.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportBusyException.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java
git commit -m "feat(replay): coordinate atomic workbook imports"
```

---

### Task 4: Import, Page, Options, and Stats HTTP API

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Create: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `ReplayIssueImportService`, `ReplayIssueDao`, and `DaoIndexAnalysisProperties.BatchTrigger.token`.
- Produces: `/api/ai/parallel-replay/issues/import`, list root, `/options`, and `/stats`.

- [ ] **Step 1: Write failing standalone MockMvc integration tests**

Use real parser/service/H2 DAO and real `DaoIndexAnalysisProperties`; only HTTP is driven by MockMvc:

```java
@Test void validTokenImportsThenListReturnsRows() throws Exception {
    mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
            .file(ReplayIssueTestFixtures.validWorkbook(1)).header("X-DII-Trigger-Token", "secret"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.data.totalRows").value(8));

    mvc.perform(get("/api/ai/parallel-replay/issues")
            .param("limit", "50").param("offset", "0").param("sandbox", "true"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.data.total").value(4))
       .andExpect(jsonPath("$.data.items[0].is_sandbox").value(1));
}
```

Add tests for missing/wrong token=401, blank file=400, unsupported extension=400, parser validation=400, busy=409, DAO error=500 without exception leakage, options, stats, and `limit=999` clamping through the observable item count.

- [ ] **Step 2: Run controller tests and verify RED**

Run: `mvn -Dtest=ReplayIssueControllerTest test`

Expected: compilation fails because `ReplayIssueController` is absent.

- [ ] **Step 3: Implement the controller**

Return `ResponseEntity<R<ReplayIssueImportResult>>` for import error statuses and `R.ok(payload)` for reads. Validate extension before calling service, compare token using the established DII rule (empty configured token disables the check), and log the underlying exception only on the server.

```java
@GetMapping
public R<Map<String, Object>> list(
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(defaultValue="0") int offset,
        @RequestParam(required=false) String groupName,
        @RequestParam(required=false) Boolean sandbox,
        @RequestParam(required=false) String issueLevel,
        @RequestParam(required=false) String issueType,
        @RequestParam(required=false) String keyword) {
    ReplayIssueQuery query = new ReplayIssueQuery(limit, offset, groupName,
            sandbox, issueLevel, issueType, keyword);
    return R.ok(Map.of("total", dao.count(query), "items", dao.list(query)));
}
```

- [ ] **Step 4: Run controller and backend feature tests**

Run: `mvn -Dtest='ReplayIssue*Test' test`

Expected: parser, DAO, service, and HTTP tests all pass.

- [ ] **Step 5: Commit Task 4 only**

```bash
git add src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java \
  src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java
git commit -m "feat(replay): expose replay issue APIs"
```

---

### Task 5: Frontend Test Harness and Replay API Client

**Files:**
- Modify: `/Users/java/axon-link-frontend/package.json`
- Modify: `/Users/java/axon-link-frontend/package-lock.json`
- Create: `/Users/java/axon-link-frontend/vitest.config.js`
- Create: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Create: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`

**Interfaces:**
- Produces: `listReplayIssues(params)`, `getReplayIssueOptions()`, `getReplayIssueStats()`, and `importReplayIssues(file, token)`.

- [ ] **Step 1: Install the minimal existing-stack test tools**

Run: `npm install --save-dev vitest @vue/test-utils happy-dom`

Add scripts: `"test": "vitest run"` and `"test:watch": "vitest"`. Configure `environment: 'happy-dom'` and the existing Vue Vite plugin.

- [ ] **Step 2: Write failing API client tests**

Stub `fetch` at the network boundary and assert observable URL/body/error behavior. Define this literal response helper above the tests:

```javascript
const jsonResponse = (payload, status = 200) => new Response(JSON.stringify(payload), {
  status,
  headers: { 'Content-Type': 'application/json' },
})
```

```javascript
it('encodes list filters and paging', async () => {
  global.fetch = vi.fn().mockResolvedValue(jsonResponse({ code: 200, data: { total: 0, items: [] } }))
  await listReplayIssues({ limit: 50, offset: 100, groupName: '贷款组', sandbox: false, keyword: 'CCBS 响应' })
  expect(fetch.mock.calls[0][0]).toContain('groupName=%E8%B4%B7%E6%AC%BE%E7%BB%84')
  expect(fetch.mock.calls[0][0]).toContain('sandbox=false')
  expect(fetch.mock.calls[0][0]).toContain('keyword=CCBS%20%E5%93%8D%E5%BA%94')
})

it('sends multipart import without a JSON content type', async () => {
  global.fetch = vi.fn().mockResolvedValue(jsonResponse({ code: 200, data: { totalRows: 8 } }))
  await importReplayIssues(new File(['x'], 'issues.xlsx'), 'secret')
  const options = fetch.mock.calls[0][1]
  expect(options.body).toBeInstanceOf(FormData)
  expect(options.headers['X-DII-Trigger-Token']).toBe('secret')
})
```

- [ ] **Step 3: Run API tests and verify RED**

Run: `npm test -- src/api/replayIssues.spec.js`

Expected: module-not-found failure for `replayIssues.js`.

- [ ] **Step 4: Implement the API client**

Use the existing `request()` helper for GETs and direct `fetch('/api/ai/parallel-replay/issues/import', { method: 'POST', headers, body: formData })` for multipart upload. Parse JSON for all HTTP statuses and preserve the backend Chinese message; map 401 to `TOKEN_INVALID` and 409 to `IMPORT_BUSY`.

- [ ] **Step 5: Run API tests and verify GREEN**

Run: `npm test -- src/api/replayIssues.spec.js`

Expected: list, options, stats, successful import, token error, and busy error pass.

- [ ] **Step 6: Commit Task 5 only in the frontend repository**

```bash
git add package.json package-lock.json vitest.config.js src/api/replayIssues.js src/api/replayIssues.spec.js
git commit -m "test(replay): add frontend API test harness"
```

---

### Task 6: Fixed-Header Replay Issue Page

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: replay API client from Task 5.
- Produces: a standalone page component with import modal, filters, table viewport, summary, and pagination.

- [ ] **Step 1: Write the failing component behavior tests**

Mock only the HTTP client module; mount the real Vue component with complete response structures. Define this complete backend row literal before the tests:

```javascript
const fixtureRow = {
  id: 1, source_sheet: '贷款组', group_name: '贷款组', is_sandbox: 0, row_order: 2,
  domain: '贷款组', sequence_no: '59', batch_no: 'RPT20260803-194444-3815',
  transaction_code: '6208', transaction_name: '对公贷款还款计划查询', issue_level: '交易级',
  registered_date: '20260803', field_name: '响应码', issue_description: 'CCBS响应不一致',
  transaction_owner: '张济华', issue_type: '数据差异', initial_analysis: '核对返回值',
  final_solution: '修正映射', resolved_date: '', cooperation_group: '', resolver: '',
  serial_no: '001012213710102', data_repair_date: '', remark: '',
  affected_transaction_count: '58', issue_id: '000845', issue_key: 'TRAN|6208|响应码',
  historical_occurrence_count: '4', first_occurrence_date: '2026-07-28 00:00:00.0',
  last_occurrence_date: '2026-07-31 00:00:00.0', imported_at: '2026-08-04T10:00:00',
}
```

Then test the real component behavior:

```javascript
it('keeps server paging and all filters in the list request', async () => {
  listReplayIssues.mockResolvedValue({ total: 4607, items: [fixtureRow] })
  getReplayIssueOptions.mockResolvedValue({ groups: ['公共组'], issueLevels: ['交易级'], issueTypes: ['数据差异'] })
  getReplayIssueStats.mockResolvedValue({ total: 4607, groupCount: 4, sandboxCount: 1213, importedAt: '2026-08-04T10:00:00' })
  const wrapper = mount(ReplayIssuePage)
  await flushPromises()
  await wrapper.get('[data-testid="sandbox-filter"]').setValue('false')
  await wrapper.get('[data-testid="query-button"]').trigger('click')
  expect(listReplayIssues).toHaveBeenLastCalledWith(expect.objectContaining({ sandbox: false, limit: 50, offset: 0 }))
})

it('successful import reports counts and refreshes stats options and first page', async () => {
  importReplayIssues.mockResolvedValue({ totalRows: 16, sandboxRows: 8, nonSandboxRows: 8, rowsBySheet: {} })
  const wrapper = mount(ReplayIssuePage)
  await flushPromises()
  await wrapper.get('[data-testid="open-import"]').trigger('click')
  const input = wrapper.get('[data-testid="import-file"]')
  Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'issues.xlsx')] })
  await input.trigger('change')
  await wrapper.get('[data-testid="import-token"]').setValue('secret')
  await wrapper.get('[data-testid="submit-import"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('导入完成：16 条')
  expect(getReplayIssueStats).toHaveBeenCalledTimes(2)
  expect(getReplayIssueOptions).toHaveBeenCalledTimes(2)
  expect(listReplayIssues).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 0 }))
})
```

Also test previous/next boundaries, page-size reset to page 1, failed import message, double-click disabled while importing, and snake_case row rendering for all 26 visible columns.

- [ ] **Step 2: Run the component test and verify RED**

Run: `npm test -- src/components/replay/ReplayIssuePage.spec.js`

Expected: module-not-found failure for `ReplayIssuePage.vue`.

- [ ] **Step 3: Implement the page component**

Use a three-region flex layout so only `.replay-table-viewport` scrolls:

```css
.replay-page { min-height: 0; height: 100%; display: flex; flex-direction: column; }
.replay-toolbar, .replay-filters, .replay-pager { flex: 0 0 auto; }
.replay-table-viewport { min-height: 0; flex: 1 1 auto; overflow: auto; }
.replay-table { min-width: 3000px; table-layout: fixed; border-collapse: separate; border-spacing: 0; }
.replay-table thead th { position: sticky; top: 0; z-index: 2; }
```

Keep cards limited to the three summary metrics, use existing CSS variables for both themes, use Lucide `Upload`, `Search`, `RotateCcw`, and `ChevronLeft/Right` icons, and add `title` tooltips to icon-only controls. Render the Excel-like teal header and subtle blue/white alternating rows without copying screenshot watermarks or browser chrome.

- [ ] **Step 4: Run the page tests and build**

Run: `npm test -- src/components/replay/ReplayIssuePage.spec.js`

Expected: all page interactions pass.

Run: `npm run build`

Expected: Vite build succeeds with no template/compiler errors.

- [ ] **Step 5: Commit Task 6 only in the frontend repository**

```bash
git add src/components/replay/ReplayIssuePage.vue src/components/replay/ReplayIssuePage.spec.js
git commit -m "feat(replay): add replay issue list page"
```

---

### Task 7: First-Level Menu and Workspace Integration

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/ChainImpactSidebar.vue`
- Create: `/Users/java/axon-link-frontend/src/components/ChainImpactSidebar.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/views/TransactionAnalysis.vue`

**Interfaces:**
- Produces: sidebar event `selectReplayPage('replay-issues')` and `currentPage === 'replay-issues'` page rendering.

- [ ] **Step 1: Write a failing real-sidebar interaction test**

Mount `ChainImpactSidebar` with complete required props, expand “并行回放”, click “回放问题清单”, and assert the emitted business event:

```javascript
it('emits replay page selection from a SQL-peer first-level menu', async () => {
  const wrapper = mount(ChainImpactSidebar, { props: {
    domains: [],
    activeDomainId: '',
    systemStats: { status: 'normal', statusText: '系统运行正常' },
    totalTransactions: 0,
    impactStats: {},
    currentPage: 'chain',
    impactMode: 'table',
  } })
  await wrapper.get('[data-testid="replay-section-toggle"]').trigger('click')
  await wrapper.get('[data-testid="replay-issues-menu"]').trigger('click')
  expect(wrapper.emitted('selectReplayPage')).toEqual([['replay-issues']])
})
```

- [ ] **Step 2: Run the sidebar test and verify RED**

Run: `npm test -- src/components/ChainImpactSidebar.spec.js`

Expected: replay selectors/event are absent.

- [ ] **Step 3: Implement navigation and parent page switching**

In `ChainImpactSidebar.vue`, add a separate first-level section between SQL 巡检 and 代码提交, its own open state, replay active predicate, Lucide `PlaySquare` icon, and one child entry. Add `selectReplayPage` to `defineEmits`.

In `TransactionAnalysis.vue`:

```vue
<ChainImpactSidebar @select-replay-page="onSelectReplayPage" />
<ReplayIssuePage v-show="currentPage === 'replay-issues'" class="impact-main" />
```

Import the component and implement `onSelectReplayPage = pageKey => { currentPage.value = pageKey }`. Keep hash startup support unchanged so `#replay-issues` opens the page directly.

- [ ] **Step 4: Run sidebar/page tests and build**

Run: `npm test`

Expected: all API, page, and sidebar tests pass.

Run: `npm run build`

Expected: frontend compiles and writes the new static bundle to backend `src/main/resources/static`.

- [ ] **Step 5: Commit Task 7 only in the frontend repository**

```bash
git add src/components/ChainImpactSidebar.vue src/components/ChainImpactSidebar.spec.js src/views/TransactionAnalysis.vue
git commit -m "feat(replay): add parallel replay navigation"
```

---

### Task 8: Full Verification, Browser Checks, and Knowledge Log

**Files:**
- Modify: `/Users/java/obsidian/log.md`
- Verify only: backend/frontend source and generated backend static assets.

**Interfaces:**
- Consumes: complete backend and frontend feature.
- Produces: fresh automated evidence and visual evidence for completion.

- [ ] **Step 1: Run all feature-specific backend tests**

Run: `mvn -Dtest='ReplayIssue*Test' test`

Expected: all replay parser, DAO, service, and controller tests pass with 0 failures/errors.

- [ ] **Step 2: Run the full backend test suite**

Run: `mvn test`

Expected: no new failures. If the two pre-existing dirty-main `ErrorCodeScanServiceTest` expectations still fail, record their exact names separately and confirm all replay tests pass; do not change that unrelated behavior in this feature.

- [ ] **Step 3: Run all frontend tests and production build**

Run: `npm test`

Expected: all Vitest suites pass.

Run: `npm run build`

Expected: Vite exits 0 and backend `src/main/resources/static/index.html` references existing hashed assets.

- [ ] **Step 4: Start the frontend development server and verify with a real browser**

Run: `npm run dev -- --host 127.0.0.1`

Use Playwright route interception for the four replay endpoints and verify at 1440x900 and 390x844:

- “并行回放” is a first-level peer of “SQL 巡检”.
- “回放问题清单” opens and loads fixture data.
- Header remains stationary while the table viewport scrolls vertically.
- Pagination remains visible while the table scrolls.
- Horizontal scrolling reaches `上次出现日期` without resizing the page shell.
- Import modal accepts `.xlsx/.xls`, disables during import, and shows returned counts.
- Light and dark themes have readable text and no overlap.

Capture screenshots and inspect them; check canvas/pixels only if the page unexpectedly renders blank.

- [ ] **Step 5: Update the Obsidian implementation log**

Append exactly one line without modifying prior entries:

```text
2026-08-04 [IMPL] axon-link-server 并行回放问题清单落地 | 更新后端、前端、测试及 3 工程设计页 | 八个业务/沙箱页签事务化全量导入，新增并行回放一级菜单、固定表头宽表、服务端分页和多条件筛选；记录实际自动化与浏览器验证结果
```

Replace the final clause after the semicolon with the actual test counts and any known unrelated failures before saving.

- [ ] **Step 6: Review diffs and commit only owned documentation change**

Inspect backend/frontend/Obsidian status. Confirm no unrelated user change is staged. Because `log.md` already contains user-owned uncommitted changes, do not stage or commit it unless an exact index-only patch can isolate the single appended line; leaving the append uncommitted is acceptable and safer.

- [ ] **Step 7: Report completion evidence**

Report backend feature test count, full-suite status, frontend test count, build result, browser viewports, static asset result, migration path, and any residual deployment step (manual execution of V33 on the result MySQL database).
