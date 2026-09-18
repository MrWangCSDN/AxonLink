# Replay Database Comparison Import Owner And Error Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make initialization import resolve owners by Chinese name or exact `姓名(username)`, let the last non-empty owner win per table, and export the complete validation error list as `.xlsx`.

**Architecture:** Keep parsing and owner resolution in the existing backend services, with every non-empty row still validated before the atomic import. Add a focused frontend workbook helper that dynamically loads SheetJS, maps the six displayed error columns, and downloads one workbook without adding a backend endpoint.

**Tech Stack:** Java 17, Spring JDBC, Apache POI, JUnit 5, Vue 3, Vitest, SheetJS (`xlsx`)

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对字段登记-初始化导入负责人与错误导出-系统设计.md`

## Global Constraints

- G 列为空时允许导入。
- 有中文或英文括号时按 `姓名(username)` 对启用人员做姓名与 username 联合精确匹配。
- 无括号时只按中文姓名精确匹配，不回退查询 username 或工号。
- 同表所有非空负责人单元格都必须校验，最终使用 Excel 行顺序中的最后一个非空负责人；末尾空白不清空。
- 错误清单导出全部六列，Sheet 名固定为 `错误清单`，文件名为 `初始化导入错误清单-yyyyMMdd-HHmmss.xlsx`。
- 不新增后端导出接口。

---

### Task 1: Excel Parser Last-Owner Semantics

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonExcelParserTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonExcelParser.java`

**Interfaces:**
- Consumes: Excel rows with C/E/G fixed columns.
- Produces: `ParsedTable.reviserInput()` containing the last non-empty G-column value.

- [ ] **Step 1: Write failing parser tests**

Replace the conflicting-owner assertion with tests proving that different owners on one table create no owner conflict, the last non-empty value wins, and a trailing blank does not clear it.

- [ ] **Step 2: Run parser tests and verify RED**

Run: `mvn -Dtest=ReplayDatabaseComparisonExcelParserTest test`

Expected: FAIL because `toParsed()` currently selects the first non-empty owner and `addConflicts()` still reports owner inconsistency.

- [ ] **Step 3: Implement minimal parser change**

Remove only the `同一表的负责人不一致` conflict branch and scan rows in reverse order for the first non-empty `reviserInput`.

- [ ] **Step 4: Run parser tests and verify GREEN**

Run: `mvn -Dtest=ReplayDatabaseComparisonExcelParserTest test`

Expected: PASS.

### Task 2: Owner Resolver Input Rules

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonReviserResolverTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonReviserResolver.java`

**Interfaces:**
- Consumes: blank, Chinese name, or `姓名(username)` text.
- Produces: `Resolution` containing the canonical active `SysUser`, or a row-level failure reason.

- [ ] **Step 1: Write failing resolver tests**

Assert that `张三` resolves by exact real name, both bracket styles resolve by exact name plus username, while `c-zhangs` and `10001` are treated as names and return `人员不存在或已停用`.

- [ ] **Step 2: Run resolver tests and verify RED**

Run: `mvn -Dtest=ReplayDatabaseComparisonReviserResolverTest test`

Expected: FAIL because plain text currently falls back to username and employee number.

- [ ] **Step 3: Implement minimal resolver change**

Keep the parenthesized exact-match branch, then query only `findActiveByExactRealName(normalized)` for all non-parenthesized input.

- [ ] **Step 4: Run resolver tests and verify GREEN**

Run: `mvn -Dtest=ReplayDatabaseComparisonReviserResolverTest test`

Expected: PASS.

### Task 3: Import Service End-to-End Owner Validation

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonImportServiceTest.java`

**Interfaces:**
- Consumes: parser output and `ReplayDatabaseComparisonReviserResolver` results.
- Produces: one atomic import using the final valid owner while retaining errors from every invalid non-empty row.

- [ ] **Step 1: Write import service regression tests**

Add one workbook with two valid different owners for the same table and assert the second owner is stored; add one workbook whose earlier owner is invalid but later owner is valid and assert the entire import still fails with the earlier row in the error list.

- [ ] **Step 2: Run import service tests and verify behavior**

Run: `mvn -Dtest=ReplayDatabaseComparisonImportServiceTest test`

Expected: the final-owner test passes after Task 1; the invalid-earlier-row test must fail if the service validates only the aggregated owner.

- [ ] **Step 3: Validate every non-empty owner row before preparing the table**

Resolve each distinct non-empty row input, append errors to that row, and use the resolution corresponding to `ParsedTable.reviserInput()` for persistence only when every row is valid.

- [ ] **Step 4: Run import service tests and verify GREEN**

Run: `mvn -Dtest=ReplayDatabaseComparisonImportServiceTest test`

Expected: PASS with no partial writes.

### Task 4: Frontend Error Workbook Helper

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/replay/initialImportErrorWorkbook.js`
- Create: `/Users/java/axon-link-frontend/src/components/replay/initialImportErrorWorkbook.spec.js`
- Modify: `/Users/java/axon-link-frontend/package.json`
- Modify: `/Users/java/axon-link-frontend/package-lock.json`

**Interfaces:**
- Produces: `exportInitialImportErrors(errors, now = new Date()) => Promise<void>`.
- Workbook columns: `Sheet`, `行号`, `表英文名`, `字段英文名`, `负责人`, `原因`.

- [ ] **Step 1: Add SheetJS dependency**

Run: `npm install xlsx`

- [ ] **Step 2: Write failing helper tests**

Mock dynamic `import('xlsx')` functions and assert all errors are mapped in order, the sheet is named `错误清单`, and the generated filename uses `初始化导入错误清单-yyyyMMdd-HHmmss.xlsx`.

- [ ] **Step 3: Run helper tests and verify RED**

Run: `npx vitest run src/components/replay/initialImportErrorWorkbook.spec.js`

Expected: FAIL because the helper does not exist.

- [ ] **Step 4: Implement the workbook helper**

Create the six-column row objects, dynamically import SheetJS inside the export function, append one worksheet, and call `writeFile` with the formatted local timestamp.

- [ ] **Step 5: Run helper tests and verify GREEN**

Run: `npx vitest run src/components/replay/initialImportErrorWorkbook.spec.js`

Expected: PASS.

### Task 5: Initialization Dialog Export Action

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayDatabaseComparisonPage.spec.js`

**Interfaces:**
- Consumes: `initialImportErrors`.
- Calls: `exportInitialImportErrors(initialImportErrors.value)`.

- [ ] **Step 1: Write failing page test**

After a rejected import containing multiple errors, assert an `export-initial-import-errors` button is visible and invokes the helper with the complete error array.

- [ ] **Step 2: Run page test and verify RED**

Run: `npx vitest run src/components/replay/ReplayDatabaseComparisonPage.spec.js`

Expected: FAIL because the export button is absent.

- [ ] **Step 3: Add the export action and concise styling**

Render the button only when errors exist, place it beside `错误清单（N）`, and disable it while an export is already in progress.

- [ ] **Step 4: Run page test and verify GREEN**

Run: `npx vitest run src/components/replay/ReplayDatabaseComparisonPage.spec.js`

Expected: PASS.

### Task 6: Regression Verification

**Files:**
- Verify only.

**Interfaces:**
- Produces: evidence that backend import behavior and frontend build remain stable.

- [ ] **Step 1: Run focused backend tests**

Run: `mvn -Dtest=ReplayDatabaseComparisonExcelParserTest,ReplayDatabaseComparisonReviserResolverTest,ReplayDatabaseComparisonImportServiceTest test`

- [ ] **Step 2: Run complete backend tests**

Run: `mvn test`

- [ ] **Step 3: Run complete frontend tests**

Run: `npx vitest run --exclude '**/.worktrees/**'`

- [ ] **Step 4: Build the frontend**

Run: `npm run build`

- [ ] **Step 5: Inspect diffs**

Run in each repository: `git diff --check && git status --short`

Expected: no whitespace errors and only intended files plus previously existing unrelated backend changes remain.
