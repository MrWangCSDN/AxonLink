# Replay Occurrence Count Label and Width Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the replay issue count column to “出现笔数” on the page and Excel export, and make its page width equal to the 112px review-status column.

**Architecture:** Keep the internal field and sorting API unchanged. Only the presentation metadata in Vue and the exported workbook header change; the Excel import parser continues accepting the legacy source header.

**Tech Stack:** Vue 3, Vitest, Java 17, Spring MVC, Apache POI, JUnit 5

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Page and Excel export display “出现笔数”.
- The page column width is exactly `112px`, matching “审核状态”.
- `affected_transaction_count`, `affectedTransactionCountOrder`, and the three-state sort behavior do not change.
- Excel import continues accepting “该问题出现在的交易笔数”.
- Preserve unrelated uncommitted changes; do not commit, merge, push, reset, or clean either repository.

---

### Task 1: Rename and resize the page column

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`

**Interfaces:**
- Consumes: existing `columns` metadata and `affected_transaction_count` sort control.
- Produces: visible label `出现笔数` and width `112px`; internal key and sort test ID remain unchanged.

- [ ] **Step 1: Write the failing component expectation**

Change `visibleColumnLabels` to contain `出现笔数`, then extend the existing column-width assertions:

```js
expect(columnWidths[visibleColumnLabels.indexOf('出现笔数')])
  .toBe(columnWidths[visibleColumnLabels.indexOf('审核状态')])
expect(columnWidths[visibleColumnLabels.indexOf('出现笔数')]).toContain('width: 112px')
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: the header list differs because production still renders “该问题出现在的交易笔数”.

- [ ] **Step 3: Apply the minimal Vue metadata change**

Change only this entry:

```js
['affected_transaction_count', '出现笔数', '112px'],
```

Do not change the sort state, request parameter, icon, accessibility label, or data field.

- [ ] **Step 4: Run the focused component test and verify GREEN**

Run the Step 2 command. Expected: all `ReplayIssuePage` tests pass.

---

### Task 2: Rename the Excel export header and rebuild

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Verify generated output: `src/main/resources/static`
- Update: `/Users/java/obsidian/log.md`

**Interfaces:**
- Consumes: `GET /api/ai/parallel-replay/issues/export`.
- Produces: workbook column 24 header `出现笔数`; import parser header remains unchanged.

- [ ] **Step 1: Write the failing export assertion**

In `affectedTransactionCountOrderAppliesToListAndExport`, assert:

```java
assertEquals("出现笔数", workbook.getSheetAt(0).getRow(0).getCell(24).getStringCellValue());
assertEquals("10", workbook.getSheetAt(0).getRow(1).getCell(24).getStringCellValue());
```

- [ ] **Step 2: Run the focused backend test and verify RED**

Run:

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn '-Dtest=ReplayIssueControllerTest#affectedTransactionCountOrderAppliesToListAndExport' test
```

Expected: header assertion fails with actual value “该问题出现在的交易笔数”.

- [ ] **Step 3: Apply the minimal export header change**

In the export `headers` array, replace only the display string with `出现笔数`. Do not alter column order or values.

- [ ] **Step 4: Verify focused and full relevant tests**

Run:

```bash
cd /Users/java/axon-link-frontend && npm test
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn '-Dtest=ReplayIssueControllerTest#affectedTransactionCountOrderAppliesToListAndExport' test
```

Expected: frontend tests and the focused backend test pass with zero failures.

- [ ] **Step 5: Rebuild backend static assets and compile**

Run:

```bash
cd /Users/java/axon-link-frontend && npm run build
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
/opt/homebrew/bin/mvn -DskipTests compile
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/axon-link-server diff --check
```

Expected: production build, Java compilation, and both diff checks succeed.

- [ ] **Step 6: Append implementation evidence**

Append one `[IMPL]` line to `/Users/java/obsidian/log.md` with exact fresh test totals and build results. Do not change the import template header or parser.
