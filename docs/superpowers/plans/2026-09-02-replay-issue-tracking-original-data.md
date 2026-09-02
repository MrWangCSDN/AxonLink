# Replay Issue Tracking Original Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 改造并行回放问题跟踪，使每个导入批次始终可见，批次可展开查看原始导入值，正文仅展示真实字段差异，并阻止无变化保存产生历史记录。

**Architecture:** 保留导入批次和问题-批次关联作为批次存在性的来源；使用导入快照提供“原始数据”展开内容；服务端统一计算规范化后的字段差异，由 API 返回结构化差异，前端不再解析完整快照。问题跟踪抽屉保留现有时间线容器，但将批次卡片压缩为时间、操作人、操作类型、批次、差异表和右侧“原始数据”入口。

**Tech Stack:** Spring Boot, JdbcTemplate, Java records, MySQL-compatible SQL, Vue 3, Vite, Vitest, Vue Test Utils.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题跟踪-批次原始数据与变更时间线-设计.md`

## Global Constraints

- 每次成功导入都保留批次节点；无变化导入不新增问题历史。
- 用户打开编辑后未修改内容并保存，不新增“人工保存”历史。
- 导入和人工操作统一展示字段差异；导入操作人固定为“系统”。
- 正文只展示“字段、变更前、变更后、操作时间、操作人”等必要信息。
- 原始数据只读、来自本批次导入快照；不展示内部快照 JSON、ID、来源行号。
- 长文本单行截断，鼠标悬浮展示完整多行内容。
- 尊重当前两个仓库的既有未提交修改，不回滚或重排无关文件。

---

### Task 1: Define Field Diff And Original Data Projection

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueFieldChange.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueOriginalDataItem.java`
- Create or modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueTrackingEvent.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueTrackingProjection.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueTrackingProjectionTest.java`

**Interfaces:**
- Consumes: `ReplayIssueRow` snapshots and existing `ReplayIssueHistoryEntry` values.
- Produces: immutable API-ready field-change and original-data items with fixed display labels.

- [ ] **Step 1: Write failing projection tests**
  - Verify changed values produce exactly one item per changed business field.
  - Verify equal values, null versus normalized blank values, and repeated saves produce no diff.
  - Verify original-data projection excludes IDs, snapshot metadata, source row and other technical fields.
  - Verify long values remain intact in the projection for the frontend tooltip.
- [ ] **Step 2: Run focused test and confirm failure**

Run: `mvn -q -Dtest=ReplayIssueTrackingProjectionTest test`

Expected: FAIL because the projection types and comparison logic do not exist.
- [ ] **Step 3: Implement the smallest typed projection**
  - Keep field labels in one explicit whitelist.
  - Normalize comparison values before comparing, but preserve display values for before/after output.
  - Treat the incoming snapshot as original-data input, not as the before value in a user/system diff.
- [ ] **Step 4: Run focused test and confirm pass**

Run: `mvn -q -Dtest=ReplayIssueTrackingProjectionTest test`

- [ ] **Step 5: Review projection scope against the approved Obsidian spec**

### Task 2: Stop No-Op Manual Saves From Creating History

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueTrackingProjection` diff result and the existing normalized edit result.
- Produces: unchanged behavior for real edits, no current-row/history mutation for no-op saves.

- [ ] **Step 1: Add a failing no-op save test**
  - Submit the exact current values, assert the returned row remains valid.
  - Assert `dii_replay_issue_history` count does not increase.
  - Assert the current row and latest-history timestamp are unchanged.
- [ ] **Step 2: Run the focused test and confirm failure**

Run: `mvn -q -Dtest=ReplayIssueEditServiceTest test`

Expected: FAIL because `update` currently always calls `insertHistoryForRound`.
- [ ] **Step 3: Add the no-op guard after building the normalized `after` row**
  - Compare only tracked business fields.
  - Return without `updateCurrent`, `insertHistoryForRound`, or latest-history batch mutation when there is no diff.
  - Keep validation and authorization behavior unchanged.
- [ ] **Step 4: Run the focused edit tests**

Run: `mvn -q -Dtest=ReplayIssueEditServiceTest test`

- [ ] **Step 5: Add a real-change regression assertion**
  - Verify one changed field still creates exactly one “人工保存” event with the current operator.

### Task 3: Make Import Batch Membership Complete And Import No-Ops Quiet

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify if needed: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRoundEntry.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Consumes: one successful import workbook and the current issue projection.
- Produces: one import round and issue-round association for participating rows, optional history only for real changes, and preserved incoming snapshot for original-data expansion.

- [ ] **Step 1: Add failing import tests for three cases**
  - Existing issue with identical incoming values: issue-round association exists, history count is unchanged.
  - Existing issue with one changed base field or system status transition: one system history event exists with incoming snapshot.
  - New issue: batch association, original snapshot and one “导入新增” event exist.
- [ ] **Step 2: Run import tests and confirm the current behavior fails**

Run: `mvn -q -Dtest=ReplayIssueMergeServiceTest,ReplayIssueDaoTest test`

- [ ] **Step 3: Compare import-before/import-after values before inserting history**
  - Keep `insertIssueRound` and occurrence/batch updates for every participating issue.
  - Do not use the existence of a history row to decide whether a batch belongs in the timeline.
  - Preserve the actual `incomingSnapshot` for every batch association that supports original-data viewing.
  - Keep existing status business rules, inheritance behavior and auto-repair behavior; only suppress history when no tracked field changed.
- [ ] **Step 4: Run focused import and DAO tests**

Run: `mvn -q -Dtest=ReplayIssueMergeServiceTest,ReplayIssueDaoTest test`

- [ ] **Step 5: Add stable ordering assertions**
  - Verify equal timestamps use descending database IDs/round IDs.

### Task 4: Return Batch Nodes With Original Data And Structured Diffs

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRoundTrackingGroup.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: issue-round associations, import snapshots and actual history rows.
- Produces: `GET /api/ai/parallel-replay/issues/{id}/round-tracking` response with batch metadata, original-data items and structured event changes.

- [ ] **Step 1: Add failing controller contract tests**
  - A no-op batch is returned with an empty change list and original-data payload.
  - A changed import returns operator “系统”, operation time, batch and field changes.
  - A manual event returns the real operator and only changed fields.
  - Technical snapshot fields are absent from the presentation payload.
- [ ] **Step 2: Run controller tests and confirm failure**

Run: `mvn -q -Dtest=ReplayIssueControllerTest test`

- [ ] **Step 3: Make issue-round associations the batch timeline source**
  - Build batch nodes even when no history event exists.
  - Attach history events to the correct batch without reintroducing the old inherited/manual nested sections.
  - Keep the raw `/tracking` endpoint compatible unless the approved API shape requires a coordinated change.
  - Return a single explicit field-diff list per event and original-data items for import events.
- [ ] **Step 4: Apply descending time and stable tie-break ordering**
- [ ] **Step 5: Run controller regression tests**

Run: `mvn -q -Dtest=ReplayIssueControllerTest test`

### Task 5: Redesign The Tracking Drawer Presentation

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js` only if response normalization is needed
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Test: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js` only if API shape changes

**Interfaces:**
- Consumes: structured round-tracking response from Task 4.
- Produces: compact timeline cards with `原始数据` toggle, field-diff table and hover tooltips.

- [ ] **Step 1: Add failing component tests**
  - Render batch header with time, operator, operation type, batch and a right-aligned `原始数据` button.
  - Verify clicking the button expands only that batch’s original-data area.
  - Verify the正文 shows only `字段 / 变更前 / 变更后` rows.
  - Verify no-op batch keeps its card and shows `本次无变化` without an empty table.
  - Verify long before/after/original values are truncated and expose the full value on hover.
  - Verify manual and system events use the same layout while showing different operators.
- [ ] **Step 2: Run focused frontend tests and confirm failure**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

- [ ] **Step 3: Replace legacy nested snapshot sections**
  - Remove visible complete-snapshot rendering from the正文.
  - Render metadata first, then the diff table, then the compact `原始数据` control for import batches.
  - Keep the current drawer, timeline marker and latest-batch cue unless they conflict with the approved layout.
- [ ] **Step 4: Add accessible original-data disclosure**
  - Use a real button or native disclosure semantics.
  - Keep button state and label clear when expanded/collapsed.
- [ ] **Step 5: Add visual text overflow behavior**
  - Apply single-line ellipsis only to constrained cells.
  - Add a hover tooltip/title with complete multi-line text only when truncation is needed.
  - Ensure the drawer remains readable on narrow screens.
- [ ] **Step 6: Run focused frontend tests**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js`

### Task 6: Update Design References And Verify Both Builds

**Files:**
- Modify if needed: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`
- Modify if needed: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`
- Modify if needed: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`
- Modify if needed: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题跟踪-批次原始数据与变更时间线-设计.md`
- Modify generated output: `src/main/resources/static/` through the frontend build

**Interfaces:**
- Consumes: completed backend and frontend behavior from Tasks 1-5.
- Produces: synchronized Obsidian source-of-truth pages and deployable static assets.

- [ ] **Step 1: Run all replay backend tests**

Run: `mvn -q -Dtest='com.axonlink.ai.replay.**' test`

- [ ] **Step 2: Run the frontend replay test suite**

Run: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js`

- [ ] **Step 3: Build the frontend into the server static directory**

Run: `npm run build` from `/Users/java/axon-link-frontend`

- [ ] **Step 4: Run backend packaging/verification**

Run: `mvn -q -DskipTests package`

- [ ] **Step 5: Run whitespace and diff checks without touching unrelated changes**

Run: `git diff --check` in both repositories.

- [ ] **Step 6: Review the final UI behavior**
  - Confirm batch without changes remains visible.
  - Confirm original data is available only through the adjacent button.
  - Confirm long values do not expand the drawer unexpectedly.
  - Confirm no-op manual save does not create a timeline event.
