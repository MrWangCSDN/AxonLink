# Replay Report File Names Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename query/accounting daily and weekly Excel files from batch-number-based names to business-readable names whose dates come from the selected batch numbers.

**Architecture:** Add one stateless filename utility that parses the family and date from standard `RPT`/`DZ` batch numbers. Route snapshot creation, cached snapshot reads, controller download headers, current-report mail attachments, and additional generated-daily attachments through that utility so new and historical snapshots expose the same canonical name without a database migration.

**Tech Stack:** Java 17, Spring Boot 3, JUnit 5, Mockito, MockMvc, Maven

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section “日报与周报文件名规范（2026-09-16）”

## Global Constraints

- `RPT` maps to `查询`; `DZ` maps to `账务`.
- Daily names are `<查询|账务>日报-yyyyMMdd.xlsx`.
- Weekly names are `<查询|账务>周报(yyyyMMdd-yyyyMMdd).xlsx`.
- Dates come only from batch numbers, never from the system clock.
- Historical snapshot BLOBs require no migration; output boundaries recalculate canonical names.
- Excel contents, mail subjects, batch identifiers, snapshot timestamps, and database keys remain unchanged.
- Use JDK 17 at `/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home`.
- Do not commit or revert unrelated existing workspace changes.

---

### Task 1: Canonical Filename Utility

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayReportFileNames.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayReportFileNamesTest.java`

**Interfaces:**
- Produces: `ReplayReportFileNames.daily(String batchNo): String`
- Produces: `ReplayReportFileNames.weekly(String startBatchNo, String endBatchNo): String`

- [ ] **Step 1: Write failing utility tests**

```java
assertEquals("查询日报-20260916.xlsx", ReplayReportFileNames.daily("RPT20260916-102753-6470"));
assertEquals("账务日报-20260916.xlsx", ReplayReportFileNames.daily("DZ20260916-01"));
assertEquals("查询周报(20260909-20260916).xlsx",
        ReplayReportFileNames.weekly("RPT20260909-01", "RPT20260916-102753-6470"));
assertEquals("账务周报(20260909-20260916).xlsx",
        ReplayReportFileNames.weekly("DZ20260909-01", "DZ20260916-01"));
assertThrows(IllegalArgumentException.class,
        () -> ReplayReportFileNames.weekly("RPT20260909-01", "DZ20260916-01"));
```

- [ ] **Step 2: Run the utility test and verify RED**

Run:

```bash
JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' \
PATH="$JAVA_HOME/bin:$PATH" mvn -Dtest=ReplayReportFileNamesTest test
```

Expected: compilation failure because `ReplayReportFileNames` does not exist.

- [ ] **Step 3: Implement the minimal parser and formatter**

Use a private pattern equivalent to `^(RPT|DZ)(\d{8})(?:-.+)$`, map the family to the Chinese label, trim inputs, reject malformed inputs, and reject cross-family weekly ranges.

- [ ] **Step 4: Run the utility test and verify GREEN**

Run the command from Step 2. Expected: all utility tests pass.

### Task 2: Daily Report Snapshot, Download, and Mail Names

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportMailService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportMailServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentServiceTest.java`

**Interfaces:**
- Consumes: `ReplayReportFileNames.daily(String batchNo)`
- Produces: canonical daily filenames in persisted new snapshots, browser headers, current mail attachments, and additional generated-daily attachments.

- [ ] **Step 1: Update assertions first**

Add or change tests so a new RPT snapshot stores `查询日报-20260902.xlsx`, a DZ download returns the UTF-8 encoded `账务日报-20260902.xlsx`, and an old snapshot named `RPT20260902-01日报.xlsx` is exposed to mail and additional-attachment flows as `查询日报-20260902.xlsx`.

- [ ] **Step 2: Run daily-focused tests and verify RED**

```bash
JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' \
PATH="$JAVA_HOME/bin:$PATH" mvn -Dtest=ReplayIssueDailyReportServiceTest,ReplayIssueControllerTest,ReplayDailyReportMailServiceTest,ReplayReportMailAttachmentServiceTest test
```

Expected: filename assertions fail with legacy names.

- [ ] **Step 3: Route all daily boundaries through the utility**

Replace direct `batchNo + "日报.xlsx"` concatenation. When mail services or additional attachment resolution load historical snapshots, keep snapshot content and size but use `ReplayReportFileNames.daily(snapshot.batchNo())` for user-visible metadata and MIME attachment names.

- [ ] **Step 4: Run daily-focused tests and verify GREEN**

Run the command from Step 2. Expected: all daily-focused tests pass.

### Task 3: Weekly Report Snapshot, Cached Download, and Mail Names

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `ReplayReportFileNames.weekly(String startBatchNo, String endBatchNo)`
- Produces: canonical weekly filenames for newly generated snapshots, cached historical snapshots, browser headers, mail views, and MIME attachments.

- [ ] **Step 1: Update assertions first**

Expect `查询周报(20260901-20260908).xlsx` for RPT and `账务周报(20260901-20260908).xlsx` for DZ. Add a cached old snapshot case proving `generate` returns a copied snapshot with the canonical filename while preserving content and metadata.

- [ ] **Step 2: Run weekly-focused tests and verify RED**

```bash
JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' \
PATH="$JAVA_HOME/bin:$PATH" mvn -Dtest=ReplayWeeklyReportServiceTest,ReplayWeeklyReportMailServiceTest,ReplayIssueControllerTest test
```

Expected: weekly filename assertions fail with `<endBatchNo>周报.xlsx` or the historical stored name.

- [ ] **Step 3: Canonicalize weekly snapshots at creation and read boundaries**

Use the utility in `buildReport`. For cached snapshots and mail contexts, create a new `ReplayWeeklyReportSnapshot` with the same start/end identifiers, content type, bytes, size, and generated time but the canonical filename.

- [ ] **Step 4: Run weekly-focused tests and verify GREEN**

Run the command from Step 2. Expected: all weekly-focused tests pass.

### Task 4: Regression Verification

**Files:**
- Verify all modified source and test files.

**Interfaces:**
- Consumes: all behavior from Tasks 1–3.
- Produces: verified release-ready filename behavior.

- [ ] **Step 1: Run the complete backend suite**

```bash
JAVA_HOME='/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home' \
PATH="$JAVA_HOME/bin:$PATH" mvn test
```

Expected: zero failures and zero errors.

- [ ] **Step 2: Check patch hygiene**

```bash
git diff --check
git diff --stat -- src/main/java/com/axonlink/ai/replay src/test/java/com/axonlink/ai/replay
```

Expected: no whitespace errors; changes remain limited to filename generation and its tests.
