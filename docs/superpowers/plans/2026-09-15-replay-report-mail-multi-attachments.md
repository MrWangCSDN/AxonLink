# Replay Report Mail Multi-Attachments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend daily and weekly replay-report email dialogs so the current report remains mandatory while users can add multiple generated RPT/DZ daily reports and multiple local Excel files.

**Architecture:** Replace the two JSON mail-send requests with one-shot `multipart/form-data` requests. A shared replay attachment service validates and resolves trusted database snapshots plus request-scoped local Excel files, while the generic mail service sends an ordered attachment list and the existing status tables retain only the latest attachment metadata manifest.

**Tech Stack:** Java 17, Spring Boot MVC/JDBC/Mail, Apache POI `FileMagic`, MySQL-compatible migrations with H2 tests, Vue 3, Fetch `FormData`, Vitest, Vue Test Utils.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section “日报与周报邮件多附件（2026-09-15）”; `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md` section “邮件多附件模型（2026-09-15）”; `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md` section “日报与周报邮件多附件接口（2026-09-15）”.

## Global Constraints

- The current daily or weekly report is always attachment 1 and cannot be removed or replaced.
- Additional generated reports are daily-report snapshots only and may mix `RPT` and `DZ` families.
- Local files must have `.xls` or `.xlsx` names and valid OLE2/OOXML file signatures.
- A local file may not exceed 20 MiB; all current, generated, and local attachments together may not exceed 50 MiB.
- Local file content is request-scoped and must never be copied into a business persistence directory or database column.
- Existing application-wide multipart limits remain 300MB because other import workflows require them; replay mail applies stricter service-level limits.
- Attachment order is current report, selected generated daily reports in first-selection order, then local files in browser order.
- The latest mail status keeps attachment metadata only; no attachment content, token, draft, or send history is persisted.
- Do not commit automatically. The working trees already contain unrelated user changes; end each task with a focused diff and test checkpoint.

---

### Task 1: Persist the Latest Attachment Manifest

**Files:**
- Create: `src/main/resources/db/daoindex/V67__replay_report_mail_attachment_manifest.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayMailAttachmentSource.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayMailAttachmentMetadata.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailStatus.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportMailStatus.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyReportMailDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportMailDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyReportMailDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportMailDaoTest.java`

**Interfaces:**
- Produces: `ReplayMailAttachmentSource { CURRENT_REPORT, GENERATED_DAILY, LOCAL_EXCEL }`.
- Produces: `ReplayMailAttachmentMetadata(String fileName, long size, ReplayMailAttachmentSource source, String batchNo)`.
- Produces: `markSending(..., List<ReplayMailAttachmentMetadata> attachments)` on both mail DAOs.
- Produces: `attachments()` on both mail status records for later view projection.

- [ ] **Step 1: Write failing DAO round-trip tests**

Add tests that call `markSending` with all three sources and assert exact JSON round-trip order:

```java
List<ReplayMailAttachmentMetadata> attachments = List.of(
        new ReplayMailAttachmentMetadata("RPT20260915日报.xlsx", 6470, CURRENT_REPORT, "RPT20260915-01"),
        new ReplayMailAttachmentMetadata("DZ20260914日报.xlsx", 7120, GENERATED_DAILY, "DZ20260914-01"),
        new ReplayMailAttachmentMetadata("补充说明.xlsx", 4096, LOCAL_EXCEL, null));

dao.markSending(batchNo, "标题", "正文", "sender@example.com", to, cc, attachments);
assertEquals(attachments, dao.find(batchNo).orElseThrow().attachments());
```

Add the equivalent weekly `(startBatchNo, endBatchNo)` test and assert that a second `markSending` replaces the prior manifest rather than appending history.

- [ ] **Step 2: Run persistence tests and verify they fail**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayDailyReportMailDaoTest,ReplayWeeklyReportMailDaoTest test
```

Expected: compilation fails because attachment metadata and the extended DAO signatures do not exist.

- [ ] **Step 3: Add the migration and metadata records**

Use a three-step migration compatible with database versions that reject defaults on TEXT types:

```sql
ALTER TABLE dii_replay_daily_report_mail
    ADD COLUMN attachment_manifest MEDIUMTEXT NULL AFTER cc_emails;
UPDATE dii_replay_daily_report_mail
   SET attachment_manifest = '[]'
 WHERE attachment_manifest IS NULL OR attachment_manifest = '';
ALTER TABLE dii_replay_daily_report_mail
    MODIFY COLUMN attachment_manifest MEDIUMTEXT NOT NULL;

ALTER TABLE dii_replay_weekly_report_mail
    ADD COLUMN attachment_manifest MEDIUMTEXT NULL AFTER cc_emails;
UPDATE dii_replay_weekly_report_mail
   SET attachment_manifest = '[]'
 WHERE attachment_manifest IS NULL OR attachment_manifest = '';
ALTER TABLE dii_replay_weekly_report_mail
    MODIFY COLUMN attachment_manifest MEDIUMTEXT NOT NULL;
```

Define the records exactly:

```java
public enum ReplayMailAttachmentSource {
    CURRENT_REPORT, GENERATED_DAILY, LOCAL_EXCEL
}

public record ReplayMailAttachmentMetadata(
        String fileName,
        long size,
        ReplayMailAttachmentSource source,
        String batchNo) {
}
```

- [ ] **Step 4: Extend both DAOs**

Serialize manifests with the existing Jackson style used for email arrays. Read blank/null values as `List.of()`, preserve list order, and fail with a domain-specific `IllegalStateException` when stored JSON is malformed. Include `attachment_manifest` in both `SELECT` projections and both upsert statements.

- [ ] **Step 5: Run DAO tests and inspect the focused diff**

Run the command from Step 2 and expect all selected tests to pass. Then run:

```bash
git diff --check -- \
  src/main/resources/db/daoindex/V67__replay_report_mail_attachment_manifest.sql \
  src/main/java/com/axonlink/ai/replay/dto/ReplayMailAttachmentSource.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayMailAttachmentMetadata.java \
  src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyReportMailDao.java \
  src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportMailDao.java
```

---

### Task 2: Query Generated Daily-Report Attachment Options

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportAttachmentOption.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportAttachmentOptionPage.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `ReplayReportAttachmentOption(String batchNo, String family, String fileName, long fileSize, LocalDateTime generatedAt)`.
- Produces: `ReplayReportAttachmentOptionPage(List<ReplayReportAttachmentOption> items, int page, int size, long total)`.
- Produces: `ReplayDailyDataDao.searchReportAttachmentOptions(String keyword, String family, int page, int size)`.
- Produces: `ReplayDailyDataDao.findReportSnapshots(List<String> batchNos)` returning a batch-number keyed `LinkedHashMap` in requested order.
- Produces: `ReplayIssueDailyReportService.searchAttachmentOptions(String keyword, String family, int page, int size)` as the controller-facing boundary.
- Produces: `GET /api/ai/parallel-replay/issues/daily-report/attachment-options`.

- [ ] **Step 1: Write failing DAO search tests**

Insert generated snapshots for one `RPT` and two `DZ` batches plus daily source data without a snapshot. Assert:

```java
var page = dao.searchReportAttachmentOptions("20260915", "ALL", 0, 20);
assertEquals(List.of("RPT20260915-02", "DZ20260915-01"),
        page.items().stream().map(ReplayReportAttachmentOption::batchNo).toList());
assertEquals(2, page.total());
```

Add separate assertions for `family=RPT`, case-insensitive filename keyword matching, generated-time descending order, and page size validation clamped to `1..100`.

- [ ] **Step 2: Run DAO tests and verify failure**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayDailyDataDaoTest test
```

Expected: compilation fails because the option page API is missing.

- [ ] **Step 3: Implement the paged snapshot query**

Query only `dii_replay_daily_report_snapshot`; require non-null content and positive `file_size`. Normalize family to `ALL|RPT|DZ`, reject any other value with `IllegalArgumentException("日报类型错误")`, and use a separate count query with the same filters. Order by `generated_at DESC, batch_no DESC`. Add one `IN (...)` snapshot query for `findReportSnapshots`, deduplicate input batch numbers before SQL construction, and restore the caller's requested order in a `LinkedHashMap` so Task 3 never performs one query per attachment. Expose the paged search through `ReplayIssueDailyReportService`; the controller must not depend directly on the DAO.

- [ ] **Step 4: Write and implement controller tests**

Mock the DAO/service result and assert the exact endpoint parameters and `R.ok(page)` response. Add a `400` test for invalid family. The controller method signature is:

```java
@GetMapping("/daily-report/attachment-options")
public ResponseEntity<R<ReplayReportAttachmentOptionPage>> reportAttachmentOptions(
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "ALL") String family,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size)
```

- [ ] **Step 5: Run focused tests and diff check**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayDailyDataDaoTest,ReplayIssueControllerTest test
git diff --check -- src/main/java/com/axonlink/ai/replay src/test/java/com/axonlink/ai/replay
```

---

### Task 3: Build and Validate Ordered Attachments

**Files:**
- Create: `src/main/java/com/axonlink/notification/service/MailAttachment.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentService.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentServiceTest.java`
- Modify: `src/main/java/com/axonlink/notification/service/MailService.java`
- Create: `src/test/java/com/axonlink/notification/service/MailServiceTest.java`

**Interfaces:**
- Produces: `MailAttachment(String fileName, byte[] content, String contentType)`.
- Produces: `ResolvedAttachments(List<MailAttachment> mailAttachments, List<ReplayMailAttachmentMetadata> metadata, long totalSize)`.
- Produces: `ReplayReportMailAttachmentService.resolve(MailAttachment current, ReplayMailAttachmentMetadata currentMetadata, List<String> reportBatchNos, List<MultipartFile> localFiles, String currentDailyBatchNo)`.
- Produces: `MailService.sendTextWithAttachmentsSync(List<String> to, List<String> cc, String subject, String body, List<MailAttachment> attachments)`.
- Produces nested exceptions `InvalidAttachmentException(fileName, reason)`, `AttachmentTooLargeException(fileName)`, `AttachmentTotalSizeException()`, and `MissingReportSnapshotsException(List<String> batchNos)` for deterministic controller mapping.

- [ ] **Step 1: Write failing resolver tests**

Cover these exact cases:

```java
assertEquals(List.of("current.xlsx", "rpt.xlsx", "dz.xlsx", "local-a.xlsx", "local-b.xls"),
        result.mailAttachments().stream().map(MailAttachment::fileName).toList());
```

- Duplicate generated batch IDs preserve first selection only.
- A daily current batch repeated in `reportBatchNos` is excluded.
- Missing generated snapshots produce one exception containing every missing batch number.
- `.txt`, empty files, false `.xlsx` content, path-like names, and files larger than 20 MiB are rejected before mail sending.
- Current plus generated plus local content larger than 50 MiB raises `AttachmentTotalSizeException`.
- Valid OOXML begins with ZIP magic; valid legacy XLS begins with OLE2 magic. Use POI `FileMagic.valueOf` rather than trusting browser MIME.

- [ ] **Step 2: Run the resolver test and verify failure**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayReportMailAttachmentServiceTest test
```

Expected: compilation fails because the resolver does not exist.

- [ ] **Step 3: Implement the shared resolver**

Use constants:

```java
static final long MAX_LOCAL_FILE_SIZE = 20L * 1024 * 1024;
static final long MAX_TOTAL_SIZE = 50L * 1024 * 1024;
```

Batch-load generated snapshots in the requested order without one query per batch. Read local `MultipartFile` bytes only after checking reported size, validate safe basename with `StringUtils.cleanPath`, detect `FileMagic.OLE2` or `FileMagic.OOXML`, and emit content types `application/vnd.ms-excel` or `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` from detected format.

- [ ] **Step 4: Write failing MIME assembly test**

Use a mocked `JavaMailSender` and a real `MimeMessage`. Call `sendTextWithAttachmentsSync` with three attachments, then assert attachment disposition, UTF-8 names, content types, and order from the multipart body.

- [ ] **Step 5: Implement multi-attachment mail sending**

Create `MimeMessageHelper(message, true, UTF_8)` and loop over the supplied list:

```java
for (MailAttachment attachment : attachments) {
    helper.addAttachment(
            attachment.fileName(),
            new ByteArrayResource(attachment.content()),
            attachment.contentType());
}
```

Keep the old single-attachment method as a delegating compatibility wrapper until Tasks 4 and 5 migrate all callers.

- [ ] **Step 6: Run shared service tests and diff check**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayReportMailAttachmentServiceTest,MailServiceTest test
git diff --check -- src/main/java/com/axonlink/notification src/main/java/com/axonlink/ai/replay/service
```

---

### Task 4: Migrate Daily-Report Mail to Multipart

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailSendRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailView.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportMailService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportMailServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `ReplayReportMailAttachmentService.resolve(...)` and `MailService.sendTextWithAttachmentsSync(...)` from Task 3.
- Produces: `ReplayDailyReportMailSendRequest(..., String body, List<String> reportBatchNos)`.
- Produces: `ReplayDailyReportMailView(..., ReplayMailAttachmentMetadata currentAttachment, List<ReplayMailAttachmentMetadata> attachments)`.
- Produces: multipart `POST /daily-report/mail-send` with `@RequestPart("mail")` and optional `@RequestPart("files")`.

- [ ] **Step 1: Write failing daily service tests**

Mock current snapshot, one RPT snapshot, one DZ snapshot, and two local `MockMultipartFile`s. Assert the service:

```java
verify(mailService).sendTextWithAttachmentsSync(
        eq(to), eq(cc), eq(subject), eq(body),
        argThat(items -> items.stream().map(MailAttachment::fileName).toList().equals(
                List.of("current.xlsx", "rpt.xlsx", "dz.xlsx", "local.xlsx"))));
verify(mailDao).markSending(eq(batchNo), eq(subject), eq(body), eq(sender), eq(to), eq(cc),
        argThat(metadata -> metadata.size() == 4));
```

Assert configuration returns current attachment metadata and send failure persists `FAILED` while preserving the attempted manifest.

- [ ] **Step 2: Run daily service tests and verify failure**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayDailyReportMailServiceTest test
```

- [ ] **Step 3: Implement the daily service changes**

Change the send signature to:

```java
public ReplayDailyReportMailView send(
        ReplayDailyReportMailSendRequest request,
        List<MultipartFile> files)
```

Preserve existing title, email, body, token-controller, and status semantics. Build current attachment metadata from the trusted snapshot, resolve all attachments before `markSending`, then send exactly once.

- [ ] **Step 4: Write and implement multipart controller tests**

Use MockMvc multipart requests with a JSON `MockMultipartFile("mail", "", "application/json", ...)` and repeated `files` parts. Assert wrong token returns `401` before the service, invalid attachment returns `400`, size exceptions return `413`, missing generated snapshots return `404`, and SMTP errors remain `502`.

Controller signature:

```java
@PostMapping(value = "/daily-report/mail-send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<R<ReplayDailyReportMailView>> sendDailyReportMail(
        @RequestPart(value = "mail", required = false) ReplayDailyReportMailSendRequest body,
        @RequestPart(value = "files", required = false) List<MultipartFile> files,
        @RequestHeader(value = "X-DII-Trigger-Token", required = false) String token,
        HttpServletRequest request)
```

- [ ] **Step 5: Run daily tests and inspect the diff**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayDailyReportMailServiceTest,ReplayIssueControllerTest test
git diff --check -- src/main/java/com/axonlink/ai/replay src/test/java/com/axonlink/ai/replay
```

---

### Task 5: Migrate Weekly-Report Mail to Multipart

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportMailSendRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportMailView.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: Task 1 manifest persistence and Task 3 resolver/mail sender.
- Produces: `ReplayWeeklyReportMailSendRequest(..., String body, List<String> reportBatchNos)`.
- Produces: multipart `POST /weekly-report/mail-send` using the same `mail` and `files` part names as daily mail.

- [ ] **Step 1: Write failing weekly service tests**

Assert a fixed weekly snapshot remains first, generated RPT/DZ daily snapshots follow, and local Excel files remain last. Verify `currentAttachment.batchNo()` equals the weekly end batch while its source is `CURRENT_REPORT`. Verify missing daily snapshots and total-size failures do not call `mailDao.markSending` or `mailService`.

- [ ] **Step 2: Run weekly tests and verify failure**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayWeeklyReportMailServiceTest test
```

- [ ] **Step 3: Implement weekly service and controller migration**

Use the same optional `List<MultipartFile> files` signature and exception mapping as Task 4. Do not apply daily-current-batch deduplication to the fixed weekly attachment; selected daily reports remain valid even when their batch equals `endBatchNo`.

- [ ] **Step 4: Add weekly multipart controller tests**

Send one RPT selection, one DZ selection, and two local file parts. Assert the deserialized request preserves report selection order and the service receives all local parts. Repeat the daily `400/401/404/413/502` mapping assertions for the weekly endpoint.

- [ ] **Step 5: Run backend mail tests**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Dtest=ReplayDailyReportMailDaoTest,ReplayWeeklyReportMailDaoTest,ReplayDailyDataDaoTest,ReplayReportMailAttachmentServiceTest,MailServiceTest,ReplayDailyReportMailServiceTest,ReplayWeeklyReportMailServiceTest,ReplayIssueControllerTest test
```

Expected: all selected tests pass with zero failures and zero errors.

---

### Task 6: Send Multipart Requests from the Frontend API

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`

**Interfaces:**
- Produces: `getReplayReportAttachmentOptions({ keyword, family, page, size })`.
- Produces: `sendReplayDailyReportMail(mail, files, token)`.
- Produces: `sendReplayWeeklyReportMail(mail, files, token)`.
- Both send functions construct `FormData` with one JSON `mail` Blob and repeated `files` entries and must not manually set `Content-Type`.

- [ ] **Step 1: Write failing API tests**

Assert candidate query encoding and inspect FormData:

```javascript
await sendReplayDailyReportMail(mail, [fileA, fileB], 'secret')
const [, options] = fetch.mock.calls.at(-1)
expect(options.headers).toEqual({ 'X-DII-Trigger-Token': 'secret' })
expect(options.body).toBeInstanceOf(FormData)
expect(options.body.getAll('files')).toEqual([fileA, fileB])
expect(JSON.parse(await options.body.get('mail').text())).toEqual(mail)
```

Add the same weekly assertion and verify empty file arrays submit only the `mail` part.

- [ ] **Step 2: Run API tests and verify failure**

```bash
npm test -- --run src/api/replayIssues.spec.js
```

- [ ] **Step 3: Implement FormData requests and option query**

Use:

```javascript
const form = new FormData()
form.append('mail', new Blob([JSON.stringify(mail || {})], { type: 'application/json' }))
for (const file of files || []) form.append('files', file, file.name)
```

Call the existing request helper with the token header only. Do not stringify FormData and do not set multipart boundaries manually.

- [ ] **Step 4: Run API tests and diff check**

```bash
npm test -- --run src/api/replayIssues.spec.js
git diff --check -- src/api/replayIssues.js src/api/replayIssues.spec.js
```

---

### Task 7: Add the Shared Daily/Weekly Attachment UI

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayReportMailAttachments.vue`
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayReportMailAttachments.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `getReplayReportAttachmentOptions` and the two multipart send functions from Task 6.
- Produces props: `currentAttachment`, `selectedReports`, `localFiles`, `disabled`.
- Produces emits: `update:selectedReports`, `update:localFiles`, `validation-change`.
- Produces parent state: `dailyReportMailSelectedReports`, `dailyReportMailLocalFiles`, and computed total bytes/error.

- [ ] **Step 1: Write failing component tests**

Cover:

- Fixed current attachment displays filename, size, and “系统自动附加” with no remove button.
- “添加已生成日报” opens a selector with `全部/查询日报/账务日报`, debounced keyword search, pagination, and multi-select.
- Selecting the current daily batch produces a duplicate hint and does not add an item.
- Selected generated reports and local files have individual remove buttons.
- File input has `accept=".xls,.xlsx"` and `multiple`; `.txt`, files over 20 MiB, and total size over 50 MiB emit an invalid state.
- Selector loading and failure states remain inside the dialog and do not close the email modal.

- [ ] **Step 2: Run component tests and verify failure**

```bash
npm test -- --run src/components/replay/ReplayReportMailAttachments.spec.js
```

- [ ] **Step 3: Implement the attachment component**

Keep all candidate search, family filter, pagination, deduplication, size formatting, local input reset, and item removal inside the component. Emit immutable arrays after every change. Render the summary as `共 N 个附件 / 12.4 MB` and expose one validation message at a time, prioritizing invalid type, single-file size, then total size.

- [ ] **Step 4: Write failing page integration tests**

Extend existing daily and weekly mail tests so configuration mocks return:

```javascript
currentAttachment: {
  fileName: 'RPT20260915-102753日报.xlsx',
  fileSize: 6470,
  source: 'CURRENT_REPORT',
  batchNo: 'RPT20260915-102753',
}
```

Assert both dialogs render the shared component, sending includes `reportBatchNos` and local `File[]`, invalid attachments disable confirmation, sending disables attachment changes, a failed request preserves selections, and closing/reopening clears optional attachments.

- [ ] **Step 5: Integrate the component into `ReplayIssuePage.vue`**

Replace the single attachment paragraph with `ReplayReportMailAttachments`. Initialize fixed metadata from mail config, reset optional state in both `openDailyReportMail` and `openWeeklyReportMail`, and call:

```javascript
await sendReplayDailyReportMail(
  { batchNo, subject, toEmails, ccEmails, body, reportBatchNos },
  dailyReportMailLocalFiles.value,
  token,
)
```

Use the equivalent weekly call with `startBatchNo` and `endBatchNo`. Keep the existing email address editing and operation-token behavior unchanged.

- [ ] **Step 6: Run frontend report tests**

```bash
npm test -- --run \
  src/api/replayIssues.spec.js \
  src/components/replay/ReplayReportMailAttachments.spec.js \
  src/components/replay/ReplayIssuePage.spec.js
```

Expected: all selected frontend tests pass.

---

### Task 8: Full Regression, Build, and Documentation Closure

**Files:**
- Modify after implementation verification: `/Users/java/obsidian/log.md`
- Build output: `/Users/java/axon-link-server/src/main/resources/static/`

**Interfaces:**
- Consumes all prior tasks.
- Produces verified frontend assets embedded in the backend and an implementation log entry with actual test counts.

- [ ] **Step 1: Run the complete frontend suite**

```bash
cd /Users/java/axon-link-frontend
npm test
```

Expected: zero failed files and zero failed tests.

- [ ] **Step 2: Build the frontend into backend static resources**

```bash
cd /Users/java/axon-link-frontend
npm run build
```

Expected: Vite exits `0`; generated assets appear under `/Users/java/axon-link-server/src/main/resources/static/` according to the existing Vite configuration.

- [ ] **Step 3: Run the complete backend suite on JDK 17**

```bash
cd /Users/java/axon-link-server
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn test
```

Expected: Maven `BUILD SUCCESS`, zero failures, and zero errors. Record skipped tests separately.

- [ ] **Step 4: Run packaging and source checks**

```bash
cd /Users/java/axon-link-server
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -DskipTests package
git diff --check
```

Expected: package exits `0` and diff check prints no errors. Do not create a ZIP unless the user requests one after verification.

- [ ] **Step 5: Perform manual browser acceptance**

Verify both daily and weekly dialogs:

1. Current report is visible and cannot be deleted.
2. RPT and DZ generated reports can be searched, selected multiple times without duplication, and removed.
3. Multiple `.xls/.xlsx` local files can be added and removed.
4. Invalid files and size limits block sending with Chinese messages.
5. The final request sends one email with attachments in the specified order.
6. Failed sending preserves the optional attachment selections for retry.

- [ ] **Step 6: Append the implementation log with measured results**

Append one `[IMPL]` line to `/Users/java/obsidian/log.md` containing the actual frontend test count, backend test count, skipped count, build status, and the three attachment-source rules. Do not state counts before commands in Steps 1–4 have completed.

- [ ] **Step 7: Review only the intended changes**

```bash
git status --short
git diff --stat
git diff --check
```

Because both repositories already contain user changes, do not reset, clean, stage, or commit unrelated files.
