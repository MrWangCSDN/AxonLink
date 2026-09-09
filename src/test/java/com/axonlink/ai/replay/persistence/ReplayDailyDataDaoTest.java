package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayDailyDataDaoTest {

    private ReplayDailyDataDao dao;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V56__dii_replay_daily_import_data.sql"))
                .execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V57__dii_replay_daily_report_snapshot.sql"))
                .execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V58__dii_replay_daily_report_mail.sql"))
                .execute(jdbc.getDataSource());
        dao = new ReplayDailyDataDao(jdbc);
    }

    @Test
    void replacesOnlyTheRequestedBatchAndPreservesSourceOrderAndTotals() {
        LocalDateTime importedAt = LocalDateTime.of(2026, 9, 5, 10, 0);
        ReplayDailyWorkbookData other = data("RPT-OTHER", 9);
        ReplayDailyWorkbookData first = data("RPT-BATCH", 1);
        ReplayDailyWorkbookData second = data("RPT-BATCH", 5);

        dao.replaceBatch(other, importedAt);
        dao.replaceBatch(first, importedAt);
        dao.replaceBatch(second, importedAt.plusMinutes(1));

        assertEquals(second.summaries(), dao.findSummaries("RPT-BATCH"));
        assertEquals(second.comparisons(), dao.findComparisons("RPT-BATCH"));
        assertEquals(second.coverageSummaries(), dao.findCoverageSummaries("RPT-BATCH"));
        assertEquals(second.coverageDetails(), dao.findCoverageDetails("RPT-BATCH"));
        assertEquals(other.summaries(), dao.findSummaries("RPT-OTHER"));
        assertEquals(ReplayDailyRowType.TOTAL,
                dao.findComparisons("RPT-BATCH").get(1).rowType());
        assertEquals(List.of(ReplayDailyRowType.DOMAIN_DETAIL, ReplayDailyRowType.DOMAIN_TOTAL,
                        ReplayDailyRowType.GROUP_DETAIL, ReplayDailyRowType.GROUP_TOTAL),
                dao.findCoverageSummaries("RPT-BATCH").stream().map(ReplayCoverageSummaryRow::rowType).toList());
    }

    @Test
    void rejectsBlankBatchBeforeDeletingAnything() {
        ReplayDailyWorkbookData valid = data("RPT-BATCH", 1);
        dao.replaceBatch(valid, LocalDateTime.now());

        ReplayDailyWorkbookData invalid = new ReplayDailyWorkbookData(" ", valid.summaries(),
                valid.comparisons(), valid.coverageSummaries(), valid.coverageDetails());

        assertThrows(IllegalArgumentException.class,
                () -> dao.replaceBatch(invalid, LocalDateTime.now()));
        assertEquals(valid.summaries(), dao.findSummaries("RPT-BATCH"));
    }

    @Test
    void listsDistinctBatchesWithPreviousBatchInsideTheSameFamily() {
        dao.replaceBatch(data("RPT20260901-01", 1), LocalDateTime.of(2026, 9, 1, 9, 0));
        dao.replaceBatch(data("DZ20260901-01", 2), LocalDateTime.of(2026, 9, 1, 10, 0));
        dao.replaceBatch(data("LEGACY20260901-01", 3), LocalDateTime.of(2026, 9, 1, 11, 0));
        dao.replaceBatch(data("RPT20260902-01", 4), LocalDateTime.of(2026, 9, 2, 9, 0));
        dao.replaceBatch(data("DZ20260902-01", 5), LocalDateTime.of(2026, 9, 2, 10, 0));
        dao.replaceBatch(data("RPT20260902-01", 6), LocalDateTime.of(2026, 9, 2, 11, 0));
        LocalDateTime generatedAt = LocalDateTime.of(2026, 9, 7, 10, 30);
        dao.saveReportSnapshot(snapshot("RPT20260902-01", generatedAt, new byte[]{1, 2, 3}));
        ReplayDailyReportMailDao mailDao = new ReplayDailyReportMailDao(jdbc);
        mailDao.markSending("RPT20260902-01", "标题", "正文", "sender@example.com",
                List.of("to@example.com"), List.of());
        mailDao.markSent("RPT20260902-01");

        List<ReplayDailyBatch> batches = dao.findBatchesRecentFirst();
        Map<String, ReplayDailyBatch> batchesByNo = batches.stream()
                .collect(Collectors.toMap(ReplayDailyBatch::batchNo, Function.identity()));

        assertEquals(List.of("RPT20260902-01", "DZ20260902-01", "DZ20260901-01", "RPT20260901-01"),
                new ArrayList<>(batches.stream().map(ReplayDailyBatch::batchNo).toList()));
        assertEquals("RPT20260901-01", batchesByNo.get("RPT20260902-01").previousBatchNo());
        assertEquals("DZ20260901-01", batchesByNo.get("DZ20260902-01").previousBatchNo());
        assertNull(batchesByNo.get("RPT20260901-01").previousBatchNo());
        assertTrue(batchesByNo.get("RPT20260902-01").generated());
        assertEquals(generatedAt, batchesByNo.get("RPT20260902-01").generatedAt());
        assertEquals("SENT", batchesByNo.get("RPT20260902-01").mailStatus());
        assertTrue(batchesByNo.get("RPT20260902-01").mailSentAt() != null);
        assertNull(batchesByNo.get("RPT20260902-01").mailFailureMessage());
        assertFalse(batchesByNo.get("DZ20260902-01").generated());
        assertNull(batchesByNo.get("DZ20260902-01").generatedAt());
        assertEquals("UNSENT", batchesByNo.get("DZ20260902-01").mailStatus());
        assertEquals(1L, batches.stream().filter(batch -> batch.batchNo().equals("RPT20260902-01")).count());
        assertFalse(batches.stream().anyMatch(batch -> batch.batchNo().startsWith("LEGACY")));
        assertTrue(dao.batchExists("RPT20260902-01"));
        assertFalse(dao.batchExists("MISSING-BATCH"));
        assertFalse(dao.batchExists(" "));
    }

    @Test
    void listsOnlyGeneratedBatchesInStableFamilyOrderForWeeklyReports() {
        dao.replaceBatch(data("RPT20260901-01", 1), LocalDateTime.of(2026, 9, 1, 9, 0));
        dao.replaceBatch(data("DZ20260901-01", 2), LocalDateTime.of(2026, 9, 1, 10, 0));
        dao.replaceBatch(data("RPT20260903-01", 3), LocalDateTime.of(2026, 9, 3, 9, 0));
        dao.replaceBatch(data("RPT20260908-01", 4), LocalDateTime.of(2026, 9, 8, 9, 0));
        dao.saveReportSnapshot(snapshot("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 18, 0), new byte[]{1}));
        dao.saveReportSnapshot(snapshot("RPT20260908-01", LocalDateTime.of(2026, 9, 8, 18, 0), new byte[]{8}));
        dao.saveReportSnapshot(snapshot("DZ20260901-01", LocalDateTime.of(2026, 9, 1, 19, 0), new byte[]{9}));

        List<ReplayDailyBatch> generated = dao.findGeneratedBatchesInFamilyOrder();

        assertEquals(List.of("DZ20260901-01", "RPT20260901-01", "RPT20260908-01"),
                generated.stream().map(ReplayDailyBatch::batchNo).toList());
        assertTrue(generated.stream().allMatch(ReplayDailyBatch::generated));
        assertFalse(generated.stream().anyMatch(batch -> "RPT20260903-01".equals(batch.batchNo())));
    }

    @Test
    void batchListExcludesBarePrefixesAcceptedBySqlLike() {
        dao.replaceBatch(data("RPT", 1), LocalDateTime.of(2026, 9, 1, 9, 0));
        dao.replaceBatch(data("DZ", 2), LocalDateTime.of(2026, 9, 1, 10, 0));
        dao.replaceBatch(data("RPT1", 3), LocalDateTime.of(2026, 9, 1, 11, 0));
        dao.replaceBatch(data("DZ1", 4), LocalDateTime.of(2026, 9, 1, 12, 0));

        List<String> batches = dao.findBatchesRecentFirst().stream()
                .map(ReplayDailyBatch::batchNo)
                .toList();

        assertEquals(List.of("DZ1", "RPT1"), batches);
    }

    @Test
    void savesReplacesReadsAndDeletesReportSnapshots() {
        LocalDateTime firstGeneratedAt = LocalDateTime.of(2026, 9, 7, 10, 30);
        LocalDateTime secondGeneratedAt = firstGeneratedAt.plusMinutes(5);
        dao.saveReportSnapshot(snapshot("RPT20260907-02", firstGeneratedAt, new byte[]{1, 2, 3}));
        dao.saveReportSnapshot(snapshot("DZ20260907-02", firstGeneratedAt, new byte[]{4}));
        dao.saveReportSnapshot(snapshot("RPT20260907-02", secondGeneratedAt, new byte[]{9, 8}));

        ReplayDailyReportSnapshot saved = dao.findReportSnapshot("RPT20260907-02").orElseThrow();
        assertEquals("RPT20260907-02日报.xlsx", saved.fileName());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", saved.contentType());
        assertArrayEquals(new byte[]{9, 8}, saved.content());
        assertEquals(2L, saved.fileSize());
        assertEquals(secondGeneratedAt, saved.generatedAt());

        assertEquals(2, dao.deleteAllReportSnapshots());
        assertTrue(dao.findReportSnapshot("RPT20260907-02").isEmpty());
        assertTrue(dao.findReportSnapshot("DZ20260907-02").isEmpty());
    }

    private static ReplayDailyReportSnapshot snapshot(String batchNo, LocalDateTime generatedAt, byte[] content) {
        return new ReplayDailyReportSnapshot(batchNo, batchNo + "日报.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content, content.length, generatedAt);
    }

    private static ReplayDailyWorkbookData data(String batch, int seed) {
        return new ReplayDailyWorkbookData(batch,
                List.of(new ReplayDailySummaryRow(batch, "公共组", 10L + seed, 100L + seed,
                        3L, 80L, 2L, new BigDecimal("0.9123"), 7L, seed, "{\"kind\":\"summary\"}")),
                List.of(
                        new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL, "A001", "S001",
                                "查询", "开发", "行内", "公共组", 100L + seed, 1L, 2L, 3L, 4L,
                                80L, 5L, new BigDecimal("0.91"), new BigDecimal("0.92"),
                                new BigDecimal("12.3"), new BigDecimal("23.4"), seed, "{\"kind\":\"detail\"}"),
                        new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.TOTAL, null, null,
                                null, null, null, null, 100L + seed, 1L, 2L, 3L, 4L,
                                80L, 5L, new BigDecimal("0.91"), new BigDecimal("0.92"),
                                new BigDecimal("12.3"), new BigDecimal("23.4"), seed + 1, "{\"kind\":\"total\"}")),
                List.of(
                        new ReplayCoverageSummaryRow(batch, ReplayDailyRowType.DOMAIN_DETAIL, "公共组", 100L, 80L,
                                20L, 2L, 3L, 4L, new BigDecimal("0.80"), seed, "{\"kind\":\"coverage\"}"),
                        new ReplayCoverageSummaryRow(batch, ReplayDailyRowType.DOMAIN_TOTAL, "合计", 100L, 80L,
                                20L, 2L, 3L, 4L, new BigDecimal("0.80"), seed + 1, "{\"kind\":\"domainTotal\"}"),
                        new ReplayCoverageSummaryRow(batch, ReplayDailyRowType.GROUP_DETAIL, "零售组", 100L, 80L,
                                20L, 2L, 3L, 4L, new BigDecimal("0.80"), seed + 2, "{\"kind\":\"group\"}"),
                        new ReplayCoverageSummaryRow(batch, ReplayDailyRowType.GROUP_TOTAL, "合计", 100L, 80L,
                                20L, 2L, 3L, 4L, new BigDecimal("0.80"), seed + 3, "{\"kind\":\"groupTotal\"}")),
                List.of(new ReplayCoverageDetailRow(batch, "A001", "查询", "公共组", "S001", "R001",
                        "是", LocalDate.of(2026, 8, 2), 100L, "已发送", "", "开发", "行内",
                        seed, "{\"kind\":\"coverageDetail\"}")));
    }
}
