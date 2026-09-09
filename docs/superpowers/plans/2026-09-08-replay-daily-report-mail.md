# Replay Daily Report Mail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send an already-generated replay daily report as a mandatory Excel attachment using editable per-send mail content initialized from YAML defaults, protected by the existing import token and showing persistent per-batch send status.

**Architecture:** A dedicated replay daily-report mail service reads the current report snapshot, computes the fixed subject from the batch date, and uses the existing SMTP service's new synchronous attachment method. A batch-keyed status table references the report snapshot with `ON DELETE CASCADE`, so report invalidation automatically resets mail status. The Vue daily-report modal exposes a secondary mail action and a separate compose dialog.

**Tech Stack:** Java 17, Spring Boot 3, Spring Mail, Spring JDBC, Flyway-style SQL migrations, Vue 3, Vitest, Apache POI-generated XLSX snapshots.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Only an existing `dii_replay_daily_report_snapshot` may be attached; sending must never generate or accept an uploaded attachment.
- Subject, recipients, CC recipients, and body are initialized from YAML/environment defaults and may be edited for one send without changing configuration.
- Reuse `X-DII-Trigger-Token` and `DII_BATCH_TRIGGER_TOKEN`.
- Default subject is `对公分布式核心回放问题日报-yyyyMMdd`, with the date parsed from a standard RPT/DZ batch; the complete generated subject may then be edited.
- Subject is required with a maximum length of 255 characters; at least one valid recipient is required; email addresses are deduplicated case-insensitively; body is required plain text with a maximum length of 10000 characters.
- SMTP send is synchronous; status is persisted as `SENDING`, `SENT`, or `FAILED`, and absent rows project as `UNSENT`.
- Report snapshot deletion must automatically remove matching mail status.
- Sending must not update report source data or any replay issue table.
- The client cannot upload, replace, or remove the attachment; missing, empty, or unreadable snapshot content aborts before SMTP.
- Do not commit or reset existing user changes.

---

### Task 1: Mail Status Persistence and Batch Projection

**Files:**
- Create: `src/main/resources/db/daoindex/V58__dii_replay_daily_report_mail.sql`
- Create: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyReportMailDao.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailStatus.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyBatch.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`
- Create: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyReportMailDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`

**Interfaces:**
- Produces: `ReplayDailyReportMailDao.find(String batchNo)`, `markSending(...)`, `markSent(...)`, and `markFailed(...)`.
- Produces: `ReplayDailyBatch.mailStatus()`, `mailSentAt()`, and `mailFailureMessage()`.

- [ ] **Step 1: Write failing DAO and batch projection tests**

Cover absent status as `UNSENT`, status transitions, latest-attempt overwrite, failure truncation, and snapshot deletion cascading the status row. Verify `findBatchesRecentFirst()` returns status only for the current snapshot.

- [ ] **Step 2: Run persistence tests and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportMailDaoTest,ReplayDailyDataDaoTest test`

Expected: compilation failure because the status DAO and batch fields do not exist.

- [ ] **Step 3: Add migration, DAO, DTO, and batch join**

Create `dii_replay_daily_report_mail` keyed by `batch_no`, with a foreign key to `dii_replay_daily_report_snapshot(batch_no) ON DELETE CASCADE`. Extend the batch query with the status table and keep compatibility constructors for existing tests.

- [ ] **Step 4: Run persistence tests and verify GREEN**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportMailDaoTest,ReplayDailyDataDaoTest test`

Expected: all persistence tests pass.

### Task 2: Configured Attachment Mail Service

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/config/ReplayDailyReportMailProperties.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailView.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportMailService.java`
- Modify: `src/main/java/com/axonlink/notification/service/MailService.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportMailServiceTest.java`

**Interfaces:**
- Consumes: YAML lists `axon-link.replay.daily-report-mail.to` and `.cc`.
- Produces: `MailService.sendTextWithAttachmentSync(List<String> to, List<String> cc, String subject, String body, String fileName, byte[] content, String contentType)`.
- Produces: `ReplayDailyReportMailService.configuration(String batchNo)` and `send(String batchNo, String body)` returning `ReplayDailyReportMailView`.

- [ ] **Step 1: Write failing service tests**

Verify fixed RPT/DZ subjects, configured TO/CC use, exact snapshot bytes and filename attachment, missing snapshot/config validation, 10000-character body boundary, `SENT` persistence, and `FAILED` persistence without touching issue tables.

- [ ] **Step 2: Run mail service tests and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportMailServiceTest test`

Expected: compilation failure because the service and attachment method do not exist.

- [ ] **Step 3: Implement properties, MIME attachment sending, and business service**

Use `MimeMessageHelper(..., true, UTF-8)` and `ByteArrayResource` for the xlsx attachment. Normalize and deduplicate configured addresses, validate the standard batch and body, persist `SENDING` before SMTP, then persist `SENT` or `FAILED`.

- [ ] **Step 4: Run mail service tests and verify GREEN**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportMailServiceTest test`

Expected: all service tests pass.

### Task 3: Protected Daily Report Mail API

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailSendRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `GET /api/ai/parallel-replay/issues/daily-report/mail-config?batchNo=...`.
- Produces: `POST /api/ai/parallel-replay/issues/daily-report/mail-send` with body `{ "batchNo": "...", "body": "..." }` and `X-DII-Trigger-Token`.

- [ ] **Step 1: Write failing controller tests**

Cover config response, successful send, wrong token 401, malformed batch 400, missing snapshot 404, incomplete config 503, and SMTP failure 502. Verify the request cannot supply recipients, subject, or attachment.

- [ ] **Step 2: Run controller tests and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueControllerTest test`

Expected: endpoint-not-found or compilation failure.

- [ ] **Step 3: Add endpoints and exception mapping**

Reuse `properties.getBatchTrigger().getToken()` for send authorization. Map business exceptions to the documented Chinese messages and never log the supplied token or body.

- [ ] **Step 4: Run controller tests and verify GREEN**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueControllerTest test`

Expected: all controller tests pass.

### Task 4: Daily Report Mail UI and Mock

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`

**Interfaces:**
- Consumes: batch `mailStatus`, `mailSentAt`, and `mailFailureMessage` fields plus the two mail endpoints from Task 3.
- Produces: mail compose modal with read-only subject/TO/CC, body, password, synchronous send feedback, and resend behavior.

- [ ] **Step 1: Write failing API, component, and mock tests**

Verify the mail button appears only for generated reports, displays `邮件发送` for unsent and `重新发送` for sent/failed, loads configured recipients, requires body/token, disables duplicate sends, preserves the report modal, and refreshes batch status after success or failure.

- [ ] **Step 2: Run frontend tests and verify RED**

Run: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js mock/daoIndexMockServer.spec.js`

Expected: missing API functions and UI selectors.

- [ ] **Step 3: Implement API calls, modal, styles, and mock state**

Add a secondary mail action beside the Excel button. The compose dialog reads title and recipients from the server, accepts only body and password, and shows `未发送/发送中/已发送/发送失败` without exposing editable recipient fields.

- [ ] **Step 4: Run frontend tests and verify GREEN**

Run: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js mock/daoIndexMockServer.spec.js`

Expected: all focused frontend tests pass.

### Task 5: Editable Per-Send Mail Content

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailSendRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/config/ReplayDailyReportMailProperties.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportMailService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportMailServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`

**Interfaces:**
- Consumes: `POST /daily-report/mail-send` body `{batchNo, subject, toEmails, ccEmails, body}`.
- Produces: editable defaults from `GET /daily-report/mail-config`; persisted send status contains actual submitted values.

- [ ] **Step 1: Write failing backend and frontend tests**

Cover editable subject/TO/CC/body submission, batch-derived default title date, YAML default body, address normalization and validation, required recipient/title/body limits, persisted actual values, and mandatory current snapshot attachment without any client attachment field.

- [ ] **Step 2: Run focused tests and verify RED**

Run backend: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportMailServiceTest,ReplayIssueControllerTest test`

Run frontend: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js mock/daoIndexMockServer.spec.js`

Expected: tests fail because request fields, default body, editable controls, and validation do not exist.

- [ ] **Step 3: Implement the minimal backend, frontend, and mock changes**

Extend the request DTO and service API; retain snapshot lookup as the only attachment source. Add editable title and address inputs to the compose dialog, initialize all values from configuration, submit actual values, and mirror behavior in Mock.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the commands from Step 2 and expect all focused tests to pass.

### Task 6: Regression and Build Verification

**Files:**
- Verify only.

**Interfaces:**
- Confirms backend migration, report generation/download, snapshot invalidation, mail status, frontend flow, and existing issue mail remain compatible.

- [ ] **Step 1: Run focused backend replay tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportMailDaoTest,ReplayDailyDataDaoTest,ReplayDailyReportMailServiceTest,ReplayIssueDailyReportServiceTest,ReplayIssueImportServiceTest,ReplayIssueEditServiceTest,ReplayIssueControllerTest test`

- [ ] **Step 2: Run full frontend tests and build**

Run: `npm test -- --run && npm run build`

- [ ] **Step 3: Run backend diff check, full tests, and package**

Run: `git diff --check && JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test && JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -DskipTests package`

Expected: zero failures, zero diff errors, and successful backend/frontend builds.

### Task 7: YAML Recipient List Compatibility

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportMailService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportMailServiceTest.java`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`

**Interfaces:**
- Consumes: YAML lists `axon-link.replay.daily-report-mail.to` and `cc`; each entry may also be an environment-variable string containing comma-, semicolon-, Chinese punctuation-, or newline-separated addresses.
- Produces: normalized, case-insensitively deduplicated `List<String>` defaults without changing HTTP or database contracts.

- [x] **Step 1: Add a failing normalization test**

Set `to` and `cc` with a mixture of separate YAML-style entries and delimiter-separated environment-style entries. Assert that configuration returns flattened, trimmed, lower-case, de-duplicated lists.

- [x] **Step 2: Run the focused test and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportMailServiceTest test`

Expected: the new test fails because configured entries are not yet split.

- [x] **Step 3: Implement YAML list configuration and compatible normalization**

Express `to` and `cc` as YAML sequences in `application.yml`. Split every configured list entry with `[;,，；\\n]+`, discard blanks, trim, normalize to lower case, and preserve first-seen order.

- [x] **Step 4: Verify focused tests, package, and configuration documentation**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportMailServiceTest,ReplayIssueControllerTest test && JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -DskipTests package && git diff --check`

Expected: zero failures, successful package, and no diff whitespace errors.

### Task 8: Recipient Address Tags

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`
- Build output: `src/main/resources/static/`

**Interfaces:**
- Consumes: unchanged `toEmails` and `ccEmails` arrays from the mail configuration API.
- Produces: unchanged `toEmails` and `ccEmails` arrays in the send API; only the compose UI representation changes.

- [x] **Step 1: Add failing component tests for address tags**

Assert that every configured address renders as an independent removable tag, pasted delimiter-separated addresses become tags, duplicate addresses collapse case-insensitively, and the submitted request still contains arrays.

- [x] **Step 2: Run the focused component test and verify RED**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: selectors for recipient tags are missing.

- [x] **Step 3: Implement the minimal tag editor**

Keep recipient arrays separately from a trailing draft input. Commit draft text on Enter, comma, semicolon, Chinese punctuation, paste, or blur; wrap tags across lines; allow deleting individual tags and Backspace deletion of the last tag; disable editing while sending.

- [x] **Step 4: Run frontend tests, build into backend, and verify diffs**

Run: `npm test -- --run && npm run build && git diff --check && git -C /Users/java/axon-link-server diff --check`

Expected: all frontend tests pass, production assets are written to the backend, and no whitespace errors remain.
