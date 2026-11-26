# Named script configuration datasource implementation plan

**Goal:** Let each named script configuration, including existing migration comparisons, save a GaussDB/PostgreSQL-compatible source connection and generate its frozen SQL from that source.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/数据库比对命名脚本配置-系统设计.md` and the sibling 数据模型/API接口 documents. The user confirmed the database family and directly requested this work.

**Architecture:** Keep independent connection metadata and encrypted password alongside each script configuration. Resolve one source per metadata preview/import/save, reuse the PostgreSQL catalog reader with explicit connection/schema, and close that source after the operation. Keep frozen-download/revision contracts unchanged.

## Global constraints

- Database family is GaussDB/PostgreSQL compatible. Existing openGauss driver registers `org.postgresql.Driver`; build a fixed `jdbc:postgresql:` URL from structured inputs. No new vendor adapter or arbitrary JDBC options.
- `SaveRequest.connection` has `{mode,host,port,database,schema,username,password}`. Modes are `CUSTOM`/`BASE`; new UI defaults CUSTOM with port5432/schema public. Port must be an exact integer1..65535. Password whitespace is significant.
- Old rows with null source are BASE. Missing connection in an update inherits current source. Explicit BASE clears custom source/secret. Never silently fall back after a CUSTOM connection failure.
- Detail.connection returns public fields and `passwordConfigured`; plaintext and ciphertext never appear in API responses, config_json, generated SQL, names, errors, or logs. Blank/missing password retains an existing password only for the same normalized host/port/database/username; schema changes may reuse it. Changed authentication endpoint requires a replacement password.
- V97 adds nullable `connection_json` and `password_ciphertext`. All source and script fields are persisted in the same insert or CAS update. No DB writes or revision changes after validation/connection failure.
- Use AES-256-GCM with fresh nonce, versioned ciphertext, and a32byte Base64 key from `replay-database-comparison.script-config-password-key` / `REPLAY_DB_SCRIPT_PASSWORD_KEY`. No embedded or generated-in-source default key. Legacy BASE/download paths remain usable without the key. Document migration and one-time deployment key configuration.
- Each CUSTOM operation uses one short-lived read-only source connection; connectTimeout10seconds, socket and catalog query timeouts30seconds. No per-config persistent pool, global registry/property mutation, or result-DB transaction held across source metadata/network work.
- POST `/script-configs/preview-metadata` takes JSON `{configurationId?,revision?,connection?,tables}` and returns `TableImportResult.tables`. Refresh uses empty per-table ignores to read current fields; frontend merges existing ignore choices and displays invalid choices for removal, while save performs full authoritative validation.
- Multipart `/script-configs/import-tables` accepts existing `file` plus optional connection JSON text, configurationId, revision. Credentials stay in bodies. Retained-secret lookup requires matching current id/revision. Existing no-source callers remain BASE-compatible.
- Preserve real ordered PK protection, global-PK retention, table/field order, ignore validation at save, five domains, max5000tables/10MiBExcel, metadata batches200, default hash limit64 with range1..256, NEW14columns, human-login permissions, and frozen SQL checksums/offline download. Schema chooses metadata only; existing unqualified generated query table names remain unchanged.

## Review focus

- Two configurations with the same table names but different source fields/schema must generate from their own source; no global-state race or fallback.
- Existing configurations can set/change a connection without reimporting Excel or losing table settings, and stale metadata responses cannot overwrite the latest source selection.
- Blank/whitespace/replaced passwords, endpoint changes, missing or wrong key, tampered ciphertext and API error paths do not leak credentials or accidentally clear them.
- Failed preview/save and stale revision leave source, frozen bytes, SHA, counts and revision unchanged; old frozen scripts download while source/key unavailable.
- Connections close on success/failure and all three catalog query kinds use the selected schema and timeout.

## Task 1: Frontend independent source editor

Worktree: `/Users/wangshanhe/.codex/worktrees/polling-backpressure-frontend/axon-link-frontend` on `codex/script-config-datasource`.
Files: `ReplayDatabaseComparisonScriptManager.vue`, relevant `replayDatabaseComparison.js` API wrappers and component/API/version tests.

- [x] Add failing behavioral tests for new and existing configuration connection fields, password retain/replace/reset, connection-aware Excel and metadata refresh, stale metadata, invalid ignored fields and saved-revision downloads.
- [x] Implement the connection form, BASE compatibility, body-only preview/source context, field refresh preserving table settings, and safe password handling. Reuse existing modal style and lifecycle guards.
- [x] Run targeted tests, complete frontend suite and production build with explicit `/tmp/axon-script-source-frontend-dist --emptyOutDir` output; inspect changed UI at available narrow/wide layouts if feasible.
- [x] Self-review and commit named frontend files only; no push. Report commands/results/hash to root.

## Task 2: Backend source resolution and frozen generation

Worktree: `/Users/wangshanhe/.codex/worktrees/script-config-datasource-server/axon-link-server` on `codex/script-config-datasource`.
Files: script configuration DTO/DAO/service/controller, bounded source+encryption helpers/properties, explicit-connection metadata reader, V97 migration, relevant tests and deployment instructions.

- [x] Add failing tests covering per-source metadata selection, isolated schemas, source persistence, encrypted storage/redaction, retained/replaced password rules, stale id/revision, failed saves, old/offline download and close/timeout behavior.
- [x] Implement the minimal source/encryption/DAO/DTO extensions and reuse catalog SQL with explicit connection/schema for the three read paths.
- [x] Add import source context and JSON metadata preview with existing human permissions; preserve legacy overloads/callers and validate all save metadata on the server.
- [x] Run relevant tests under Java17, then the full backend suite with the actual final frontend bundle. Verify V95+V97 migration and missing/invalid key behavior. No production DB access.
- [x] Self-review and commit named files only; no push. Report commands/results/hash and any runtime limits to root.

## Task 3: Independent review and integration

- [x] Review API alignment, credential isolation and redaction, lifecycle/races, schema/query/resource handling and existing-config compatibility. Resolve findings with meaningful regression tests.
- [x] Verify complete suites, frontend build, migration/docs and unchanged original dirty files. Commit plan status and deployment instructions.
- [x] Push verified frontend/backend/design commits to their GitHub main branches under prior user authorization, with no force push. Copy final frontend bundle to local backend. No source ZIP unless separately requested.

## Final verification

Frontend commits `01ee9fc`, `7a9ba60`, and `af64e14`: 843 tests passed and the production build passed. Desktop/narrow browser checks used mocked APIs. Backend commits `2144517` and `64af10c`: 1677 tests, zero failures/errors, six existing skips with Java17; 53 focused source/credential/API tests passed. The last frontend bundle also passed the focused SecurityIntegrationTest recheck (nine tests, two existing skips, zero failures/errors). V95→V97 migration was verified on temporary MySQL9.4 with the legacy revision/blob retained; that instance was removed. Source GaussDB driver/catalog tests used JDBC doubles, with no production database access. Independent final review approved both branches with no open findings.

Authoritative design changes were rebased onto the latest Obsidian main, preserving both concurrent append-only log entries; design revision `6c59237`. Prior code and vault working-copy edits were left unchanged. The authorized push step is completed by verifying remote refs in the final task response.
