# SDD ledger — plan: docs/superpowers/plans/2026-08-05-replay-issue-tracking.md

Baseline: d1ab4a0

## Tasks

- Task 1: complete (commits d1ab4a0..363025b, review clean; minor: enforce system-status rejection in Task 3 service/controller and populate DTO status/manual fields in mapping)
- Task 2: fix round 1/5 (2 addressed, 1 open: unique-key migration silently deleted duplicate legacy rows; commits 0b3d0b6..09edeb6)
- Task 2: fix round 2/5 (remaining finding addressed; migration now fails on null/duplicate legacy keys without deleting data; commits 09edeb6..1ce017e)
- Task 2: complete (commits 363025b..1ce017e, review findings addressed)
- Task 3: complete (commits 1ce017e..4f4da5e, review self-check clean)
- Task 4: complete (commits 4f4da5e..fb0efc9, focused and replay suites pass)
- Task 5: complete (frontend commit 036775e; 26 tests pass and isolated production build pass)
- Task 6: complete (static package integrated; replay suite 48 pass; Maven package and jar inspection pass)
