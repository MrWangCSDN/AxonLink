# 回放周报 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从两个已生成且同族的日报批次生成永久三 Sheet 周报，并支持直接下载、邮件发送和状态展示。

**Architecture:** 周报以日报快照作为候选资格门禁，以四类日报原始表和问题投影作为计算输入。起始批次映射日报计算器的 `previous`，结束批次映射 `current`；工作簿复用现有日报计算器和 Writer，快照及邮件状态使用独立组合主键表保存。

**Tech Stack:** Java 17、Spring Boot、Spring JDBC、Apache POI、MySQL/H2、Vue 3、Vitest

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- 起止批次都必须已经生成日报、同属 `RPT` 或 `DZ`，且起始早于结束；允许跨过中间批次。
- 汇总信息使用 `previous=起始`、`current=结束`，另外两个 Sheet 只取结束批次。
- 周报按 `(start_batch_no, end_batch_no)` 永久保存，后续直接下载，不自动失效或重新生成。
- 周报邮件强制附加已生成周报，收件人、抄送人、发件人和口令规则复用日报。
- 不修改日报快照、问题清单、问题状态或现有日报计算公式。

---

### Task 1: 周报快照与邮件持久化

**Files:**
- Create: `src/main/resources/db/daoindex/V60__dii_replay_weekly_report.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportSnapshot.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportOption.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportOptions.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportMailStatus.java`
- Create: `src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDao.java`
- Create: `src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportMailDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportMailDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`

**Interfaces:**
- Produces: `Optional<ReplayWeeklyReportSnapshot> findSnapshot(String startBatchNo, String endBatchNo)`
- Produces: `void saveSnapshot(ReplayWeeklyReportSnapshot snapshot)`
- Produces: `List<ReplayWeeklyReportOption> findGeneratedReports()`
- Produces: `markSending(...)`, `markSent(...)`, `markFailed(...)`, `find(...)` keyed by both batch numbers.

- [x] **Step 1: Write the failing migration/DAO tests**

Assert composite uniqueness, independent ranges sharing one end batch, exact BLOB round-trip, generated ordering, `UNSENT` projection, mail state transitions, and cascade from weekly snapshot to weekly mail only.

- [x] **Step 2: Run tests to verify RED**

Run: `mvn -Dtest=ReplayWeeklyReportDaoTest,ReplayWeeklyReportMailDaoTest test`

Expected: test compilation fails because weekly DTOs and DAOs do not exist.

- [x] **Step 3: Add the migration and immutable DTOs**

Migration creates:

```sql
CREATE TABLE IF NOT EXISTS dii_replay_weekly_report_snapshot (
    start_batch_no VARCHAR(128) NOT NULL,
    end_batch_no VARCHAR(128) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_content LONGBLOB NOT NULL,
    file_size BIGINT NOT NULL,
    generated_at DATETIME NOT NULL,
    PRIMARY KEY (start_batch_no, end_batch_no),
    INDEX idx_replay_weekly_report_end (end_batch_no)
);
```

The same migration creates `dii_replay_weekly_report_mail` with the composite primary/foreign key and the same mail columns as the daily mail table.

- [x] **Step 4: Implement DAOs with parameterized SQL**

Use `ON DUPLICATE KEY UPDATE` only as a concurrency fallback. Normal service behavior returns existing snapshots without overwriting them.

- [x] **Step 5: Run DAO tests to verify GREEN**

Run: `mvn -Dtest=ReplayWeeklyReportDaoTest,ReplayWeeklyReportMailDaoTest test`

---

### Task 2: 周报生成、范围校验与永久下载

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`

**Interfaces:**
- Produces: `ReplayWeeklyReportOptions options()`
- Produces: `ReplayWeeklyReportSnapshot generate(String startBatchNo, String endBatchNo)`
- Uses: `ReplayDailyReportCalculator.calculate(previousSummaries, previousIssues, currentSummaries, currentIssues)`
- Uses: `ReplayDailyReportWorkbookWriter.write(calculated, endComparisons, endCoverageSummaries, endCoverageDetails)`

- [x] **Step 1: Write failing service tests**

Cover:

```text
RPT start < RPT end across intermediate batches -> generates
DZ start < DZ end -> generates
RPT + DZ / same batch / reverse order -> range error
either daily snapshot missing -> daily-not-generated error
either raw summary missing -> batch-data error
existing weekly snapshot -> returns exact bytes without calculator/writer calls
same end batch with different starts -> independent snapshots
```

Assert the calculator receives start data as previous and end data as current; Writer receives only end-batch interface and coverage rows.

- [x] **Step 2: Run tests to verify RED**

Run: `mvn -Dtest=ReplayWeeklyReportServiceTest test`

- [x] **Step 3: Add generated daily candidate query**

Add `ReplayDailyDataDao.findGeneratedBatchesInFamilyOrder()` returning only standard batches with both summary data and daily snapshot, ordered by family, `imported_at`, then `batch_no`.

- [x] **Step 4: Implement weekly service**

Validate standard batch format and membership in the generated candidate order before opening a repeatable-read transaction. Inside the transaction load start/end summaries and issue statistics plus end-only detail datasets; outside the transaction calculate, write, and save:

```java
new ReplayWeeklyReportSnapshot(
    startBatchNo,
    endBatchNo,
    endBatchNo + "周报.xlsx",
    XLSX_CONTENT_TYPE,
    bytes,
    bytes.length,
    LocalDateTime.now(clock));
```

- [x] **Step 5: Run service and existing daily report tests**

Run: `mvn -Dtest=ReplayWeeklyReportServiceTest,ReplayIssueDailyReportServiceTest,ReplayDailyReportCalculatorTest,ReplayDailyReportWorkbookWriterTest test`

---

### Task 3: 周报邮件配置、附件发送与后端 API

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/config/ReplayDailyReportMailProperties.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportMailSendRequest.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportMailView.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- `GET /api/ai/parallel-replay/issues/weekly-report/options`
- `GET /api/ai/parallel-replay/issues/weekly-report?startBatchNo=...&endBatchNo=...`
- `GET /api/ai/parallel-replay/issues/weekly-report/mail-config?startBatchNo=...&endBatchNo=...`
- `POST /api/ai/parallel-replay/issues/weekly-report/mail-send`

- [x] **Step 1: Write failing mail and controller tests**

Verify status mapping, editable subject/to/cc/body, title date from `endBatchNo`, shared recipient configuration, mandatory weekly snapshot attachment, trigger-token rejection, SMTP failure persistence, exact Chinese errors, and xlsx response filename.

- [x] **Step 2: Run tests to verify RED**

Run: `mvn -Dtest=ReplayWeeklyReportMailServiceTest,ReplayIssueControllerTest test`

- [x] **Step 3: Extend YAML defaults**

Add:

```yaml
weekly-subject-prefix: ${REPLAY_WEEKLY_REPORT_MAIL_SUBJECT_PREFIX:对公分布式核心回放问题周报-}
weekly-body: ${REPLAY_WEEKLY_REPORT_MAIL_BODY:各位好，附件为本周期回放问题周报，请查收。}
```

Keep existing `to`, `cc`, SMTP sender, and token keys unchanged.

- [x] **Step 4: Implement weekly mail service and controller mappings**

Reuse the daily mail validation rules: subject `1..255`, body `1..10000`, at least one valid recipient, case-insensitive deduplication, and `SENDING -> SENT|FAILED`. Always attach the BLOB returned by `ReplayWeeklyReportDao.findSnapshot(start, end)`.

- [x] **Step 5: Run mail/controller tests to verify GREEN**

Run: `mvn -Dtest=ReplayWeeklyReportMailServiceTest,ReplayIssueControllerTest test`

---

### Task 4: 前端周报选择、下载和共享邮件编辑器

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`

**Interfaces:**
- Produces API helpers: `getReplayWeeklyReportOptions`, `downloadReplayWeeklyReport`, `getReplayWeeklyReportMailConfig`, `sendReplayWeeklyReportMail`.
- 日报、周报复用页面内同一套邮件地址标签编辑器和发送状态。

- [x] **Step 1: Write failing frontend tests**

Cover button placement, generated-only candidates, same-family/later end filtering, cross-intermediate selection, generated/download label, permanent status refresh, email state, editable chips/body/subject, X/cancel/submit closing rules, and failure keeping the modal open.

- [x] **Step 2: Run tests to verify RED**

Run: `npm test -- src/components/replay/ReplayReportMailModal.spec.js src/components/replay/ReplayIssuePage.spec.js`

- [x] **Step 3: Reuse the shared report mail editor**

Use the same page-level mail editor for daily and weekly reports so 20+ recipient wrapping, paste splitting, delete, backspace, token handling, and close behavior remain identical without duplicating modal code.

- [x] **Step 4: Add weekly report modal and API flow**

Place “周报” immediately after “日报”. Keep start/end selections in component state; derive valid end options from candidate order and family. Pair generated state by exact `startBatchNo + endBatchNo`, not by end batch alone.

- [x] **Step 5: Add deterministic mock data and handlers**

Mock at least four generated RPT daily batches, two generated DZ batches, one existing weekly pair, generation persistence, and weekly mail status transitions.

- [x] **Step 6: Run focused and full frontend tests**

Run focused command from Step 2, then `npm test`.

---

### Task 5: 集成验证、静态资源和交付

**Files:**
- Modify: `/Users/java/obsidian/log.md`
- Modify: this plan checkbox state

- [x] **Step 1: Run backend focused regression**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Dtest='ReplayWeeklyReport*Test,ReplayIssueDailyReportServiceTest,ReplayDailyReportCalculatorTest,ReplayDailyReportWorkbookWriterTest,ReplayIssueControllerTest' test
```

- [x] **Step 2: Run full backend verification and package**

Run: `mvn clean package`

Expected: zero failures/errors and `target/axon-link-server-1.0.0.jar` created.

- [x] **Step 3: Build frontend into backend resources**

Run from `/Users/java/axon-link-frontend`: `VITE_USE_MOCK=0 npm run build`

- [x] **Step 4: Verify migration and packaged static assets**

Inspect the JAR for `V60__dii_replay_weekly_report.sql`, weekly classes, and current `static/index.html`.

- [x] **Step 5: Package backend source**

Run: `./scripts/package-source.sh axon-link-server-source-<yyyyMMdd-HHmm>.zip` and verify with `unzip -tq`.

- [ ] **Step 6: Update implementation log**

Append one `[IMPL]` line to `/Users/java/obsidian/log.md` containing focused/full test counts and final JAR/ZIP paths.
