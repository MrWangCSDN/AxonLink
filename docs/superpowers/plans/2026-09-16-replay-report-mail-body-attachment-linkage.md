# Replay Report Mail Body Attachment Linkage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate the editable daily/weekly report email body from the current and additionally selected generated report attachments, and refresh it whenever those system attachments change.

**Architecture:** A backend preview service resolves the same report snapshot references as mail attachment delivery, extracts coverage totals from the workbook plus collected volume from the persisted summary view, aggregates by `RPT`/`DZ`, and composes the daily or weekly text. The frontend calls one preview endpoint when the modal opens and whenever generated attachments change; local Excel attachments never participate, and the send endpoint continues to use the final editable body supplied by the page.

**Tech Stack:** Java 17, Spring Boot MVC, Apache POI, JUnit 5/Mockito, Vue 3, Vitest.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-邮件正文附件联动-系统设计.md`

## Global Constraints

- Treat `RPT` as 查询 and `DZ` as 账务.
- Count the current generated report and every additionally selected generated report exactly once by complete business key.
- Local `.xls/.xlsx` uploads do not affect the body and do not trigger regeneration.
- Attachment selection changes overwrite earlier manual body edits; sending does not regenerate again.
- Prefer the `按大组汇总` total, fall back to `按业务领域汇总`, and reject snapshots when both totals exist but differ.
- Query text precedes accounting text when both families exist.
- Preserve existing newline normalization and HTML summary-table rendering.
- Do not commit repository changes unless the user explicitly requests a commit.

---

### Task 1: Extract and compose report body metrics

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailBodyMetricExtractor.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailBodyComposer.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailBodyMetricExtractorTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailBodyComposerTest.java`

**Interfaces:**
- Consumes: `byte[] workbookContent`, `ReplayReportSummaryView summary`, `ReplayReportPeriod mailPeriod`.
- Produces: `ReplayReportMailBodyMetricExtractor.Metrics(long expectedTransactions, long actualTransactions, long collectedTransactions)` and `ReplayReportMailBodyComposer.compose(ReplayReportPeriod, List<FamilyMetrics>)`.

- [ ] **Step 1: Write failing extractor tests**

Create workbook fixtures with `ReplayDailyReportWorkbookWriter`-compatible section titles and assert:

```java
@Test
void prefersGroupCoverageTotalAndUsesSummaryCollectedTotal() {
    byte[] workbook = workbookWithCoverageTotals(120, 100, 120, 100);
    ReplayReportSummaryView summary = summary("RPT", 900L);

    Metrics metrics = extractor.extract(workbook, summary, "DAILY||RPT20260916-01");

    assertThat(metrics).isEqualTo(new Metrics(120, 100, 900));
}

@Test
void fallsBackToDomainCoverageTotalWhenGroupTotalIsMissing() {
    byte[] workbook = workbookWithDomainCoverageTotal(88, 80);
    assertThat(extractor.extract(workbook, summary("DZ", 700L), "DAILY||DZ20260916-01"))
            .isEqualTo(new Metrics(88, 80, 700));
}

@Test
void rejectsDifferentDomainAndGroupTotals() {
    byte[] workbook = workbookWithCoverageTotals(120, 100, 121, 100);
    assertThatThrownBy(() -> extractor.extract(workbook, summary("RPT", 900L), "report"))
            .isInstanceOf(BodyMetricUnavailableException.class)
            .hasMessageContaining("覆盖汇总合计不一致");
}
```

- [ ] **Step 2: Run extractor tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Dtest=ReplayReportMailBodyMetricExtractorTest test
```

Expected: compilation failure because `ReplayReportMailBodyMetricExtractor` does not exist.

- [ ] **Step 3: Implement the minimal extractor**

Use `WorkbookFactory.create(new ByteArrayInputStream(content))`, find `ReplayDailyWorkbookParser.COVERAGE_SHEET`, scan section-title rows, locate the `合计` row in each section, and read columns `1` and `2`. Read collected volume from `summary.totalRow().values().get("sentTransactionCount")`. Reject missing sheets, totals, non-numeric values, negative values, and mismatched dual totals with `BodyMetricUnavailableException(reportKey, reason)`.

- [ ] **Step 4: Write failing composer tests**

```java
@Test
void composesDailyQueryAndAccountingInFixedOrder() {
    String body = composer.compose(ReplayReportPeriod.DAILY, List.of(
            family("DZ", 20, 18, 200), family("RPT", 10, 9, 100)));
    assertThat(body).isEqualTo("""
            各位领导、老师：
            本轮回放查询交易应发交易 10，实发交易 9，本轮回放采集交易量 100，实发交易量 100；
            本轮回放账务交易应发交易 20，实发交易 18，本轮回放采集交易量 200，实发交易量 200。""");
}

@Test
void composesWeeklyAccountingOnly() {
    String body = composer.compose(ReplayReportPeriod.WEEKLY, List.of(family("DZ", 20, 18, 200)));
    assertThat(body).isEqualTo("""
            各位领导、老师：
            本周回放比对主要内容如下，请查阅，谢谢。
            本轮回放账务交易应发交易 20，实发交易 18，本轮回放采集交易量 200，实发交易量 200。""");
}
```

- [ ] **Step 5: Run composer tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Dtest=ReplayReportMailBodyComposerTest test
```

Expected: compilation failure because `ReplayReportMailBodyComposer` does not exist.

- [ ] **Step 6: Implement aggregation-safe composition and verify GREEN**

Implement fixed query/accounting ordering and punctuation. Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Dtest=ReplayReportMailBodyMetricExtractorTest,ReplayReportMailBodyComposerTest test
```

Expected: PASS.

---

### Task 2: Resolve selected snapshots and expose body preview API

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportMailBodyPreviewRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportMailBodyPreview.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailBodyService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailBodyServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Request: `ReplayReportMailBodyPreviewRequest(ReplayGeneratedReportRef currentReport, List<ReplayGeneratedReportRef> generatedReports)`.
- Response: `ReplayReportMailBodyPreview(String body)`.
- Endpoint: `POST /api/ai/parallel-replay/issues/report-mail/body-preview`.
- Loader: `ReplayReportMailAttachmentService.resolveSystemReports(ReplayGeneratedReportRef current, List<ReplayGeneratedReportRef> generated)` returns deduplicated, query-first `List<ResolvedSystemReport>`.

- [ ] **Step 1: Write failing snapshot-resolution tests**

Add tests proving that `resolveSystemReports` loads daily and weekly snapshots, removes a duplicate current reference, and sorts `RPT` before `DZ` without accepting local files.

```java
List<ResolvedSystemReport> resolved = service.resolveSystemReports(current, List.of(current, accountingWeekly));
assertThat(resolved).extracting(item -> item.summary().family()).containsExactly("RPT", "DZ");
```

- [ ] **Step 2: Run attachment service test and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Dtest=ReplayReportMailAttachmentServiceTest test
```

Expected: compilation failure because `resolveSystemReports` is absent.

- [ ] **Step 3: Extract the shared system-report resolver**

Make `ResolvedSystemReport` a public nested record, move existing generated-report deduplication/loading/sorting into `resolveSystemReports`, and let the existing `resolve(...)` method reuse it while still preserving the already-loaded current attachment path used during sending.

- [ ] **Step 4: Write failing preview service tests**

```java
@Test
void aggregatesMultipleReportsByFamilyAndUsesCurrentPeriodAsTemplate() {
    when(attachmentService.resolveSystemReports(current, selected)).thenReturn(List.of(
            report("RPT", 10, 9, 100), report("RPT", 20, 18, 200), report("DZ", 30, 27, 300)));
    ReplayReportMailBodyPreview preview = service.preview(new ReplayReportMailBodyPreviewRequest(current, selected));
    assertThat(preview.body()).contains("查询交易应发交易 30").contains("账务交易应发交易 30");
}
```

Also cover null request/current report, unsupported batch family, empty resolved reports, and metric extraction failure propagation.

- [ ] **Step 5: Run preview service tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Dtest=ReplayReportMailBodyServiceTest test
```

Expected: compilation failure because the preview service and DTOs are absent.

- [ ] **Step 6: Implement preview service and endpoint**

Aggregate with `Math.addExact` to detect overflow. Map malformed requests to 400, missing snapshots to 404, and unavailable summary/body metrics to 409. Return `R.ok(new ReplayReportMailBodyPreview(body))`.

- [ ] **Step 7: Add and run controller contract tests**

Add MockMvc coverage for successful daily and weekly requests and 400/404/409 responses. Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Dtest=ReplayReportMailBodyServiceTest,ReplayReportMailAttachmentServiceTest,ReplayIssueControllerTest test
```

Expected: PASS.

---

### Task 3: Add the frontend preview API client

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`

**Interfaces:**
- Produces: `previewReplayReportMailBody(currentReport, generatedReports = [])`.
- Calls: `POST /ai/parallel-replay/issues/report-mail/body-preview` with JSON `{ currentReport, generatedReports }`.

- [ ] **Step 1: Write the failing API test**

```javascript
it('previews report mail body from current and selected generated reports', async () => {
  await previewReplayReportMailBody(currentReport, [accountingReport])
  expect(fetch.mock.calls[0][0]).toBe('/api/ai/parallel-replay/issues/report-mail/body-preview')
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({
    currentReport,
    generatedReports: [accountingReport],
  })
})
```

- [ ] **Step 2: Run the API test and verify RED**

Run:

```bash
npm test -- --run src/api/replayIssues.spec.js
```

Expected: FAIL because `previewReplayReportMailBody` is not exported.

- [ ] **Step 3: Implement the API helper and verify GREEN**

Use the existing `request` JSON behavior and no trigger-token header. Re-run the command from Step 2 and expect PASS.

---

### Task 4: Refresh editable body on generated attachment changes

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `previewReplayReportMailBody` from Task 3 and the existing `currentAttachment`/`reportMailSelectedReports` values.
- Produces: `refreshReplayReportMailBody(selectedReports)` and `onReportMailSelectedReportsUpdate(selectedReports)`.

- [ ] **Step 1: Write failing component tests**

Cover these behaviors with mocked API responses:

```javascript
it('shows the generated body when the mail modal opens', async () => {
  previewReplayReportMailBody.mockResolvedValue({ body: '自动查询正文' })
  await openDailyMail()
  expect(wrapper.get('[data-testid="daily-report-mail-body"]').element.value).toBe('自动查询正文')
})

it('overwrites a manual body after generated attachments change', async () => {
  await editBody('用户修改正文')
  previewReplayReportMailBody.mockResolvedValue({ body: '查询和账务自动正文' })
  await selectGeneratedAccountingReport()
  expect(mailBody()).toBe('查询和账务自动正文')
})

it('does not refresh the body when local files change', async () => {
  await editBody('保留用户正文')
  await uploadLocalExcel()
  expect(previewReplayReportMailBody).toHaveBeenCalledTimes(1)
  expect(mailBody()).toBe('保留用户正文')
})
```

Also assert that preview failure keeps the last body, displays an error, and disables sending until a later preview succeeds.

- [ ] **Step 2: Run component tests and verify RED**

Run:

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: FAIL because the page still displays `mail-config.body` and directly assigns selected reports.

- [ ] **Step 3: Implement modal/open refresh and attachment-change refresh**

Replace `@update:selected-reports="reportMailSelectedReports = $event"` with the explicit handler. Build normalized refs from `dailyReportMailCurrentAttachment` plus selected report options. Use an incrementing request sequence so stale preview responses cannot overwrite a newer selection. Keep the previous body on failure, expose a concise error, and include preview loading/error in the existing send-disable guard.

- [ ] **Step 4: Verify component GREEN**

Run:

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/components/replay/ReplayReportMailAttachments.spec.js
```

Expected: PASS.

---

### Task 5: Focused and full regression verification

**Files:**
- Verify only; no new production files expected.

**Interfaces:**
- Confirms all prior task contracts together.

- [ ] **Step 1: Run focused backend tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Dtest=ReplayReportMailBodyMetricExtractorTest,ReplayReportMailBodyComposerTest,ReplayReportMailBodyServiceTest,ReplayReportMailAttachmentServiceTest,ReplayDailyReportMailServiceTest,ReplayWeeklyReportMailServiceTest,ReplayIssueControllerTest test
```

Expected: PASS.

- [ ] **Step 2: Run full backend tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Expected: PASS with only existing documented skips.

- [ ] **Step 3: Run frontend tests and production build**

```bash
cd /Users/java/axon-link-frontend
npm test -- --run
npm run build
```

Expected: all tests PASS and Vite production build completes.

- [ ] **Step 4: Check whitespace and unintended files**

```bash
cd /Users/java/axon-link-server && git diff --check
cd /Users/java/axon-link-frontend && git diff --check
```

Expected: no output.

- [ ] **Step 5: Manually verify the critical UI flow**

Open a query report mail modal, confirm query-only text, add an accounting generated report and confirm query+accounting text, edit the body, add/remove a generated report and confirm automatic reset, then add a local Excel and confirm the body remains unchanged.
