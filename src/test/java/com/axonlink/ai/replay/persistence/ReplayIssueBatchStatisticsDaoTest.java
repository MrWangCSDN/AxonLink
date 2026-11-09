package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.service.ReplayIssueExcelParser;
import com.axonlink.ai.replay.service.ReplayIssueMergeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueBatchStatisticsDaoTest {
    private static final String FIRST = "RPT20260901-01";
    private static final String SECOND = "RPT20260902-01";
    private static final LocalDateTime FIRST_TIME = LocalDateTime.of(2026, 9, 1, 9, 0);
    private static final LocalDateTime SECOND_TIME = LocalDateTime.of(2026, 9, 2, 9, 0);
    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
    }

    @Test
    void preservesEachBatchsOwnQuantityCodeReviewAndRepairDate() {
        dao.beginBatchStatistics(FIRST, FIRST_TIME);
        insertIssue(1L, FIRST, "006208", 9L, "PENDING", null);
        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);

        dao.beginBatchStatistics(SECOND, SECOND_TIME);
        jdbc.update("UPDATE dii_replay_issue SET batch_no=?,transaction_code='6208',affected_transaction_count='25',"
                + "review_status='APPROVED',defect_repair_date='2026-09-02' WHERE id=1", SECOND);
        dao.upsertOccurrenceBatch(1L, "stat-1", SECOND, SECOND_TIME, ReplayIssueStatus.NO_ACTION);

        var first = dao.findBatchReportIssueStatistics(FIRST).get(0);
        var second = dao.findBatchReportIssueStatistics(SECOND).get(0);

        assertEquals(9L, first.affectedTransactionCount());
        assertEquals("006208", first.transactionCode());
        assertEquals(ReplayIssueReviewStatus.PENDING, first.reviewStatus());
        assertNull(first.defectRepairDate());
        assertEquals(25L, second.affectedTransactionCount());
        assertEquals("6208", second.transactionCode());
        assertEquals(ReplayIssueReviewStatus.APPROVED, second.reviewStatus());
        assertEquals(LocalDate.of(2026, 9, 2), second.defectRepairDate());
    }

    @Test
    void refreshesOpenApprovalButNeverRewritesAFrozenBatchForALateApproval() {
        dao.beginBatchStatistics(FIRST, FIRST_TIME);
        insertIssue(1L, FIRST, "006208", 9L, "PENDING", null);
        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);
        approveAndRecord(1L, FIRST, FIRST_TIME.plusHours(1));
        var approved = dao.findBatchReportIssueStatistics(FIRST).get(0);
        assertEquals(ReplayIssueReviewStatus.APPROVED, approved.reviewStatus());
        assertEquals(LocalDate.of(2026, 9, 1), approved.defectRepairDate());

        insertIssue(2L, FIRST, "006209", 4L, "PENDING", null);
        dao.upsertOccurrenceBatch(2L, "stat-2", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);
        dao.beginBatchStatistics(SECOND, SECOND_TIME);
        approveAndRecord(2L, FIRST, SECOND_TIME.plusHours(1));

        var late = dao.findBatchReportIssueStatistics(FIRST).get(1);
        assertEquals(ReplayIssueReviewStatus.PENDING, late.reviewStatus());
        assertNull(late.defectRepairDate());
        assertTrue(dao.findBatchReportIssueStatistics(SECOND).isEmpty(), "没有出现的问题不补造新批次成员");
    }

    @Test
    void keepsFamiliesIndependentAndExplicitlyReopensTheRequestedBatch() {
        dao.beginBatchStatistics(FIRST, FIRST_TIME);
        dao.beginBatchStatistics("DZ20260901-01", FIRST_TIME);
        dao.beginBatchStatistics(SECOND, SECOND_TIME);
        assertEquals("FROZEN", state(FIRST));
        assertEquals("OPEN", state(SECOND));
        assertEquals("OPEN", state("DZ20260901-01"));
        dao.beginBatchStatistics(FIRST, SECOND_TIME.plusHours(1));
        dao.beginBatchStatistics(FIRST, SECOND_TIME.plusHours(1));
        assertEquals("OPEN", state(FIRST));
        assertEquals("FROZEN", state(SECOND));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_statistics_batch WHERE status='OPEN'", Integer.class));
        assertTrue(dao.findBatchReportIssueStatistics(FIRST).isEmpty());
    }

    @Test
    void rollsBackActivationAndSnapshotsTogetherWithTheImportTransaction() {
        dao.beginBatchStatistics(FIRST, FIRST_TIME);
        insertIssue(1L, FIRST, "006208", 9L, "PENDING", null);
        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);

        assertThrows(IllegalStateException.class, () -> dao.inTransaction(current -> {
            current.beginBatchStatistics(SECOND, SECOND_TIME);
            jdbc.update("UPDATE dii_replay_issue SET batch_no=?,affected_transaction_count='25' WHERE id=1", SECOND);
            current.upsertOccurrenceBatch(1L, "stat-1", SECOND, SECOND_TIME, ReplayIssueStatus.NO_ACTION);
            throw new IllegalStateException("import failed");
        }));

        assertEquals("OPEN", state(FIRST));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_statistics_batch WHERE batch_no=?", Integer.class, SECOND));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue_occurrence_batch WHERE batch_name=?", Integer.class, SECOND));
        assertEquals(9L, dao.findBatchReportIssueStatistics(FIRST).get(0).affectedTransactionCount());
        assertEquals("9", jdbc.queryForObject("SELECT affected_transaction_count FROM dii_replay_issue WHERE id=1", String.class));
    }

    @Test
    void freezesOnlyAfterAnOpenCaptureTransactionHasCommitted() throws Exception {
        dao.beginBatchStatistics(FIRST, FIRST_TIME);
        insertIssue(1L, FIRST, "006208", 9L, "PENDING", null);
        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);
        var captured = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var freezeStarted = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var capturing = workers.submit(() -> dao.inTransaction(current -> {
                jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='11' WHERE id=1");
                new ReplayIssueBatchStatisticsDao(jdbc).captureCurrent(1L, FIRST, FIRST_TIME.plusHours(1));
                captured.countDown();
                try {
                    if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("capture wait timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            }));
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            var freezing = workers.submit(() -> {
                freezeStarted.countDown();
                new ReplayIssueDao(jdbc).beginBatchStatistics(SECOND, SECOND_TIME);
            });
            assertTrue(freezeStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> freezing.get(300, TimeUnit.MILLISECONDS),
                    "冻结必须等待正在记录的OPEN批次快照事务提交");
            allowCommit.countDown();
            capturing.get(5, TimeUnit.SECONDS);
            freezing.get(5, TimeUnit.SECONDS);
            assertEquals("FROZEN", state(FIRST));
            assertEquals(11L, dao.findBatchReportIssueStatistics(FIRST).get(0).affectedTransactionCount());
        } finally {
            allowCommit.countDown();
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void refusesMissingMetadataOrAnyIncompleteMemberInsteadOfUsingCurrentValues() {
        insertIssue(1L, FIRST, "006208", 9L, "PENDING", null);
        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);
        assertIncomplete(FIRST);
        dao.beginBatchStatistics(FIRST, FIRST_TIME);
        assertIncomplete(FIRST);
        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);
        assertEquals(1, dao.findBatchReportIssueStatistics(FIRST).size());
        jdbc.update("UPDATE dii_replay_issue_occurrence_batch SET statistics_recorded_at=NULL WHERE batch_name=?", FIRST);
        assertIncomplete(FIRST);
    }

    @Test
    void validatesEverySnapshotKeyAndRejectsWrongTypesEnumsDatesAndIssueIds() throws Exception {
        dao.beginBatchStatistics(FIRST, FIRST_TIME);
        insertIssue(1L, FIRST, "006208", 9L, "PENDING", null);
        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);
        ObjectNode valid = snapshot();
        var mutations = List.<java.util.function.Consumer<ObjectNode>>of(
                node -> node.remove("reviewStatus"),
                node -> node.remove("transactionCode"),
                node -> node.putNull("transactionCode"),
                node -> node.put("transactionCode", "   "),
                node -> node.putNull("issueLevel"),
                node -> node.put("issueLevel", " "),
                node -> node.put("issueLevel", "unknown"),
                node -> node.put("sandbox", "false"),
                node -> node.put("affectedTransactionCount", "9"),
                node -> node.put("affectedTransactionCount", -1),
                node -> node.put("issueId", 2),
                node -> node.put("reviewStatus", "UNKNOWN"),
                node -> node.put("defectRepairDate", "not-a-date"));
        for (var mutate : mutations) {
            ObjectNode corrupt = valid.deepCopy();
            mutate.accept(corrupt);
            jdbc.update("UPDATE dii_replay_issue_occurrence_batch SET statistics_snapshot=? WHERE batch_name=?", corrupt.toString(), FIRST);
            assertIncomplete(FIRST);
        }
        jdbc.update("UPDATE dii_replay_issue_occurrence_batch SET statistics_snapshot=? WHERE batch_name=?", "not-json", FIRST);
        assertIncomplete(FIRST);
        jdbc.update("UPDATE dii_replay_issue_occurrence_batch SET statistics_snapshot=? WHERE batch_name=?", valid.toString(), FIRST);
        assertEquals(ReplayIssueReviewStatus.PENDING, dao.findBatchReportIssueStatistics(FIRST).get(0).reviewStatus());
        assertNull(dao.findBatchReportIssueStatistics(FIRST).get(0).defectRepairDate(), "显式null保留未闭环含义");
    }

    @Test
    void capturesLegacyNonNumericCountsWithoutRejectingImportsOrInventingAZeroCount() throws Exception {
        dao.beginBatchStatistics(FIRST, FIRST_TIME);
        insertIssue(1L, FIRST, "006208", 9L, "PENDING", null);
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='旧版统计说明' WHERE id=1");

        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);

        assertEquals("旧版统计说明", snapshot().get("affectedTransactionCount").textValue());
        assertIncomplete(FIRST);
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='-1' WHERE id=1");
        dao.upsertOccurrenceBatch(1L, "stat-1", FIRST, FIRST_TIME, ReplayIssueStatus.NO_ACTION);
        assertEquals("-1", snapshot().get("affectedTransactionCount").textValue());
        assertIncomplete(FIRST);
    }

    @Test
    void aRealMergeCapturesAppearedIssuesAndAZeroRowBatchFreezesThePreviousState() {
        var source = ReplayIssueTestFixtures.row("公共组", false, 1, "006208", "问题");
        var workbook = new ReplayIssueExcelParser.ParsedWorkbook(List.of(source), Map.of("公共组", 1), 0, 1);
        var merge = new ReplayIssueMergeService(dao);
        merge.merge(workbook, LocalDate.of(2026, 8, 20), ReplayIssueOperator.system(), "statistics-first");
        String originalBatch = source.batchNo();
        var original = dao.findBatchReportIssueStatistics(originalBatch).get(0);
        assertEquals("新建", original.issueStatus());
        assertEquals("006208", original.transactionCode());
        assertFalse(original.reopenedAfterFixed());

        String emptyBatch = "RPT20260821-01";
        var empty = new ReplayIssueExcelParser.ParsedWorkbook(List.of(), Map.of("公共组", 0), 0, 0);
        dao.inTransaction(current -> merge.mergeWithinTransaction(current, empty, LocalDate.of(2026, 8, 21),
                ReplayIssueOperator.system(), "statistics-empty", emptyBatch));

        assertEquals("FROZEN", state(originalBatch));
        assertTrue(dao.findBatchReportIssueStatistics(emptyBatch).isEmpty());
        var frozen = dao.findBatchReportIssueStatistics(originalBatch).get(0);
        assertEquals("新建", frozen.issueStatus());
        assertNull(frozen.defectRepairDate());
        assertEquals("已修复", dao.findDailyReportIssueStatistics(originalBatch).get(0).issueStatus(), "旧current读取仍兼容");
    }

    private void approveAndRecord(long id, String batch, LocalDateTime at) {
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED',defect_repair_date=? WHERE id=?", at.toLocalDate(), id);
        jdbc.update("INSERT INTO dii_replay_issue_history(replay_issue_id,issue_key,operation_type,operation_at) VALUES (?,?,?,?)",
                id, "stat-" + id, "审核通过", at);
        dao.updateLatestHistoryOccurrenceBatch(id, at, batch);
    }

    private String state(String batch) {
        return jdbc.queryForObject("SELECT status FROM dii_replay_issue_statistics_batch WHERE batch_no=?", String.class, batch);
    }

    private void assertIncomplete(String batch) {
        var exception = assertThrows(ReplayIssueBatchStatisticsIncompleteException.class,
                () -> dao.findBatchReportIssueStatistics(batch));
        assertTrue(exception.getMessage().contains("批次历史统计不完整：" + batch));
        assertFalse(exception.getMessage().contains("issueId"));
    }

    private ObjectNode snapshot() throws Exception {
        String raw = jdbc.queryForObject("SELECT statistics_snapshot FROM dii_replay_issue_occurrence_batch WHERE batch_name=?", String.class, FIRST);
        var json = new ObjectMapper();
        JsonNode node = json.readTree(raw);
        if (node.isTextual()) node = json.readTree(node.textValue());
        return (ObjectNode) node;
    }

    private void insertIssue(long id, String batch, String code, long count, String review, LocalDate repaired) {
        jdbc.update("INSERT INTO dii_replay_issue(id,source_sheet,group_name,is_sandbox,row_order,domain,batch_no,"
                        + "transaction_code,issue_level,field_name,issue_type,issue_status,affected_transaction_count,"
                        + "review_status,defect_repair_date,issue_key,imported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "公共组", "公共组", false, 1, "公共组", batch, code, "交易级", "字段", "合理差异", "无需处理",
                Long.toString(count), review, repaired, "stat-" + id, FIRST_TIME);
    }
}
