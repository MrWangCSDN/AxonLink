# Replay Collaborator Email Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Save replay issue edits first, then ask whether to send the issue email; derive recipients and content on the backend and expose per-collaborator mail status without duplicate sends.

**Architecture:** The backend owns the canonical mail fingerprint, recipient resolution, subject, and column-style body. The existing mail record remains the deduplication ledger, while the frontend only presents collaborator/status rows and invokes the send decision after a successful save.

**Tech Stack:** Spring Boot, JdbcTemplate, JavaMailSender, Vue 3, Vitest.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Fingerprint fields are `issue_id + transaction_code + issue_status + issue_type + initial_analysis + final_solution + cooperation_person + remark`.
- Recipients are all collaborators and developers; technology owners are CC only; duplicate email addresses are removed.
- SysUser email is preferred; missing email falls back to `<username>@spdbdev.com` for non-staff accounts.
- Email is sent only after save and explicit confirmation; saving with “否” never sends.
- If the saved issue status is `修复待验证`, save only; do not show confirmation and do not send mail.
- A successful send with unchanged fingerprint is never repeated; failed sends do not roll back the saved issue.

### Task 1: Define backend recipient and message contract

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMailService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayTransactionPersonDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueMailStatus.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMailServiceTest.java`

**Interfaces:**
- Add a transaction-person lookup by transaction code returning developer usernames and bank-owner employee numbers.
- Add a mail preview/status projection containing collaborator labels, recipient email, status, and failure message.
- Keep the existing `requestSend(ReplayIssueRow)` entry point for compatibility, but make it resolve all To/CC addresses.

- [ ] Write tests for issue_id/transaction_code fingerprint fields, username fallback email, multi-person recipient de-duplication, subject, and column-style body.
- [ ] Run the focused mail service tests and verify the new assertions fail before implementation.
- [ ] Implement recipient resolution: parse `姓名(username)` developer display values, resolve bank owners by emp_no, use stored email or username fallback, remove blanks and duplicates, and omit empty CC.
- [ ] Implement subject `issue_id 是 xxxx 的问题协同处理` and body with platform URL plus one field per line.
- [ ] Run the focused backend tests and verify they pass.

### Task 2: Update mail status persistence and API response

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueMailStatus.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueMailDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueMailDaoTest.java`

**Interfaces:**
- `GET /api/ai/parallel-replay/issues/{id}/mail-status` returns collaborator status entries and aggregate status.
- `PATCH /api/ai/parallel-replay/issues/{id}` continues saving the issue; sending remains a post-save action.

- [ ] Add DAO/service tests for UNSENT, SENT, PENDING, and FAILED states, including failure reason retrieval.
- [ ] Implement status lookup per resolved collaborator and project changed content as pending.
- [ ] Preserve existing mail table rows and make the latest successful hash the comparison source.
- [ ] Run DAO and controller tests.

### Task 3: Replace checkbox UI with collaborator status rows and save confirmation

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Add a backend mail-preview/status call used when opening the editor and after selecting a collaborator.
- After `updateReplayIssue` succeeds, if there are recipients, show one confirmation per collaborator; “是” calls the send endpoint, “否” closes without sending.

- [ ] Add component tests proving the old checkbox is absent, collaborator status appears on the same row, failed status exposes a title, and save with “否” does not call send.
- [ ] Implement compact collaborator rows with name/email, status badge, and failure tooltip; show “未发送/已发送/待发送/发送失败”.
- [ ] Implement post-save confirmation using the resolved issue_id and recipient email; skip confirmation/send for unchanged already-sent recipients.
- [ ] Add the status gate before confirmation: `修复待验证` always skips confirmation and send, while other statuses retain the normal flow.
- [ ] Refresh issue list and mail status after save/send while preserving table horizontal scroll.
- [ ] Run the focused Vitest suite.

### Task 4: Mock data, build, and regression verification

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`

- [ ] Add mock responses for multiple collaborators, fallback addresses, sent/pending/failed states, and confirmation send results.
- [ ] Run the replay frontend test suite and production build.
- [ ] Run backend replay mail tests and compile the server.
- [ ] Open the local mock page and verify the editor layout, confirmation text, status rows, and failure tooltip visually.
