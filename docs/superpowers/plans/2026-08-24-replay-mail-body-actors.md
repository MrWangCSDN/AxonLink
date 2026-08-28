# Replay Mail Body Actors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the current logged-in sender, dynamically matched transaction owner, and dynamically matched technology owner to replay collaboration email bodies, leaving missing values blank and keeping status deduplication aligned with the actual body.

**Architecture:** The controller resolves the authenticated `ReplayIssueOperator` for both mail status and send requests and passes it to `ReplayIssueMailService`. The service loads `ReplayTransactionPersonRow` once, builds an immutable mail context used by body generation, recipient resolution, and content hashing, so the rendered message and deduplication key cannot diverge.

**Tech Stack:** Java 17, Spring Boot MVC, Spring JDBC, JUnit 5, Mockito.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- SMTP envelope sender remains the configured system mailbox.
- Body `发件人` comes only from the authenticated back-end principal and renders as `realName(username)`.
- Body `交易负责人` uses `ReplayTransactionPersonRow.developer`; body `科技负责人` uses `ReplayTransactionPersonRow.bankOwner`.
- Missing actor/owner values render as an empty value after the label, never `-`.
- Sender and both owner values participate in the content hash.
- Do not change database schema, SMTP configuration, recipient/CC rules, or front-end request payload.
- Preserve all unrelated dirty-worktree changes; do not commit, push, merge, reset, or remove user files.

---

### Task 1: Mail context, body, and content fingerprint

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMailServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMailService.java`

**Interfaces:**
- Consumes: `ReplayIssueOperator`, `ReplayIssueRow`, and `ReplayTransactionPersonDao.findByTransactionCode`.
- Produces: `status(ReplayIssueRow, ReplayIssueOperator)` and `requestSend(ReplayIssueRow, List<String>, ReplayIssueOperator)`; compatibility overloads may delegate with a blank operator for isolated legacy callers.

- [ ] **Step 1: Write failing mail-service tests**

Add tests asserting the sent body contains `发件人：张三(zhangs3)`, `交易负责人：开发甲、开发乙`, and `科技负责人：科技甲`; add a missing-directory test asserting all three labels remain while absent owner values are empty; add a hash test proving sender or matched-owner changes alter the content hash.

- [ ] **Step 2: Run the focused service test and verify RED**

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home mvn -Dtest=ReplayIssueMailServiceTest test`

Expected: compilation or assertion failure because operator-aware body/context APIs do not exist.

- [ ] **Step 3: Implement one immutable mail context**

Resolve the transaction-person row once per status/send call. Render login display with blank-safe `realName(username)` formatting, render `developer`/`bankOwner` exactly as stored by the current transaction directory, use an empty-string helper for the three new body lines, and feed the same values into SHA-256 content generation and asynchronous `sendNow`.

- [ ] **Step 4: Run the focused service test and verify GREEN**

Run the Task 1 command and confirm every service test passes.

### Task 2: Authenticated controller propagation and packaging verification

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`

**Interfaces:**
- Consumes: operator-aware service methods from Task 1.
- Produces: authenticated `GET /{id}/mail-status` and `POST /{id}/mail-send` behavior without any new front-end parameter.

- [ ] **Step 1: Write failing controller tests**

Add a status test that receives HTTP 401 without a resolved login and a send-path test proving the resolved login reaches the body rendered by the real mail service or a captured service invocation.

- [ ] **Step 2: Run the focused controller tests and verify RED**

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home mvn -Dtest=ReplayIssueControllerTest test`

Expected: the new status authorization assertion fails or operator-aware calls are absent.

- [ ] **Step 3: Implement controller propagation**

Resolve `ReplayIssueOperator` once in each endpoint, return HTTP 401 when absent, and pass the operator to the service. Keep request and response JSON unchanged.

- [ ] **Step 4: Run focused regressions**

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home mvn -Dtest=ReplayIssueMailServiceTest,ReplayIssueControllerTest test` and report the three known unrelated batch-tracking assertions separately if they remain.

- [ ] **Step 5: Package and inspect**

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home mvn -DskipTests package`, then run `git diff --check` on the touched files and confirm the JAR contains `ReplayIssueMailService.class`.
