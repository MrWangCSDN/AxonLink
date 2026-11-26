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

- [x] Update the existing design/API text, removing the obsolete no-technology-filter statement.
- [x] Frontend: add failing behavioral tests for technology header opening, fuzzy candidate search, multi/blank selection, cross-filter request, export and clear/reset; enable existing column via header config with no new widgets.
- [x] Backend: add failing real-row tests for full-display and blank filters, fuzzy literals, candidate counts/self-exclusion/other filters, same-named CUSTOM_SQL sources in different bound batches, and matching export. Add the filter using existing mapping and generic code.
- [x] Run appropriate focused tests, complete frontend/backend suites and production frontend build; use Java17 and actual bundled index for backend security integration tests. Existing verified baselines are frontend843/backend1677(6skips) at af64e14/90e430f, so do not rerun an identical baseline unnecessarily.
- [x] Independently review both diffs and verify statistical/permission/display equivalence. Resolve actionable findings with targeted regression tests.
- [x] Commit named changes, fast-forward/push each GitHub main without force, copy final frontend to local backend, and report verification. No ZIP requested.

## Verification

Frontend `b0ec00b`:121 focused tests and845 full tests passed; production build passed. Backend `a993bbe`/`bed5888`:72 final focused tests and1681 full tests, zero failures/errors and6 existing skips under Java17. Real H2 rows/workbooks cover scopes, blank/multi selection, fuzzy literal escaping, frozen CUSTOM_SQL source identities, and a concurrent personnel assignment update. The matched display is selected with the filter query; current identities still govern permissions and mutation responses. Independent final review has no open findings. The final frontend bundle is included in the backend verification. No production database was accessed and no migration is introduced. Remote main refs are verified after the authorized integration push.
