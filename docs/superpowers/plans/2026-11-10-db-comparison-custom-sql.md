# Custom SQL comparison registration implementation plan

**Goal:** Add working single-table custom SQL comparison registrations to the existing page, including snapshots, exports, and issue identity/permissions.

**Approved spec:** Obsidian 5afcefb, 回放数据库比对字段登记-自定义SQL three documents.

**Baselines:** backend 04a3677, frontend 084fdec. Java 17 full backend has 40 known failures/errors in ReplayDatabaseIssueTest and ReplayDatabaseIssueControllerTest. Frontend 630 passing tests.

## Binding interfaces

- `ReplayDbCompareSqlColumn(String columnName, String columnLabel, String dataType, int ordinalPosition)`.
- `ReplayDbCompareCustomSql(String sql, String sourceTableName, List<ReplayDbCompareSqlColumn> resultColumns)`.
- `ReplayDbCompareSqlAnalysis(String sql, String schemaName, String sourceTableName, String sourceTableComment, List<ReplayDbCompareSqlColumn> resultColumns, List<String> candidateKeyColumns)`.
- Existing registration, list item, version item and state records add nullable `customSql`. Preserve old constructors and every copy method. JSON includes `registrationType` (`TABLE` or `CUSTOM_SQL`), `comparisonCode` (existing tableName), `sourceTableName` (physical anchor).
- Version summary adds `customSqlCount` with old constructors defaulting to zero. Global/list counts may add analogous counts without changing existing tableCount meaning (total comparison items).
- `ReplayDatabaseComparisonCustomSqlAnalyzer.analyze(String sql)` returns analysis.
- Analyzer `prepare(String sql, List<String> fieldNames, List<String> resultKeyColumns, Long compareLimit)` returns nested `PreparedSql(ReplayDbCompareSqlAnalysis analysis, List<ReplayDbCompareField> fields, ReplayDbCompareKeySelection comparisonKey)`.
- SQL keys use `ReplayDbCompareKeySelection("SQL_RESULT_KEY", "RESULT_KEY", orderedKeyColumns)`.
- SQL registration input fields are displayName, sourceTableName, sql, fieldNames, resultKeyColumns, version, compareLimit, partitionNum, sampleLimit.
- POST /custom-sql/analyze, POST /custom-sql, PUT /custom-sql/{id}; existing detail/delete/audit/search/version APIs return mixed registration types.

## Task 1 — Model and persistence foundation

- [x] Add regressions for old TABLE records, custom definition round trip/copy methods, same-source multiple SQL identities, version freeze and counts.
- [x] Observe RED, then extend DTOs and registration/version DAOs with backward-compatible constructors.
- [x] Add explicit result-library SQL migration(s) after V90 with registration_type/source_table_name/custom_sql/result_columns_json and version header custom_sql_count. Adapt test schema setup to real migration coverage.
- [x] Keep table_name as stable SQL_uuid identity for SQL records, current unique constraints and existing TABLE identities.
- [x] Add DAO custom update and mixed count support, use real source for personnel joins and type-aware FULL_TABLE/CUSTOM_SQL filtering.

## Task 2 — SQL analysis, selection and output

- [x] Add parser and JDBC metadata regressions for single-table SELECT/aggregation, alias folding and quoting, grouping, forbidden constructs, BASE schema and unavailability.
- [x] Observe RED, implement approved AST grammar and pure-function allowlist, SQL UTF-8 limit 65535 bytes and metadata timeout 5 seconds.
- [x] Canonical names match [a-z_][a-z0-9_$]*, max 100 characters, case-insensitive unique. Preserve actual labels for quoted outer projection.
- [x] Prepare ordered selected fields and nonempty unique result key columns. Reuse existing compare-limit validation.
- [x] Add SQL-mode branch to config generator, preserving TABLE output and generating explicit ordered projection from a derived query for both LEGACY/NEW.
- [x] Cover complete controller generation/download flow including NEW's internal base-script generation.

## Task 3 — Frontend integration

- [x] Add analyze/create/update APIs and custom editor with SQL textarea, parse action, ordered result fields/keys, compare limit, sample and permission-aware partition controls.
- [x] Add toolbar entry before version history, edit/view/delete lifecycle, SQL badges and accurate counts.
- [x] Add history SQL/keys display and readable audit labels. Keep registry item states unique via stable comparisonCode.
- [x] Test analysis/save failure retention, SQL edits invalidating parsed state, multiple SQL items, and normal TABLE compatibility.

## Task 4 — Backend lifecycle and result integration

- [x] Root adds create/update methods reusing existing actor/partition/sample/audit/transaction helpers, immutable source association and SQL_uuid identity.
- [x] Add analyzer injection without breaking existing constructors, domain/person source mapping, type-aware metadata and primary-key synchronization.
- [x] Extend state/hash/versions; TABLE hashes remain byte-compatible. SQL generation freezes fresh analysis with selected fields and keys and fails atomically.
- [x] Require a bound registration version for custom SQL result import. Resolve logical IDs to frozen source anchors for lists, personnel, audit/plan permissions and reports, including soft-deleted registrations.
- [x] Add API/in-memory DB regressions covering save→version→both exports→bound issue import/permissions.

## Task 5 — Verification and delivery

- [x] Root alone runs Maven with Java 17, targeted RED→GREEN and full suite compared to known baseline.
- [x] Run frontend targeted/full tests and build into backend, then package build and resource checks.
- [x] Independent read-only review, fix correctness findings with regressions.
- [x] Commit and integrate backend/frontend locally; update design implementation evidence and remote documentation PR.


## Verification evidence — 2026-11-11

- Frontend full suite: 36 files, 647 passing tests; Vite build succeeded into the backend static directory.
- Backend Java 17 full suite: 1462 tests, 1416 passing, 6 skipped, 9 failures and 31 errors. The 40 failing test identities and exception types exactly match the pre-feature baseline; no new failures. Full inventory: `docs/validation/2026-11-11-custom-sql.json`.
- Analyzer and generator additions were observed RED before implementation; lifecycle and version APIs were introduced after missing-interface RED. Import binding/source and custom filter regressions were observed RED before their fixes.
- Independent final review found invalid-SQL filter mapping and BASE Schema gate inconsistency. Both were reproduced RED and fixed; the final frontend/full backend runs cover them. Invalid SQL also has visible list/detail feedback.
- TABLE SQL output and hash have golden compatibility tests. Custom save, SQL-only audit, same-source multiple identities, immutable versions, both script targets, historical source permissions, soft deletion, missing/ambiguous binding and legacy unbound compatibility are covered.
- Upgrade requires V91 on the result database. Actual BASE/two-sided business SQL, production LEGACY DDL and runtime result-key uniqueness remain intranet acceptance items; see deployment instructions.
