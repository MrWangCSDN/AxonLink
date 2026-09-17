# Replay Report Mail Formatting Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove pasted blank-line inflation from replay daily/weekly mail bodies and render the embedded summary tables with the same grouped layout and palette as the Excel lower summary.

**Architecture:** Normalize user-authored mail text at the Vue paste boundary for immediate feedback and again in the backend HTML renderer as a transport safety net. Keep the persisted/request body plain text, then render the already-persisted summary view with semantic two-row HTML headers and the workbook's existing pink, yellow, white, and green cell palette.

**Tech Stack:** Vue 3, Vitest, Java 17, Spring Boot 3.1, JUnit 5.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section `日报与周报邮件正文汇总表（2026-09-16）`.

## Global Constraints

- Preserve single logical line breaks and collapse only runs of two or more line breaks.
- Normalize CRLF and CR to LF before collapsing blank lines.
- Keep the request and persisted body as plain text; never accept caller-supplied HTML.
- Escape all body, header, and cell text before adding HTML markup.
- Keep query summaries before accounting summaries and retain horizontal scrolling.
- Reuse the exact Excel lower-summary colors: `#F4CCCC`, `#FFF2CC`, `#FFFFFF`, `#FFF9E6`, and `#E2F0D9`.
- Do not change summary values, calculations, attachments, mail endpoints, or recipient behavior.

---

### Task 1: Normalize Pasted Mail Body Text

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: browser `ClipboardEvent` text from the daily/weekly shared body textarea.
- Produces: `normalizeReplayReportMailBody(value)` and `onReplayReportMailBodyPaste(event)` behavior shared by both report kinds.

- [ ] Add a failing component test that pastes mixed `CRLF`, blank lines, and numbered content into `daily-report-mail-body` and expects one newline between logical lines.
- [ ] Run the focused Vitest case and verify it fails because the textarea has no paste normalization.
- [ ] Add `@paste="onReplayReportMailBodyPaste"`; splice normalized clipboard text into the current selection and update `dailyReportMailBody` without losing surrounding text.
- [ ] Reuse the same normalizer immediately before submit so keyboard/API-populated duplicate blank lines are also removed from the outbound body.
- [ ] Run the focused component tests and verify daily and weekly paths both submit normalized plain text.

### Task 2: Render Excel-Style HTML Summary Tables

**Files:**
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/service/ReplayReportMailHtmlRenderer.java`
- Modify: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/service/ReplayReportMailHtmlRendererTest.java`

**Interfaces:**
- Consumes: `ReplayReportSummaryView.columns()`, including adjacent `groupLabel` runs.
- Produces: escaped HTML with normalized body text, two header rows, `rowspan` for ungrouped columns, `colspan` for grouped columns, and Excel-matching cell colors.

- [ ] Add failing renderer assertions for collapsed blank lines, grouped `colspan`, ungrouped `rowspan`, pink transaction headers, yellow unresolved headers/cells, white regular details, green regular total cells, and yellow unresolved total cells.
- [ ] Run `ReplayReportMailHtmlRendererTest` and verify the assertions fail against the current flattened gray header.
- [ ] Add a body normalizer and render contiguous grouped columns into a first header row plus their labels in a second header row.
- [ ] Select detail and total cell styles by `column.groupLabel()` while preserving formatting and escaping.
- [ ] Run renderer and daily/weekly mail service tests.

### Task 3: Regression Verification

**Files:**
- Verify only; no additional production files.

- [ ] Run the focused frontend mail tests.
- [ ] Run backend renderer, daily mail, and weekly mail tests with Java 17.
- [ ] Run `git diff --check` in both repositories.
- [ ] Inspect diffs to confirm unrelated dirty workspace changes were not modified.
