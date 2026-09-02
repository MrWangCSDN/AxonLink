package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueHistoryEntry;
import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueRoundEntry;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueMergeServiceTest {
    private ReplayIssueDao dao;
    private ReplayIssueMergeService merge;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        merge = new ReplayIssueMergeService(dao);
    }

    @Test
    void createsNewIssueAsNewWithManualFieldsEmptyAndHistory() {
        merge.merge(workbook(row("K1", "new")), LocalDate.of(2026, 8, 5), new ReplayIssueOperator("u", "n"));
        Map<String, Object> current = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null)).get(0);
        assertEquals("新建", current.get("issue_status"));
        assertEquals("", current.get("issue_type"));
        assertEquals(1L, dao.countHistory("K1"));
    }

    @Test
    void coverageRoundMarksEveryIncomingRowIncludingRefreshedDuplicates() {
        dao.insertCurrent(lifecycle(row("EXISTING", "old"), ReplayIssueStatus.OPEN,
                "代码问题", "a", "s", "alice"));

        var result = merge.merge(workbook(row("EXISTING", "new"), row("NEW", "new")),
                LocalDate.of(2026, 8, 10), ReplayIssueOperator.system(), "20260810-001");

        ReplayIssueQuery query = new ReplayIssueQuery(50, 0, null, null, null, null,
                null, null, null, null, null, null, null, null, "RPT20260805-000000-0000");
        assertEquals(2, result.totalRows());
        assertEquals(1, result.createdRows());
        assertEquals(1, result.updatedRows());
        assertEquals(0, result.ignoredRows());
        assertEquals("20260810-001", result.coverageRound());
        assertEquals(2, dao.count(query));
        assertEquals(List.of("RPT20260805-000000-0000"), dao.options().coverageRounds());
        assertEquals(1, dao.listImportRounds().size());
        assertEquals(2, dao.listImportRounds().get(0).inputRows());
        assertEquals("数据继承", dao.findIssueRounds(((Number) dao.list(query).stream()
                .filter(item -> "EXISTING".equals(item.get("issue_key"))).findFirst().orElseThrow().get("id")).longValue())
                .get(0).actionType());
    }

    @Test
    void unchangedImportKeepsBatchMembershipAndIncomingDataWithoutHistory() {
        long id = dao.insertCurrent(lifecycle(row("UNCHANGED", "same"), ReplayIssueStatus.OPEN,
                "代码问题", "初步分析", "处理方案", "alice"));
        dao.upsertOccurrenceBatch(id, "UNCHANGED", "RPT20260805-000000-0000",
                LocalDateTime.of(2026, 8, 5, 1, 0), ReplayIssueStatus.OPEN);

        ReplayIssueImportResult result = merge.merge(workbook(row("UNCHANGED", "same")),
                LocalDate.of(2026, 8, 5), ReplayIssueOperator.system(), "20260805-unchanged");

        assertEquals(1, result.updatedRows());
        assertEquals(0, dao.findHistoryByIssueId(id, 10).size());
        assertEquals(1, dao.findIssueRounds(id).size());
        assertTrue(dao.findIssueRounds(id).get(0).incomingSnapshot().contains("same"));
    }

    @Test
    void reopensPendingAndRetainsManualFieldsWhileRefreshingSourceFields() {
        ReplayIssueRow seed = lifecycle(row("K1", "old"), ReplayIssueStatus.PENDING_VERIFICATION, "代码问题", "analysis", "solution", "alice");
        dao.insertCurrent(seed);
        merge.merge(workbook(row("K1", "new")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system());
        Map<String, Object> current = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null)).get(0);
        assertEquals("重新打开", current.get("issue_status"));
        assertEquals("new", current.get("issue_description"));
        assertEquals("代码问题", current.get("issue_type"));
        assertEquals("analysis", current.get("initial_analysis"));
        assertEquals("solution", current.get("final_solution"));
        assertEquals("alice", current.get("cooperation_person_username"));
        assertEquals("2026-08-05", current.get("import_date").toString());
    }

    @Test
    void reimportPreservesPlannedCompletionDate() {
        ReplayIssueRow seed = lifecycle(row("K-PLAN", "old"), ReplayIssueStatus.OPEN,
                "代码问题", "analysis", "solution", "alice");
        long id = dao.insertCurrent(seed);
        dao.updatePlannedCompletionDate(id, LocalDate.of(2026, 8, 26));

        merge.merge(workbook(row("K-PLAN", "new")), LocalDate.of(2026, 8, 27), ReplayIssueOperator.system());

        Map<String, Object> current = dao.list(new ReplayIssueQuery(10, 0,
                null, null, null, null, null, null)).get(0);
        assertEquals(LocalDate.of(2026, 8, 26), current.get("planned_completion_date"));
    }

    @Test
    void rejectsBlankAndDuplicateKeysBeforeTransaction() {
        assertThrows(IllegalArgumentException.class, () -> merge.merge(workbook(row("", "bad")), LocalDate.now(), ReplayIssueOperator.system()));
        assertThrows(IllegalArgumentException.class, () -> merge.merge(workbook(row("K1", "one"), row("K1", "two")), LocalDate.now(), ReplayIssueOperator.system()));
    }

    @Test
    void autoRepairUsesRptBatchDateInsteadOfRegisteredOrImportDate() {
        dao.insertCurrent(lifecycle(
                withBatch(row("RPT-MISSING", "old"), "RPT20260819-100000-0001"),
                ReplayIssueStatus.OPEN, "代码问题", "a", "s", null));

        merge.merge(workbook(withBatch(row("RPT-PRESENT", "new"), "RPT20260820-142055-9860")),
                LocalDate.of(2026, 8, 27), ReplayIssueOperator.system());

        assertEquals(LocalDate.of(2026, 8, 20),
                dao.findCurrentByIssueKeyForUpdate("RPT-MISSING").defectRepairDate());
    }

    @Test
    void autoRepairUsesDzBatchDate() {
        dao.insertCurrent(lifecycle(
                withBatch(row("DZ-MISSING", "old"), "DZ20260819-100000-0001"),
                ReplayIssueStatus.OPEN, "代码问题", "a", "s", null));

        merge.merge(workbook(withBatch(row("DZ-PRESENT", "new"), "DZ20260821-142055-9860")),
                LocalDate.of(2026, 8, 27), ReplayIssueOperator.system());

        assertEquals(LocalDate.of(2026, 8, 21),
                dao.findCurrentByIssueKeyForUpdate("DZ-MISSING").defectRepairDate());
    }

    @Test
    void rejectsInvalidBatchFormatBeforeTransaction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> merge.merge(workbook(withBatch(row("BAD", "bad"), "BATCH20260820")),
                        LocalDate.of(2026, 8, 27), ReplayIssueOperator.system(), "invalid-format"));

        assertTrue(error.getMessage().contains("批次号日期格式不合法"));
        assertEquals(0, dao.listImportRounds().size());
    }

    @Test
    void rejectsImpossibleBatchDateBeforeTransaction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> merge.merge(workbook(withBatch(row("BAD-DATE", "bad"), "RPT20260230-100000-0001")),
                        LocalDate.of(2026, 8, 27), ReplayIssueOperator.system(), "invalid-date"));

        assertTrue(error.getMessage().contains("批次号日期格式不合法"));
        assertEquals(0, dao.listImportRounds().size());
    }

    @Test
    void rejectsMultipleBatchDatesBeforeTransaction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> merge.merge(workbook(
                                withBatch(row("DAY-20", "one"), "RPT20260820-100000-0001"),
                                withBatch(row("DAY-21", "two"), "RPT20260821-100000-0002")),
                        LocalDate.of(2026, 8, 27), ReplayIssueOperator.system(), "mixed-date"));

        assertEquals("同一工作簿存在多个批次日期：2026-08-20、2026-08-21", error.getMessage());
        assertEquals(0, dao.listImportRounds().size());
    }

    @Test
    void fixesMissingPendingAndReopensFixedWithNewImportDate() {
        dao.insertCurrent(lifecycle(row("MISSING", "old"), ReplayIssueStatus.PENDING_VERIFICATION, "代码问题", "a", "s", "alice"));
        dao.insertCurrent(lifecycle(row("FIXED", "old"), ReplayIssueStatus.FIXED, "代码问题", "a", "s", "alice"));
        merge.merge(workbook(row("FIXED", "new")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system());
        List<Map<String, Object>> rows = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null));
        Map<String, Object> missing = rows.stream().filter(r -> "MISSING".equals(r.get("issue_key"))).findFirst().orElseThrow();
        Map<String, Object> fixed = rows.stream().filter(r -> "FIXED".equals(r.get("issue_key"))).findFirst().orElseThrow();
        assertEquals("已修复", missing.get("issue_status"));
        assertEquals("2026-08-05", missing.get("defect_repair_date").toString());
        assertEquals("新建", fixed.get("issue_status"));
        assertEquals("new", fixed.get("issue_description"));
        assertEquals(1L, dao.countHistory("FIXED"));
    }

    @Test
    void fixesMissingDeferredIssueButRefreshesReappearingDeferredIssue() {
        dao.insertCurrent(lifecycle(row("DEFERRED-MISSING", "old"), ReplayIssueStatus.DEFERRED, "代码问题", "a", "s", "alice"));
        dao.insertCurrent(lifecycle(row("DEFERRED-PRESENT", "old"), ReplayIssueStatus.DEFERRED, "代码问题", "a", "s", "alice"));

        merge.merge(workbook(row("DEFERRED-PRESENT", "new")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system());

        List<Map<String, Object>> rows = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null));
        Map<String, Object> missing = rows.stream().filter(r -> "DEFERRED-MISSING".equals(r.get("issue_key"))).findFirst().orElseThrow();
        Map<String, Object> present = rows.stream().filter(r -> "DEFERRED-PRESENT".equals(r.get("issue_key"))).findFirst().orElseThrow();
        assertEquals("已修复", missing.get("issue_status"));
        assertEquals("延后修复", present.get("issue_status"));
        assertEquals("new", present.get("issue_description"));
    }

    @Test
    void noActionReappearanceRefreshesSourceAndInheritsApprovedReview() {
        ReplayIssueRow reviewed = withReviewAndDefectDate(
                lifecycle(row("NO-ACTION", "old"), ReplayIssueStatus.NO_ACTION,
                        "合理差异", "人工分析", "人工方案", "alice"),
                ReplayIssueReviewStatus.APPROVED, "reviewer", "审核人",
                LocalDateTime.of(2026, 8, 5, 12, 0), LocalDate.of(2026, 8, 5));
        long id = dao.insertCurrent(reviewed);
        dao.updatePlannedCompletionDate(id, LocalDate.of(2026, 8, 26));

        merge.merge(workbook(row("NO-ACTION", "new")), LocalDate.of(2026, 8, 6),
                ReplayIssueOperator.system(), "20260806-001");

        ReplayIssueRow current = dao.findCurrentByIdForUpdate(id);
        assertEquals("new", current.issueDescription());
        assertEquals(ReplayIssueStatus.NO_ACTION, current.issueStatus());
        assertEquals(ReplayIssueReviewStatus.APPROVED, current.reviewStatus());
        assertEquals("reviewer", current.reviewerUsername());
        assertEquals(LocalDate.of(2026, 8, 5), current.defectRepairDate());
        assertEquals("人工分析", current.initialAnalysis());
        assertEquals("人工方案", current.finalSolution());
        assertEquals("alice", current.cooperationPersonUsername());
        assertEquals(LocalDate.of(2026, 8, 26), current.plannedCompletionDate());
        ReplayIssueHistoryEntry history = dao.findHistoryByIssueId(id, 10).get(0);
        assertEquals("基础数据覆盖，人工内容继承", history.operationType());
        assertEquals(ReplayIssueReviewStatus.APPROVED, history.reviewStatus());
        assertTrue(history.afterSnapshot().contains("已审核"));
    }

    @Test
    void missingApprovedNoActionRemainsReviewedAndIsNotAutoRepaired() {
        ReplayIssueRow reviewed = withReviewAndDefectDate(
                lifecycle(row("NO-ACTION-MISSING", "old"), ReplayIssueStatus.NO_ACTION,
                        "合理差异", "人工分析", "人工方案", "alice"),
                ReplayIssueReviewStatus.APPROVED, "reviewer", "审核人",
                LocalDateTime.of(2026, 8, 5, 12, 0), LocalDate.of(2026, 8, 5));
        long id = dao.insertCurrent(reviewed);

        ReplayIssueImportResult result = merge.merge(workbook(row("PRESENT", "new")), LocalDate.of(2026, 8, 6),
                ReplayIssueOperator.system(), "20260806-002");

        ReplayIssueRow current = dao.findCurrentByIdForUpdate(id);
        assertEquals(ReplayIssueStatus.NO_ACTION, current.issueStatus());
        assertEquals(ReplayIssueReviewStatus.APPROVED, current.reviewStatus());
        assertEquals("reviewer", current.reviewerUsername());
        assertEquals(LocalDateTime.of(2026, 8, 5, 12, 0), current.reviewedAt());
        assertEquals(LocalDate.of(2026, 8, 5), current.defectRepairDate());
        assertEquals("人工分析", current.initialAnalysis());
        assertEquals(0, result.autoRepairedRows());
        assertTrue(dao.findHistoryByIssueId(id, 10).isEmpty());
        assertTrue(dao.findIssueRounds(id).isEmpty());
    }

    @Test
    void missingPendingNoActionRemainsPendingWithoutDefectRepairDate() {
        ReplayIssueRow pending = withReviewAndDefectDate(
                lifecycle(row("NO-ACTION-PENDING", "old"), ReplayIssueStatus.NO_ACTION,
                        "合理差异", "人工分析", "人工方案", "alice"),
                ReplayIssueReviewStatus.PENDING, null, null, null, null);
        long id = dao.insertCurrent(pending);

        ReplayIssueImportResult result = merge.merge(workbook(row("PRESENT", "new")), LocalDate.of(2026, 8, 6),
                ReplayIssueOperator.system(), "20260806-003");

        ReplayIssueRow current = dao.findCurrentByIdForUpdate(id);
        assertEquals(ReplayIssueStatus.NO_ACTION, current.issueStatus());
        assertEquals(ReplayIssueReviewStatus.PENDING, current.reviewStatus());
        assertNull(current.defectRepairDate());
        assertEquals(0, result.autoRepairedRows());
        assertTrue(dao.findHistoryByIssueId(id, 10).isEmpty());
        assertTrue(dao.findIssueRounds(id).isEmpty());
    }

    @Test
    void dzImportOnlyAutoRepairsMissingDzIssues() {
        dao.insertCurrent(lifecycle(withBatch(row("QUERY-MISSING", "query"), "RPT20260820-100000-0001"),
                ReplayIssueStatus.OPEN, "代码问题", "a", "s", null));
        dao.insertCurrent(lifecycle(withBatch(row("DZ-MISSING", "dz"), "DZ20260820-100000-0001"),
                ReplayIssueStatus.OPEN, "代码问题", "a", "s", null));

        ReplayIssueImportResult result = merge.merge(
                workbook(withBatch(row("DZ-PRESENT", "present"), "DZ20260821-100000-0001")),
                LocalDate.of(2026, 8, 21), ReplayIssueOperator.system());

        assertEquals(1, result.autoRepairedRows());
        assertEquals(ReplayIssueStatus.OPEN,
                dao.findCurrentByIssueKeyForUpdate("QUERY-MISSING").issueStatus());
        assertEquals(ReplayIssueStatus.FIXED,
                dao.findCurrentByIssueKeyForUpdate("DZ-MISSING").issueStatus());
    }

    @Test
    void queryImportOnlyAutoRepairsMissingQueryIssues() {
        dao.insertCurrent(lifecycle(withBatch(row("QUERY-MISSING", "query"), "RPT20260820-100000-0001"),
                ReplayIssueStatus.OPEN, "代码问题", "a", "s", null));
        dao.insertCurrent(lifecycle(withBatch(row("DZ-MISSING", "dz"), "DZ20260820-100000-0001"),
                ReplayIssueStatus.OPEN, "代码问题", "a", "s", null));

        ReplayIssueImportResult result = merge.merge(
                workbook(withBatch(row("QUERY-PRESENT", "present"), "RPT20260821-100000-0001")),
                LocalDate.of(2026, 8, 21), ReplayIssueOperator.system());

        assertEquals(1, result.autoRepairedRows());
        assertEquals(ReplayIssueStatus.FIXED,
                dao.findCurrentByIssueKeyForUpdate("QUERY-MISSING").issueStatus());
        assertEquals(ReplayIssueStatus.OPEN,
                dao.findCurrentByIssueKeyForUpdate("DZ-MISSING").issueStatus());
    }

    @Test
    void missingActiveStatusesBecomeFixedWithContentAndHistoryPreserved() {
        for (ReplayIssueStatus status : List.of(ReplayIssueStatus.OPEN, ReplayIssueStatus.REOPENED,
                ReplayIssueStatus.DEFERRED, ReplayIssueStatus.PENDING_VERIFICATION)) {
            ReplayIssueDao localDao = daoForSchema();
            ReplayIssueRow seed = withRemark(lifecycle(row("MISSING-" + status.name(), "原基础数据"), status,
                    "代码问题", "人工分析", "人工方案", "alice"), "人工备注");
            long id = localDao.insertCurrent(seed);
            localDao.updatePlannedCompletionDate(id, LocalDate.of(2026, 8, 26));
            ReplayIssueMergeService localMerge = new ReplayIssueMergeService(localDao);

            ReplayIssueImportResult result = localMerge.merge(workbook(row("PRESENT-" + status.name(), "本批次数据")),
                    LocalDate.of(2026, 8, 11), ReplayIssueOperator.system(), "20260811-002");

            Map<String, Object> current = localDao.list(new ReplayIssueQuery(20, 0, null, null, null, null,
                            "MISSING-" + status.name()))
                    .get(0);
            assertEquals("已修复", current.get("issue_status"));
            assertEquals("原基础数据", current.get("issue_description"));
            assertEquals("代码问题", current.get("issue_type"));
            assertEquals("人工分析", current.get("initial_analysis"));
            assertEquals("人工方案", current.get("final_solution"));
            assertEquals("alice", current.get("cooperation_person_username"));
            assertEquals("人工备注", current.get("remark"));
            assertEquals(LocalDate.of(2026, 8, 26), current.get("planned_completion_date"));
            assertEquals("2026-08-05", current.get("defect_repair_date").toString());
            assertEquals(1, result.autoRepairedRows());

            ReplayIssueHistoryEntry history = localDao.findHistoryByIssueId(id, 10).get(0);
            assertEquals("问题自动修复", history.operationType());
            assertTrue(history.beforeSnapshot().contains(status.displayValue()));
            assertTrue(history.afterSnapshot().contains("已修复"));
            assertTrue(history.afterSnapshot().contains("人工备注"));
            assertNull(history.incomingSnapshot());
            assertNotNull(history.contextRoundId());

            ReplayIssueRoundEntry round = localDao.findIssueRounds(id).get(0);
            assertFalse(round.appeared());
            assertEquals(status, round.statusBefore());
            assertEquals(ReplayIssueStatus.FIXED, round.statusAfter());
            assertEquals("自动修复", round.actionType());
        }
    }

    @Test
    void missingFixedAndLegacyAnalyzingStatusesRemainUnchangedWithoutHistory() {
        for (ReplayIssueStatus status : List.of(ReplayIssueStatus.FIXED, ReplayIssueStatus.ANALYZING)) {
            ReplayIssueDao localDao = daoForSchema();
            long id = localDao.insertCurrent(lifecycle(row("UNCHANGED-" + status.name(), "原数据"), status,
                    "代码问题", "人工分析", "人工方案", "alice"));

            ReplayIssueImportResult result = new ReplayIssueMergeService(localDao).merge(
                    workbook(row("PRESENT-" + status.name(), "本批次数据")), LocalDate.of(2026, 8, 11),
                    ReplayIssueOperator.system(), "20260811-003");

            Map<String, Object> current = localDao.list(new ReplayIssueQuery(20, 0, null, null, null, null,
                            "UNCHANGED-" + status.name()))
                    .get(0);
            assertEquals(status.displayValue(), current.get("issue_status"));
            assertEquals(0, result.autoRepairedRows());
            assertEquals(0, localDao.findHistoryByIssueId(id, 10).size());
            assertEquals(0, localDao.findIssueRounds(id).size());
        }
    }

    @Test
    void openIssueRefreshesSourceFieldsAndRecordsInheritedManualContent() {
        ReplayIssueRow seed = withRemark(
                lifecycle(row("OPEN", "old source"), ReplayIssueStatus.OPEN,
                        "代码问题", "人工分析", "人工方案", "alice"),
                "人工备注");
        long id = dao.insertCurrent(seed);

        ReplayIssueImportResult result = merge.merge(workbook(row("OPEN", "new source")),
                LocalDate.of(2026, 8, 11), ReplayIssueOperator.system(), "20260811-001");

        ReplayIssueQuery query = new ReplayIssueQuery(50, 0, null, null, null, null,
                null, null, null, null, null, null, null, null, "RPT20260805-000000-0000");
        Map<String, Object> current = dao.list(query).get(0);
        assertEquals("new source", current.get("issue_description"));
        assertEquals("打开", current.get("issue_status"));
        assertEquals("代码问题", current.get("issue_type"));
        assertEquals("人工分析", current.get("initial_analysis"));
        assertEquals("人工方案", current.get("final_solution"));
        assertEquals("alice", current.get("cooperation_person_username"));
        assertEquals("人工备注", current.get("remark"));
        assertEquals(1, result.updatedRows());
        assertEquals(0, result.ignoredRows());

        ReplayIssueHistoryEntry event = dao.findHistoryByIssueId(id, 10).get(0);
        assertEquals("基础数据覆盖，人工内容继承", event.operationType());
        assertTrue(event.beforeSnapshot().contains("old source"));
        assertTrue(event.afterSnapshot().contains("new source"));
        assertTrue(event.afterSnapshot().contains("人工备注"));
        assertTrue(event.incomingSnapshot().contains("new source"));
        assertEquals("数据继承", dao.findIssueRounds(id).get(0).actionType());
    }

    @Test
    void analyzingDuplicateIsIgnoredWithoutHistory() {
        ReplayIssueStatus status = ReplayIssueStatus.ANALYZING;
        ReplayIssueDao localDao = daoForSchema();
        localDao.insertCurrent(lifecycle(row("K-" + status, "old"), status, "代码问题", "a", "s", "alice"));
        ReplayIssueMergeService localMerge = new ReplayIssueMergeService(localDao);
        var result = localMerge.merge(workbook(row("K-" + status, "new")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system());
        Map<String, Object> current = localDao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null)).get(0);
        assertEquals(1, result.ignoredRows());
        assertEquals("old", current.get("issue_description"));
        assertEquals(0L, localDao.countHistory("K-" + status));
    }

    @Test
    void reopenedIssueRemainsReopenedOnARepeatedImport() {
        dao.insertCurrent(lifecycle(row("REOPEN", "old"), ReplayIssueStatus.REOPENED, "代码问题", "a", "s", "alice"));
        merge.merge(workbook(row("REOPEN", "new-1")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system());
        merge.merge(workbook(row("REOPEN", "new-2")), LocalDate.of(2026, 8, 6), ReplayIssueOperator.system());
        Map<String, Object> current = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null)).get(0);
        assertEquals("重新打开", current.get("issue_status"));
        assertEquals("new-2", current.get("issue_description"));
        assertEquals(1L, dao.countHistory("REOPEN"));
    }

    @Test
    void fixedReappearanceRetainsManualFieldsAndClearsDefectDate() {
        ReplayIssueRow seed = lifecycle(row("FIXED-2", "old"), ReplayIssueStatus.FIXED, "代码问题", "a", "s", "alice");
        seed = new ReplayIssueRow(seed.id(), seed.sourceSheet(), seed.groupName(), seed.sandbox(), seed.rowOrder(), seed.domain(), seed.sequenceNo(),
                seed.batchNo(), seed.transactionCode(), seed.transactionName(), seed.issueLevel(), seed.registeredDate(), seed.fieldName(), seed.issueDescription(),
                seed.transactionOwner(), seed.issueType(), seed.initialAnalysis(), seed.finalSolution(), seed.resolvedDate(), seed.cooperationGroup(), seed.resolver(),
                seed.serialNo(), seed.dataRepairDate(), seed.remark(), seed.affectedTransactionCount(), seed.issueId(), seed.issueKey(), seed.historicalOccurrenceCount(),
                seed.firstOccurrenceDate(), seed.lastOccurrenceDate(), seed.importedAt(), seed.issueStatus(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 4),
                seed.cooperationPersonUsername(), seed.cooperationPersonRealName());
        dao.insertCurrent(seed);
        merge.merge(workbook(row("FIXED-2", "new")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system());
        Map<String, Object> current = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null)).get(0);
        assertEquals("新建", current.get("issue_status"));
        assertEquals("代码问题", current.get("issue_type"));
        assertEquals("a", current.get("initial_analysis"));
        assertEquals("s", current.get("final_solution"));
        assertEquals("alice", current.get("cooperation_person_username"));
        org.junit.jupiter.api.Assertions.assertNull(current.get("defect_repair_date"));
    }

    @Test
    void historyInsertFailureRollsBackCurrentProjection() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        ReplayIssueDao failingDao = new ReplayIssueDao(jdbc) {
            @Override
            public void insertHistoryForRound(Long replayIssueId, String issueKey, String operationType, LocalDateTime operationAt,
                                              ReplayIssueOperator operator, LocalDate importDate, String coverageRound,
                                              String sourceSheet, Integer sourceRow, String beforeSnapshot,
                                              String afterSnapshot, String incomingSnapshot, Long contextRoundId) {
                throw new IllegalStateException("history unavailable");
            }
        };
        assertThrows(IllegalStateException.class, () -> new ReplayIssueMergeService(failingDao)
                .merge(workbook(row("ROLLBACK", "new")), LocalDate.of(2026, 8, 5), ReplayIssueOperator.system()));
        assertEquals(0, failingDao.count(new com.axonlink.ai.replay.dto.ReplayIssueQuery(10, 0, null, null, null, null, null)));
    }

    private ReplayIssueExcelParser.ParsedWorkbook workbook(ReplayIssueRow... rows) {
        return new ReplayIssueExcelParser.ParsedWorkbook(List.of(rows), Map.of("公共组", rows.length), 0, rows.length);
    }

    private ReplayIssueDao daoForSchema() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        return new ReplayIssueDao(jdbc);
    }

    private ReplayIssueRow row(String key, String description) {
        return new ReplayIssueRow(null, "公共组", "公共组", false, 1, "公共组", "1", "RPT20260805-000000-0000", "6208", "交易", "交易级",
                "2026-08-05", "字段", description, "负责人", "", "", "", "", "", "", "S", "", "", "1", "I", key,
                "0", "", "", LocalDateTime.of(2026, 8, 5, 1, 0), ReplayIssueStatus.OPEN, LocalDate.of(2026, 8, 5), null, null, null);
    }

    private ReplayIssueRow lifecycle(ReplayIssueRow row, ReplayIssueStatus status, String type, String analysis,
                                     String solution, String user) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), type, analysis, solution, row.resolvedDate(), row.cooperationGroup(), row.resolver(), row.serialNo(), row.dataRepairDate(),
                row.remark(), row.affectedTransactionCount(), row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), status, row.importDate(), null, user, "Alice");
    }

    private ReplayIssueRow withRemark(ReplayIssueRow row, String remark) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(), row.cooperationGroup(), row.resolver(),
                row.serialNo(), row.dataRepairDate(), remark, row.affectedTransactionCount(), row.issueId(), row.issueKey(), row.historicalOccurrenceCount(),
                row.firstOccurrenceDate(), row.lastOccurrenceDate(), row.importedAt(), row.issueStatus(), row.importDate(), row.defectRepairDate(),
                row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo());
    }

    private ReplayIssueRow withBatch(ReplayIssueRow row, String batchNo) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                batchNo, row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(), row.cooperationGroup(), row.resolver(),
                row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(), row.issueId(), row.issueKey(), row.historicalOccurrenceCount(),
                row.firstOccurrenceDate(), row.lastOccurrenceDate(), row.importedAt(), row.issueStatus(), row.importDate(), row.defectRepairDate(),
                row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo(), row.reviewStatus(), row.reviewerUsername(),
                row.reviewerRealName(), row.reviewedAt());
    }

    private ReplayIssueRow withReviewAndDefectDate(ReplayIssueRow row, ReplayIssueReviewStatus status,
                                                   String reviewerUsername, String reviewerRealName,
                                                   LocalDateTime reviewedAt, LocalDate defectRepairDate) {
        return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(), row.domain(), row.sequenceNo(),
                row.batchNo(), row.transactionCode(), row.transactionName(), row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(), row.cooperationGroup(), row.resolver(),
                row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(), row.issueId(), row.issueKey(), row.historicalOccurrenceCount(),
                row.firstOccurrenceDate(), row.lastOccurrenceDate(), row.importedAt(), row.issueStatus(), row.importDate(), defectRepairDate,
                row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo(), status,
                reviewerUsername, reviewerRealName, reviewedAt, row.plannedCompletionDate());
    }
}
