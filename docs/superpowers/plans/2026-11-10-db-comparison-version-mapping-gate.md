# Version generation domain mapping gate implementation plan

**Goal:** Reject missing production domain mappings before a database comparison version is persisted.

**Design:** Obsidian 回放数据库比对字段登记-系统设计.md, 生成版本领域映射门禁 section dated 2026-11-10.

**Scope:** Reuse existing generator mapping and VERSION_GATE_BLOCKED response. Add DOMAIN_MAPPING_MISSING for readable generation errors. Preserve existing version validation, snapshot and SQL generation entry points.

## Task 1 — Shared backend gate

- [x] Add failing service tests for missing personnel mapping, blank/unknown domains, aggregated errors and zero writes to all version tables.
- [x] Add script-generator null/empty domain regression and mapping HTTP 422 response coverage.
- [x] Run targeted Java 17 tests and confirm the expected failure.
- [x] Extract package-level domainMappingError(String), invoke it in generator and version gate, add dedicated metadata status.
- [x] Verify supported domains produce both SQL targets and existing guards remain covered.

## Task 2 — Frontend feedback

- [x] Add user-flow regression for all mapping errors, retained current version and successful retry.
- [x] Observe RED, add the readable gate label, verify targeted and full frontend tests.

## Task 3 — Delivery verification

- [x] Independent review of gate authority and persistence boundary.
- [x] Run full backend tests and compare exact existing 40-case baseline.
- [x] Build frontend into backend static resources and verify build output.
- [x] Commit and integrate both source repositories locally, synchronize design via remote PR.


## Verification evidence

- Observed RED with 6 expected assertion failures and 1 missing enum error across 73 targeted tests.
- Database-comparison package passed all 255 tests, including current personnel mapping, blank/unknown domains, complete error aggregation, zero version snapshot writes, both SQL targets and HTTP 422 DTO coverage.
- Frontend passed 630 tests across 35 files. Generation failure retains the current version and token, renders all mapping errors, and permits successful retry.
- Full backend suite ran 1395 tests with 1349 passing, 6 skipped, 9 failures and 31 errors. All 40 failures/errors exactly match the existing ReplayDatabaseIssueTest and ReplayDatabaseIssueControllerTest baseline by test identity, kind and exception type.
- Independent read-only review found no actionable P1/P2 regressions.
- Frontend production build succeeded and refreshed the backend static directory.
