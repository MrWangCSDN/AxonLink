# Error Code XML Export Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Load every workspace `*.error.xml` definition into memory and append resolved `errorCode` and `message` values to both error-code Excel exports.

**Architecture:** Add a focused `ErrorDefinitionIndex` Spring component that discovers and safely parses XML files once at startup, then atomically publishes immutable exact-key and suffix-key maps. `ErrorCodeExportService` queries this index while writing each row; persistence DTOs and database tables remain unchanged.

**Tech Stack:** Java 17, Spring Boot 3.1, JDK DOM XML APIs, Apache POI SXSSF, JUnit 5, Mockito.

## Global Constraints

- Scan only `project.workspace-roots`; ignore `.git`, `target`, and `build` directory trees.
- Exact lookup: two-segment scope `CmError.Comm` plus code `E0004` resolves `CmError.Comm.E0004`.
- Compatibility lookup: one-segment scope `Aplt` plus code `E0001` resolves unique suffix `Aplt.E0001`, including XML key `ApError.Aplt.E0001`.
- Empty scope, scope with more than two segments, blank code, or dotted code is not transformed and returns no match.
- Conflicting duplicate keys are ambiguous and return no match; file traversal order must never select a winner.
- Disable DTD and external entities; one malformed XML file must not prevent other files from loading.
- Missing definitions leave both added Excel cells blank.
- Add `新错误码` and `新错误描述` immediately after `错误类.分类` in both exports.
- Do not add database columns, migrations, dependencies, or changes to `ErrorCodeScanService`.
- Preserve all pre-existing uncommitted user changes; do not stage or commit them.

## File Structure

- Create `src/main/java/com/axonlink/ai/daoindex/errorcode/definition/ErrorDefinitionIndex.java`: XML discovery, secure parsing, immutable snapshot construction, ambiguity tracking, and lookup API.
- Create `src/test/java/com/axonlink/ai/daoindex/errorcode/definition/ErrorDefinitionIndexTest.java`: XML parsing, lookup normalization, ambiguity, exclusions, and malformed-file coverage.
- Modify `src/main/java/com/axonlink/ai/daoindex/errorcode/export/ErrorCodeExportService.java`: inject the index, add two headers, and write resolved values.
- Modify `src/test/java/com/axonlink/ai/daoindex/errorcode/export/ErrorCodeExportServiceTest.java`: inject a mocked index and assert header/value/blank behavior for both workbook variants.
- Existing design source of truth: `/Users/java/obsidian/01 Engineering/axon-link-server/错误码扫描-设计.md`.

---

### Task 1: In-Memory Error Definition Index

**Files:**
- Create: `src/main/java/com/axonlink/ai/daoindex/errorcode/definition/ErrorDefinitionIndex.java`
- Test: `src/test/java/com/axonlink/ai/daoindex/errorcode/definition/ErrorDefinitionIndexTest.java`

**Interfaces:**
- Consumes: Spring property `project.workspace-roots` as a comma-separated string.
- Produces: `Optional<ErrorDefinition> lookup(String errorScope, String errorCode)`.
- Produces: immutable value `ErrorDefinition(String errorCode, String message)`.
- Produces: `void reload()` for startup loading and deterministic tests.

- [x] **Step 1: Write failing exact and compatibility lookup tests**

Create a temporary workspace containing a namespace-qualified XML file:

```java
@TempDir Path tempDir;

@Test
void loadsExactAndSingleScopeCompatibilityKeys() throws Exception {
    writeErrorXml(tempDir.resolve("project/src/main/resources/CmError.error.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <errorConf xmlns="urn:test" id="CmError">
              <errors id="Comm">
                <error id="E0004" errorCode="NEW-0004" message="账户状态错误"/>
              </errors>
              <errors id="Aplt">
                <error id="E0001" errorCode="NEW-0001" message="应用错误"/>
              </errors>
            </errorConf>
            """);

    ErrorDefinitionIndex index = new ErrorDefinitionIndex(tempDir.toString());
    index.reload();

    assertEquals("NEW-0004", index.lookup("CmError.Comm", "E0004").orElseThrow().errorCode());
    assertEquals("应用错误", index.lookup("Aplt", "E0001").orElseThrow().message());
}
```

- [x] **Step 2: Run the focused test and verify the red state**

Run: `mvn -Dtest=ErrorDefinitionIndexTest test`

Expected: test compilation fails because `ErrorDefinitionIndex` does not exist.

- [x] **Step 3: Add lookup-boundary and ambiguity tests**

Cover these assertions explicitly:

```java
assertTrue(index.lookup("", "E0001").isEmpty());
assertTrue(index.lookup("A.B.C", "E0001").isEmpty());
assertTrue(index.lookup("Aplt", "E.0001").isEmpty());
assertTrue(index.lookup("Missing.Scope", "E0001").isEmpty());
```

Create two XML files with the same suffix `Aplt.E0001` but different `errorCode` or `message`; assert the suffix lookup is empty. Also create one malformed XML file beside one valid file and assert the valid definition still loads. Put a conflicting XML under `target/classes` and assert it is ignored.

- [x] **Step 4: Implement secure discovery, parsing, and immutable snapshots**

Implement the public boundary:

```java
@Component
public final class ErrorDefinitionIndex {
    private final String workspaceRoots;
    private volatile Snapshot snapshot = Snapshot.empty();

    public ErrorDefinitionIndex(@Value("${project.workspace-roots:}") String workspaceRoots) {
        this.workspaceRoots = workspaceRoots == null ? "" : workspaceRoots;
    }

    @PostConstruct
    public void reload() {
        snapshot = buildSnapshot();
    }

    public Optional<ErrorDefinition> lookup(String errorScope, String errorCode) {
        return snapshot.lookup(errorScope, errorCode);
    }

    public record ErrorDefinition(String errorCode, String message) {}
}
```

Use `Files.walk(root)` and reject any path containing a segment equal to `.git`, `target`, or `build`. Sort discovered paths before parsing for stable logs, while keeping ambiguity behavior independent of ordering.

Configure `DocumentBuilderFactory` with namespace awareness, `disallow-doctype-decl=true`, external general/parameter entities disabled, external DTD loading disabled, XInclude disabled, entity expansion disabled, and `XMLConstants.ACCESS_EXTERNAL_DTD/SCHEMA` set to empty strings. Traverse direct `errors` children of `errorConf`, then direct `error` children of each `errors` element.

Build candidate maps plus ambiguity sets. When a duplicate key has an unequal `ErrorDefinition`, remove it from the candidate map and retain it in the ambiguity set. Publish `Map.copyOf` snapshots only after all files have been processed.

- [x] **Step 5: Run index tests and verify the green state**

Run: `mvn -Dtest=ErrorDefinitionIndexTest test`

Expected: all `ErrorDefinitionIndexTest` methods pass with zero failures and errors.

---

### Task 2: Enrich Both Excel Exports

**Files:**
- Modify: `src/main/java/com/axonlink/ai/daoindex/errorcode/export/ErrorCodeExportService.java`
- Modify: `src/test/java/com/axonlink/ai/daoindex/errorcode/export/ErrorCodeExportServiceTest.java`

**Interfaces:**
- Consumes: `ErrorDefinitionIndex.lookup(String errorScope, String errorCode)` from Task 1.
- Preserves: `ExportFile exportSingle(String txId)` and `ExportFile exportAll(String domainKey)`.
- Produces: two additional cells per exported row, immediately after `错误类.分类`.

- [x] **Step 1: Update export tests first**

Inject a mocked index into `new ErrorCodeExportService(dao, index)`. For a matched row:

```java
when(index.lookup("CmError.Brch", "E0003"))
        .thenReturn(Optional.of(new ErrorDefinition("NEW-E0003", "机构号不存在")));
```

Assert both export shapes:

```java
assertEquals("新错误码", header.getCell(scopeColumn + 1).getStringCellValue());
assertEquals("新错误描述", header.getCell(scopeColumn + 2).getStringCellValue());
assertEquals("NEW-E0003", data.getCell(scopeColumn + 1).getStringCellValue());
assertEquals("机构号不存在", data.getCell(scopeColumn + 2).getStringCellValue());
```

For `Optional.empty()`, assert both data cells contain empty strings. Keep the empty-workbook test and assert the expanded headers are present without a row 1.

- [x] **Step 2: Run export tests and verify the red state**

Run: `mvn -Dtest=ErrorCodeExportServiceTest test`

Expected: compilation fails because the service constructor and headers do not yet expose the new dependency and columns.

- [x] **Step 3: Implement minimal export enrichment**

Inject `ErrorDefinitionIndex` in the constructor. Add `新错误码`, `新错误描述` after `错误类.分类` in `SINGLE_HEADERS` and `ALL_HEADERS`.

In `writeRow`, resolve once per row and write both values before `throw_text`:

```java
ErrorDefinition definition = definitions.lookup(d.getErrorScope(), d.getErrorCode()).orElse(null);
r.createCell(c++).setCellValue(definition == null ? "" : nv(definition.errorCode()));
r.createCell(c++).setCellValue(definition == null ? "" : nv(definition.message()));
```

Do not add fields to `TxErrorCodeRow`; XML values are transient export enrichment.

- [x] **Step 4: Run export tests and verify the green state**

Run: `mvn -Dtest=ErrorCodeExportServiceTest test`

Expected: all export tests pass with zero failures and errors.

- [x] **Step 5: Run both focused suites together**

Run: `mvn -Dtest=ErrorDefinitionIndexTest,ErrorCodeExportServiceTest test`

Expected: both suites pass and generated workbooks can be reopened by `XSSFWorkbook`.

---

### Task 3: Regression and Requirements Verification

**Files:**
- Verify: all files listed above.
- Verify: `/Users/java/obsidian/01 Engineering/axon-link-server/错误码扫描-设计.md` matches the implementation.

**Interfaces:**
- Consumes: completed Tasks 1 and 2.
- Produces: fresh test/build evidence and a scoped diff ready for user review.

- [x] **Step 1: Run the complete error-code module tests**

Run: `mvn -Dtest='com.axonlink.ai.daoindex.errorcode.**' test`

Expected: zero failures and errors across scan, attribution, DAO, controller support, index, and export tests.

- [x] **Step 2: Run compile/package verification without skipping tests**

Run: `mvn test`

Expected: Maven exits 0 with zero test failures and errors.

- [x] **Step 3: Inspect the final diff and whitespace**

Run: `git diff --check`

Run: `git status --short`

Confirm only the new plan/index/test and intended export/test edits belong to this task. Identify pre-existing user modifications separately; do not stage, revert, or include them in task completion claims.

- [x] **Step 4: Reconcile every requirement**

Verify from tests and implementation: exact two-segment lookup, single-segment suffix compatibility, over-two-segment rejection, ambiguity blanking, secure XML parsing, malformed-file isolation, excluded build directories, header order, matched values, unmatched blanks, and both single/all export formats.

- [x] **Step 5: Leave the worktree uncommitted for user review**

Do not run `git add` or `git commit`: this shared worktree already contains unrelated user changes, and the user did not request a commit. Report the task-owned files and verification evidence explicitly.
