# Error code catch analysis implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for independent type resolution and scan orchestration, with root-owned Maven execution and final review.

**Goal:** Exclude error-code throw sites proven handled within the same execution body while retaining uncertain or rethrown codes.

**Architecture:** Resolve exception factory types against configured source roots with JavaParser SymbolSolver. Analyze enclosing try/catch/finally before storing effective throws. Carry complete-scan evidence for legitimate empty materialization.

**Tech Stack:** Java 17, JavaParser 3.25.10, Spring JDBC, Neo4j, JUnit 5.

**Spec:** /Users/wangshanhe/.codex/worktrees/reopen-statistics-design/obsidian/01 Engineering/axon-link-server/错误码扫描-设计.md, section 14.

## Global Constraints

- Preserve unknown type/flow results, original rethrows and exception aliases.
- Keep existing result schemas and HTTP/Excel shapes.
- Isolate lambda, method, constructor, local/anonymous class execution boundaries.
- Allow empty writes only after a complete scan with positive raw hits entirely filtered.
- Root runs all Maven commands using Java 17. Business sources are unavailable locally.

## Review Focus

- Earlier catch of unknown compatibility must prevent unsafe selection of a later catch.
- Declared base exception return types may be intercepted by a narrower catch.
- finally and nested handlers must not cause a live rethrow to disappear.
- Alias/unknown calls with a caught exception must preserve it.
- Empty or partially failed source scans must preserve prior results.

### Task 1: Exception type matching

**Files:** pom.xml, scan/ErrorCodeExceptionTypeResolver.java, scan/ErrorCodeExceptionTypeResolverTest.java.

**Interfaces:** static JavaParser createParser(List<Path> sourceRoots); Match match(ThrowStmt statement, CatchClause clause); Match values MATCH, NO_MATCH, UNKNOWN.

- [x] Write failing tests for source factory types, hierarchy, multi-catch, unknown and narrower catch.
- [x] Root run the tests and confirm failure.
- [x] Add aligned symbol-solver dependency and implement per-scan parser/type matching.
- [x] Root run tests and confirm success.

### Task 2: Local propagation filtering

**Files:** scan/CaughtErrorCodeAnalyzer.java, scan/ThrowStmtVisitor.java, scan/ThrowStmtVisitorTest.java.

**Interfaces:** boolean isHandled(ThrowStmt statement); visitor getRawThrowCount() counts recognized sites before filtering.

- [x] Add failing regression tests for consume/replace/rethrow/unknown and execution boundaries.
- [x] Root verify failures, implement AST propagation checks, then verify success.

### Task 3: Complete scans and legitimate empty results

**Files:** scan/ErrorCodeScanService.java, attribution/ErrorCodeAttributionService.java, corresponding tests.

**Interfaces:** materializeTransactionErrorCodes(boolean allowEmpty) with existing no-argument method preserving its guard; scanner uses per-run parser, raw counts and completeness.

- [x] Add failing tests for full filtering, scan-to-export empty results, parsing failure and legacy guards.
- [x] Root verify failures, implement scan outcome propagation, then verify success.

### Task 4: Verification and delivery

- [x] Run all error-code tests and normal export entry points.
- [x] Run full backend suite and compare existing 40 replay-related baseline failures.
- [x] Fresh independent review, fix correctness findings and repeat verification.
- [x] Update design and plan with final evidence, commit local implementation and integrate into user's working checkout.


## Verification evidence

- Java 17 error-code and controller suites passed all 153 tests.
- Full backend suite ran 1383 tests with 1337 passing, 6 skipped, 9 failures and 31 errors. The 40 failing/error cases exactly match the existing ReplayDatabaseIssueTest and ReplayDatabaseIssueControllerTest baseline by class, method, kind and exception type.
- Verified source-to-H2-to-Excel empty and replacement flows. The screenshot-shaped E1654 case retains only the catch position in both export entry points, count and distinctCount both equal 1.
- Independent review identified finally overriding an escaped caught object and source-type ambiguity through inherited factories. Regression tests were observed RED and passed after the fixes, including intermediate receivers, implicit inherited members and var inference.
- Type analysis covers configured source roots and JDK types. Business types available only in dependency JARs remain UNKNOWN and are retained. Cross-method caller handling remains on the existing method-reachability attribution.
- Both standard source root discovery and oversized-file compatibility are preserved. Parse/read failures now prevent partial result overwrite.
