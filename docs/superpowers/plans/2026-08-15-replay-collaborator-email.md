# 回放问题协同邮件 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在回放问题编辑保存时按需向需协同人发送当前问题邮件，并按问题、编辑内容、系统发件人和收件人去重展示发送状态。

**Architecture:** 复用现有 `MailService` 异步发送能力；新增回放邮件发送记录表和 DAO，通过内容 SHA-256 指纹判断当前协同人是否已收到相同内容。编辑保存事务先更新问题和历史，再由邮件服务异步发送并更新发送记录，发送失败不回滚编辑。

**Tech Stack:** Spring Boot, JdbcTemplate, MySQL migrations, JavaMailSender, Vue 3, Vitest.

## Global Constraints

- 系统配置邮箱是唯一发件人，收件人为 `ccbs_ai_sys_user.email`。
- 去重键必须包含 `issue_key`、编辑内容指纹、发件邮箱和收件邮箱。
- 邮件失败不得回滚问题编辑保存。
- 页面空值展示规则和既有问题跟踪逻辑保持不变。

### Task 1: 邮件发送记录数据模型

**Files:**
- Create: `src/main/resources/db/daoindex/V46__dii_replay_issue_mail.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueMailStatus.java`
- Create: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueMailDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueMailDaoTest.java`

- [ ] Write a failing test proving identical issue/content/sender/recipient returns `SENT`, a changed content returns `PENDING`, and a new recipient returns `UNSENT`.
- [ ] Run `mvn -q -Dcheckstyle.skip=true -Dtest=ReplayIssueMailDaoTest test` and verify the expected missing-table/method failure.
- [ ] Add `dii_replay_issue_mail` with issue id/key, recipient username/email, sender email, content fingerprint, status, sent time, failure message, and unique key over issue/content/sender/recipient.
- [ ] Implement DAO lookup, create pending record, mark sending/sent/failed, and status projection methods.
- [ ] Run the focused test and verify it passes.

### Task 2: Save-flow mail orchestration

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueUpdateRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Modify: `src/main/java/com/axonlink/notification/service/MailService.java` only if an async callback-compatible method is needed
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMailService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMailServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

- [ ] Add `sendMail` to the update request with default `false` for compatibility.
- [ ] Write failing tests for no-send, first-send, changed-content resend, recipient-change send, unchanged-content dedupe, and missing collaborator email.
- [ ] Implement canonical content serialization and SHA-256 fingerprint using issue key, current issue fields, and edited fields; exclude recipient from content but include recipient email in dedupe lookup.
- [ ] Resolve selected collaborator email through `SysUser`, save issue/history first, then enqueue email and update record state.
- [ ] Use subject `回放问题协同通知：{issue_key}` and escaped plain-text/HTML-safe content; do not expose SMTP credentials.
- [ ] Run focused service tests and the existing replay edit tests.

### Task 3: API and frontend status display

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

- [ ] Add a status projection endpoint or row fields returning `UNSENT`, `SENDING`, `SENT`, `PENDING`, `FAILED`, and last sent time for the current collaborator/content.
- [ ] Add the edit-modal checkbox, status text, tooltip for “待发送（内容已变更）”, and disable sending when no collaborator email exists.
- [ ] Add the email status column to the issue list while preserving existing export and filter behavior.
- [ ] Write failing UI tests for checkbox payload and status labels, then implement and run the focused Vitest suite.

### Task 4: Verification and delivery

- [ ] Run `mvn -q -Dcheckstyle.skip=true test` and record unrelated pre-existing failures separately.
- [ ] Run `npm run test -- --run src/components/replay/ReplayIssuePage.spec.js`.
- [ ] Run `npm run build` so latest frontend assets land in `src/main/resources/static`.
- [ ] Verify migration SQL syntax, source package contents, and document required `MAIL_*` environment variables.
