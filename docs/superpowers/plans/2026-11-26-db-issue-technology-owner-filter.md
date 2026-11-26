# Database issue technology-owner filtering

**Goal:** Give the existing 科技负责人 column the same header filtering and fuzzy candidate search as 开发负责人.

**Authority:** User's explicit mirror-existing-column request; the existing database issue design under `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库问题清单-{系统设计,数据模型,API接口}.md` is updated in a clean Obsidian worktree before implementation.

## Contract

- Preserve frontend column/response key `technologyOwnerDisplay`. Candidate `field` and singular fuzzy list parameter both use `technologyOwnerDisplay`; exact multi-select uses `technologyOwners`.
- Reuse current header UI and backend predicates: multi-select, empty-string blank, escaped substring candidate search by name/account fragment, counts, other-column scoping, self-filter exclusion, 500-item limits and truncation. Match complete display values without splitting people.
- Use `COALESCE(registration.technology_owner_display,'')` from existing issueFrom mapping, including frozen batch source resolution for CUSTOM_SQL and blank unbound aliases. Use the same source as rendered personnel display, not a separate global personnel search or SysUser identity projection.
- List totals, GET/POST list, GET/POST candidates and Excel export share filters; reset/clear and paging remain unchanged. Preserve visible-row exclusions, sorting, permissions, review identity and import behavior. No migration, background requests or unrelated query refactor.
- Work only in managed frontend/backend/design worktrees and stage named files; preserve original unrelated dirty plan/vault files. Prior user authorization covers verified pushes to GitHub main.

## Tasks

- [ ] Update the existing design/API text, removing the obsolete no-technology-filter statement.
- [ ] Frontend: add failing behavioral tests for technology header opening, fuzzy candidate search, multi/blank selection, cross-filter request, export and clear/reset; enable existing column via header config with no new widgets.
- [ ] Backend: add failing real-row tests for full-display and blank filters, fuzzy literals, candidate counts/self-exclusion/other filters, same-named CUSTOM_SQL sources in different bound batches, and matching export. Add the filter using existing mapping and generic code.
- [ ] Run appropriate focused tests, complete frontend/backend suites and production frontend build; use Java17 and actual bundled index for backend security integration tests. Existing verified baselines are frontend843/backend1677(6skips) at af64e14/90e430f, so do not rerun an identical baseline unnecessarily.
- [ ] Independently review both diffs and verify statistical/permission/display equivalence. Resolve actionable findings with targeted regression tests.
- [ ] Commit named changes, fast-forward/push each GitHub main without force, copy final frontend to local backend, and report verification. No ZIP requested.
