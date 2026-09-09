# Replay Long Filter POST Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent HTTP 400 for long issue-description header filters by moving filter-heavy reads from URL query strings to JSON request bodies.

**Architecture:** Keep existing GET endpoints for compatibility and add POST handlers on the same paths. A typed request body wraps the normalized `ReplayIssueQuery`; the frontend list and counted-candidate APIs always call POST, while DAO SQL and response formats remain unchanged.

**Tech Stack:** Java 17, Spring MVC, Vue 3, Fetch API, JUnit 5, Vitest.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`.

## Global Constraints

- Existing GET endpoints and responses remain unchanged.
- POST JSON supports every existing list/header filter, including repeated long-text values.
- Server still clamps list page size to `1..200` and defaults to `50`.
- No database schema or query semantics change.

---

### Task 1: Add Failing API Contract Tests
- [x] Assert frontend list and counted candidate calls use POST JSON without long values in the URL.
- [x] Assert backend POST endpoints accept long `issueDescriptions` and return the existing response shape.

### Task 2: Implement Typed POST Requests
- [x] Add list/header request records and normalized query conversion.
- [x] Add POST handlers beside the compatible GET handlers.
- [x] Switch frontend API functions to JSON POST.

### Task 3: Verify Regression
- [x] Run focused frontend and backend tests.
- [x] Build frontend and backend packages.
