# Replay Mail Managed Executor Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent replay collaboration email from failing with `Not provider of jakarta.mail.util.StreamProvider was found` when running from the Spring Boot executable JAR.

**Architecture:** Keep the existing asynchronous workflow and database status transitions, but submit the actual SMTP task to Spring's existing `diiBatchExecutor` instead of `CompletableFuture`'s common pool. Constructor injection makes the executor boundary explicit and testable while compatibility constructors remain available for isolated callers.

**Tech Stack:** Java 17, Spring Boot 3.1, Spring `ThreadPoolTaskExecutor`, Jakarta Mail, JUnit 5, Mockito, Maven.

**Spec:** Approved bug-fix choice A in the current task; no design-document update is required by `/Users/java/obsidian/工程设计宪法.md` for a bug fix.

## Global Constraints

- Do not change HTTP endpoints, request/response payloads, database schema, recipient rules, or frontend behavior.
- Preserve asynchronous sending and the existing `SENDING` → `SENT`/`FAILED` persistence flow.
- Use the existing bean named `diiBatchExecutor`; do not create another thread pool.
- Preserve existing constructors used by isolated tests or callers.
- Do not commit, reset, or clean the dirty worktree.

---

### Task 1: Route replay SMTP work through the managed executor

**Files:**
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMailServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMailService.java`

**Interfaces:**
- Consumes: Spring bean `@Qualifier("diiBatchExecutor") Executor`.
- Preserves: `ReplayIssueMailService.requestSend(ReplayIssueRow, List<String>)`.
- Produces: SMTP tasks submitted through `Executor.execute(Runnable)` after synchronous `SENDING` persistence.

- [x] **Step 1: Write the failing executor-boundary test**

Create a unit test with a capturing `Executor`. Assert `requestSend` inserts `SENDING` immediately, queues exactly one task on the supplied executor, and does not call `MailService.sendTextSync` until the captured task runs. After running it, assert the mail call and `markSent` occur.

- [x] **Step 2: Run the new test and verify RED**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -Dtest=ReplayIssueMailServiceTest test
```

Expected: compilation failure because `ReplayIssueMailService` does not yet accept an injected executor.

- [x] **Step 3: Inject and use `diiBatchExecutor`**

Add `Executor mailExecutor` to the Spring constructor using `@Qualifier("diiBatchExecutor")`. Replace `CompletableFuture.runAsync(() -> sendNow(...))` with `mailExecutor.execute(() -> sendNow(...))`. Retain compatibility constructors by delegating with a direct executor (`Runnable::run`) only for explicitly constructed isolated callers.

- [x] **Step 4: Run the new test and relevant mail tests**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -Dtest=ReplayIssueMailServiceTest,ReplayIssueMailDaoTest,ReplayIssueControllerTest test
```

Expected: all tests pass with zero failures and errors.

- [x] **Step 5: Package and inspect the executable JAR**

Run Java 17 `mvn -DskipTests package`, then verify the JAR contains `org.eclipse.angus:jakarta.mail`, the `ReplayIssueMailService` bytecode references `Executor.execute`, and no longer references `CompletableFuture.runAsync`.
