# Polling backpressure implementation plan

**Goal:** Stop hidden and overlapping browser polling from repeatedly running expensive task-list queries.

**Approved scope:** The user's 2026-11-26 request to fix the previously presented polling diagnosis and corrective proposal. Existing task-list semantics are documented in `/Users/java/obsidian/01 Engineering/axon-link-server/巡检任务列表页设计.md`. This is a bug/performance fix; no API or business-count changes and no duplicate design specification.

**Architecture:** Serialize the three existing browser pollers with lifecycle and visibility guards, cancellation, and bounded failure backoff. Fetch the task page before calculating statistics, and restrict detail aggregation to that page's task IDs and environments. Do not change the Hikari defaults or backend build polling.

**Tech stack:** Vue 3, Vitest/fake timers, Java 17, Spring JdbcTemplate, JUnit/H2.

## Global constraints

- Preserve task list filters, descending task ID order, pagination, limit clamping (1..500), and response keys.
- Preserve all existing item/pool count predicates, environment/collation join behavior, and zero counts for missing details. Do not introduce a schema migration or require MySQL 8 syntax.
- Keep task polling at a five-second delay after completion while the visible task page contains PENDING/RUNNING tasks; retain thirty-second base delays for health and todo polling.
- A poller has at most one request cycle in flight. Hidden browser documents and inactive task pages do not schedule new requests. Stop/abort on unmount; late results cannot mutate UI or restart timers.
- On actual failures, retry with bounded exponential delays (up to sixty seconds); reset after success. Cancellation is not a failure. Coalesce rapid filter/environment/manual refresh requests and discard stale responses.
- Preserve API authentication and error handling; propagate AbortSignal through the API wrappers without changing existing callers.
- Use the two managed isolated worktrees; leave all pre-existing edits in original checkouts and Obsidian intact. Stage only named task files. Prior user authorization covers pushing the verified changes to GitHub main.

## Review focus

- Slow and rejected requests must not overlap or continue at a fixed failure interval.
- Hide/show and unmount during initialization or an in-flight request cannot revive a stopped poller.
- Rapid task environment/filter changes must eventually fetch the latest state without showing stale data.
- Off-page tasks and other environments must not affect counts; shared environments and empty pages retain correct results.
- All three integrations must honor document visibility; task polling must additionally honor the active menu.

## Task 1: Frontend polling lifecycle

Worktree: `/Users/wangshanhe/.codex/worktrees/polling-backpressure-frontend/axon-link-frontend`.
Files: `src/components/daoindex/DaoTaskList.vue`, `DaoIndexPage.vue`, `src/views/TransactionAnalysis.vue`, `src/components/AppHeader.vue`, relevant `src/api` wrappers, one small polling composable and behavioral tests.

- [ ] Add failing fake-timer/deferred-promise tests covering slow/failing requests, visibility, inactive task menu, unmount, and stale filter results. Observe the expected failures against current code.
- [ ] Implement shared serial scheduling and integrate the task, health/build, and todo pollers. Avoid unrelated component refactors.
- [ ] Run targeted tests, the complete frontend test suite, and a production build using an explicit temporary output path.
- [ ] Self-review and commit named frontend changes; report test counts, limitations, and commit hash. Do not push; root handles integration.

## Task 2: Page-scoped task statistics

Worktree: `/Users/wangshanhe/.codex/worktrees/polling-backpressure-server/axon-link-server`.
Files: `DiiAnalysisTaskDao.java` and a focused DAO test.

- [ ] Add behavioral H2 tests for page ordering/filtering, item/pool counts, mixed/shared environments, empty pages, and limits. Add a query-boundary check proving aggregation is scoped to fetched page IDs/environments rather than the whole history. Observe the performance-boundary test fail against current code.
- [ ] Fetch the task page first; enrich only its statistics using bounded ID/environment predicates, preserving database collation and count semantics. Skip aggregate queries for an empty page. No countAll contract change.
- [ ] Run targeted DAO tests and the full backend suite; record any genuine baseline failures separately.
- [ ] Self-review and commit named backend changes; report tests and commit hash. Do not push; root handles integration.

## Task 3: Independent review and integration

- [ ] Review both diffs for concurrency races, statistical equivalence, and unbounded scans. Resolve actionable findings and repeat affected tests.
- [ ] Verify frontend build, backend tests, git diff checks, and unchanged original dirty files.
- [ ] Fast-forward the verified commits to each GitHub main without force-pushing; report both commits and deployment implications.
