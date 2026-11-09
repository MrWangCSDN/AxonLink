package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyIssueStatisticRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportMailDao;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplayWeeklyReportServiceTest {

    private JdbcTemplate jdbc;
    private ReplayDailyDataDao dailyDataDao;
    private ReplayIssueDao issueDao;
    private ReplayWeeklyReportDao weeklyReportDao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        dailyDataDao = mock(ReplayDailyDataDao.class);
        issueDao = mock(ReplayIssueDao.class);
        weeklyReportDao = mock(ReplayWeeklyReportDao.class);
    }

    @Test
    void regeneratingALegacySnapshotReplacesItsCombinedSuccessColumnWithTheStoredSplitCounts() throws Exception {
        ReplayIssueTestFixtures.createSchema(jdbc);
        var realDaily = new ReplayDailyDataDao(jdbc);
        var realIssues = new ReplayIssueDao(jdbc);
        var realReports = new ReplayWeeklyReportDao(jdbc);
        String start = "RPT20260901-01", end = "RPT20260908-01";
        var at = LocalDateTime.of(2026, 9, 1, 9, 0);
        seedHistoricalBatch(realDaily, start, at, 60L, 20L);
        seedHistoricalBatch(realDaily, end, at.plusDays(7), 70L, 10L);
        var originalDetails = realDaily.findComparisons(end);
        byte[] legacy;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var output = new java.io.ByteArrayOutputStream()) {
            workbook.createSheet("接口比对明细").createRow(0).createCell(0).setCellValue("二者均成功");
            workbook.write(output);
            legacy = output.toByteArray();
        }
        realReports.saveSnapshot(new ReplayWeeklyReportSnapshot(start, end, "旧周报.xlsx", "xlsx",
                legacy, legacy.length, at.plusDays(7)));
        var reports = new ReplayWeeklyReportService(realDaily, realIssues, realReports,
                new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter(), jdbc);
        assertArrayEquals(legacy, reports.generate(start, end).content(), "普通下载保留原生成快照");

        var regenerated = reports.regenerate(start, end);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(regenerated.content()))) {
            var sheet = workbook.getSheet("接口比对明细");
            var header = sheet.getRow(0);
            var labels = java.util.stream.IntStream.range(0, header.getLastCellNum())
                    .mapToObj(index -> header.getCell(index).getStringCellValue()).toList();
            assertEquals(19, labels.size());
            assertEquals("二者均成功且无字段差异", labels.get(8));
            assertEquals("二者均成功且有字段差异", labels.get(9));
            assertFalse(labels.contains("二者均成功"));
            assertEquals(end, sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(70d, sheet.getRow(1).getCell(8).getNumericCellValue());
            assertEquals(10d, sheet.getRow(1).getCell(9).getNumericCellValue());
        }
        assertEquals(originalDetails, realDaily.findComparisons(end), "重新导出不改变接口原始数据");
        assertArrayEquals(regenerated.content(), realReports.findSnapshot(start, end).orElseThrow().content());
        assertArrayEquals(regenerated.content(), reports.generate(start, end).content());
    }

    @Test
    void keepsOriginalRatesForDomainsWithoutEffectiveInterfaceDetails() throws Exception {
        String start = "RPT20260901-01", end = "RPT20260908-01";
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(batch(start, 1), batch(end, 8)));
        when(dailyDataDao.findSummaries(start)).thenReturn(List.of(summary(start, "START")));
        when(dailyDataDao.findSummaries(end)).thenReturn(List.of(summary(end, "END"),
                volumeSummary(end, "贷款组", 100L), volumeSummary(end, "零交易领域", 0L)));
        // TOTAL-only is a supported imported sheet, but does not establish a transaction-code scope.
        when(dailyDataDao.findComparisons(start)).thenReturn(List.of(new ReplayInterfaceComparisonRow(
                start, ReplayDailyRowType.TOTAL, null, null, null, null, null, null,
                100L, 0L, 0L, 2L, 0L, 80L, 1L, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}", 60L, 20L)));
        when(dailyDataDao.findComparisons(end)).thenReturn(List.of(splitComparison(end, 70L, 10L)));

        var generated = service(new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter()).generate(start, end);
        var view = new ReplayReportSummaryCodec().decode(generated.summaryViewJson());
        var missing = view.rows().stream().filter(row -> "贷款组".equals(row.domain())).findFirst().orElseThrow();
        for (String key : List.of("noAction", "c528SuccessCcbsFail",
                "c528FailCcbsSuccess", "bothFailDiffCode")) {
            org.junit.jupiter.api.Assertions.assertNull(missing.values().get(key), key);
            org.junit.jupiter.api.Assertions.assertNull(view.totalRow().values().get(key), key);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(missing.values().get("successRate"));
        org.junit.jupiter.api.Assertions.assertNotNull(missing.values().get("matchPassRate"));
        org.junit.jupiter.api.Assertions.assertNotNull(view.totalRow().values().get("successRate"));
        org.junit.jupiter.api.Assertions.assertNotNull(view.totalRow().values().get("matchPassRate"));
        assertEquals(100L, missing.values().get("sentTransactionCount"));
        assertEquals(0L, missing.values().get("issueTotal"));
        var zero = view.rows().stream().filter(row -> "零交易领域".equals(row.domain())).findFirst().orElseThrow();
        assertEquals(0L, zero.values().get("noAction"));
        var covered = view.rows().stream().filter(row -> "公共组".equals(row.domain())).findFirst().orElseThrow();
        assertEquals(0L, covered.values().get("noAction"));
        assertTrue(ReplayReportCalculationContext.from(view).notices().stream().anyMatch(text -> text.contains("原算法")));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(generated.content()))) {
            var previous = findSummaryRow(workbook.getSheet("汇总信息"), start);
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, previous.getCell(9).getCellType());
            assertEquals(0.79797980d, previous.getCell(12).getNumericCellValue(), 1e-9);
            assertEquals(0d, previous.getCell(13).getNumericCellValue());
        }
    }

    @Test
    void generatesTheSelectedTwoBatchesWhenTheOlderIssueHistoryIsUnavailable() throws Exception {
        String start = "RPT20261029-000247-2239", end = "RPT20261104-194516-0435";
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(batch(start, 1), batch(end, 8)));
        when(dailyDataDao.findSummaries(start)).thenReturn(List.of(summary(start, "START")));
        when(dailyDataDao.findSummaries(end)).thenReturn(List.of(summary(end, "END")));
        when(dailyDataDao.findComparisons(start)).thenReturn(List.of(splitComparison(start, 60L, 20L)));
        when(dailyDataDao.findComparisons(end)).thenReturn(List.of(splitComparison(end, 70L, 10L)));
        when(issueDao.findBatchReportIssueStatistics(start)).thenThrow(
                new com.axonlink.ai.replay.persistence.ReplayIssueBatchStatisticsIncompleteException(start));
        when(issueDao.findBatchReportIssueStatistics(end)).thenReturn(List.of());
        when(issueDao.findCurrentReportIssues(start, end)).thenReturn(List.of(
                latestIssue(1L, "quantity-missing", end, "交易级", "528成功ccbs失败", "25")));
        // A damaged observation proves START membership, but cannot borrow END's 25 as its quantity.
        when(issueDao.findBatchOriginalReportQuantities(start)).thenReturn(List.of(
                new com.axonlink.ai.replay.dto.ReplayBatchOriginalIssueQuantity(1L, "quantity-missing",
                        com.axonlink.ai.replay.dto.ReplayBatchOriginalIssueQuantity.Presence.UNKNOWN,
                        null, null, null, null)));

        var generated = service(new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter())
                .generate(start, end);

        var view = new ReplayReportSummaryCodec().decode(generated.summaryViewJson());
        assertEquals(start, view.startBatchNo());
        assertEquals(end, view.endBatchNo());
        org.junit.jupiter.api.Assertions.assertNull(view.totalRow().values().get("previousUnresolvedTotal"));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(generated.content()))) {
            var sheet = workbook.getSheet("汇总信息");
            var oldRow = findSummaryRow(sheet, start);
            var newRow = findSummaryRow(sheet, end);
            assertEquals(100d, oldRow.getCell(3).getNumericCellValue());
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, oldRow.getCell(9).getCellType());
            assertEquals(0.82828283d, oldRow.getCell(11).getNumericCellValue(), 1e-9);
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, oldRow.getCell(13).getCellType());
            assertEquals(25d, newRow.getCell(9).getNumericCellValue());
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, newRow.getCell(14).getCellType());
            assertTrue(sheetText(sheet).contains("最新审核"));
        }
    }

    @Test
    void propagatesUnexpectedHistoryReadFailuresWithoutSavingAPartialReport() {
        String start = "RPT20260901-01", end = "RPT20260908-01";
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(batch(start, 1), batch(end, 8)));
        when(dailyDataDao.findSummaries(start)).thenReturn(List.of(summary(start, "START")));
        when(dailyDataDao.findSummaries(end)).thenReturn(List.of(summary(end, "END")));
        var cause = new org.springframework.dao.DataAccessResourceFailureException("connection lost");
        when(issueDao.findBatchReportIssueStatistics(start)).thenThrow(cause);

        assertEquals(cause, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> service(new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter()).generate(start, end)));
        verify(weeklyReportDao, never()).saveSnapshot(any());
    }

    @Test
    void generatesAcrossIntermediateBatchesWithStartAsPreviousAndEndDetailsOnly() throws Exception {
        when(weeklyReportDao.findSnapshot("RPT20260901-01", "RPT20260908-01")).thenReturn(Optional.empty());
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(
                batch("RPT20260901-01", 1), batch("RPT20260903-01", 3), batch("RPT20260908-01", 8)));
        when(dailyDataDao.findSummaries("RPT20260901-01")).thenReturn(List.of(summary(
                "RPT20260901-01", "START-SUMMARY")));
        when(dailyDataDao.findSummaries("RPT20260908-01")).thenReturn(List.of(summary(
                "RPT20260908-01", "END-SUMMARY")));
        when(issueDao.findBatchReportIssueStatistics("RPT20260901-01")).thenReturn(List.of(
                issue(1L, "合理差异", "无需处理"),
                issue(2L, "代码问题", "已修复"),
                issue(3L, "参数问题", "打开")));
        when(issueDao.findBatchReportIssueStatistics("RPT20260908-01")).thenReturn(List.of(
                issue(4L, "合理差异", "无需处理"),
                issue(5L, "外围问题", "已修复"),
                issue(6L, "平台问题", "新建")));
        when(dailyDataDao.findComparisons("RPT20260908-01")).thenReturn(List.of(comparison(
                "RPT20260908-01", "END-IFACE")));
        when(dailyDataDao.findCoverageSummaries("RPT20260908-01")).thenReturn(List.of(coverageSummary(
                "RPT20260908-01", "END-COVERAGE")));
        when(dailyDataDao.findCoverageDetails("RPT20260908-01")).thenReturn(List.of(coverageDetail(
                "RPT20260908-01", "END-COVERAGE-DETAIL")));
        ReplayWeeklyReportService service = service(new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter());

        ReplayWeeklyReportSnapshot generated = service.generate("RPT20260901-01", "RPT20260908-01");

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(generated.content()))) {
            assertEquals(3, workbook.getNumberOfSheets());
            String summaryText = sheetText(workbook.getSheet("汇总信息"));
            assertTrue(summaryText.contains("批次号：RPT20260901-01（上批次）"), summaryText);
            assertTrue(summaryText.contains("批次号：RPT20260908-01（本批次）"), summaryText);
            var previousRow = findSummaryRow(workbook.getSheet("汇总信息"), "RPT20260901-01");
            var currentRow = findSummaryRow(workbook.getSheet("汇总信息"), "RPT20260908-01");
            assertEquals(2d, previousRow.getCell(13).getNumericCellValue());
            assertEquals(0d, previousRow.getCell(16).getNumericCellValue());
            assertEquals(2d, currentRow.getCell(13).getNumericCellValue());
            assertEquals(1d, currentRow.getCell(14).getNumericCellValue());
            assertEquals(0.5d, currentRow.getCell(15).getNumericCellValue());
            assertEquals(1d, currentRow.getCell(17).getNumericCellValue());
            String interfaceText = sheetText(workbook.getSheet("接口比对明细"));
            String coverageText = sheetText(workbook.getSheet("回放交易覆盖情况"));
            assertTrue(interfaceText.contains("END-IFACE"), interfaceText);
            assertTrue(coverageText.contains("END-COVERAGE-DETAIL"), coverageText);
            assertFalse(interfaceText.contains("START"), interfaceText);
        }
        assertEquals("查询周报(20260901-20260908).xlsx", generated.fileName());
        verify(dailyDataDao).findComparisons("RPT20260901-01");
        verify(dailyDataDao, never()).findCoverageDetails("RPT20260901-01");
        ArgumentCaptor<ReplayWeeklyReportSnapshot> saved = ArgumentCaptor.forClass(ReplayWeeklyReportSnapshot.class);
        verify(weeklyReportDao).saveSnapshot(saved.capture());
        assertArrayEquals(generated.content(), saved.getValue().content());
        var summaryView = new ReplayReportSummaryCodec().decode(saved.getValue().summaryViewJson());
        assertEquals("RPT20260901-01", summaryView.startBatchNo());
        assertEquals("RPT20260908-01", summaryView.endBatchNo());
    }

    @Test
    void replaysTheLatestFieldConclusionAgainstEachBatchsOwnSplitCounts() throws Exception {
        String start = "RPT20260901-01", end = "RPT20260908-01";
        when(weeklyReportDao.findSnapshot(start, end)).thenReturn(Optional.empty());
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(batch(start, 1), batch(end, 8)));
        when(dailyDataDao.findSummaries(start)).thenReturn(List.of(summary(start, "START")));
        when(dailyDataDao.findSummaries(end)).thenReturn(List.of(summary(end, "END")));
        when(issueDao.findBatchReportIssueStatistics(start)).thenReturn(List.of(
                new ReplayDailyIssueStatisticRow(1L, "公共组", false, "合理差异", "字段级", "字段1",
                        "无需处理", 0L, false, ReplayIssueReviewStatus.APPROVED, "006208", LocalDate.of(2026, 9, 1))));
        when(issueDao.findBatchReportIssueStatistics(end)).thenReturn(List.of(
                new ReplayDailyIssueStatisticRow(2L, "公共组", false, "合理差异", "字段级", "字段1",
                        "无需处理", 0L, false, ReplayIssueReviewStatus.APPROVED, "006208", LocalDate.of(2026, 9, 8)),
                new ReplayDailyIssueStatisticRow(3L, "公共组", false, "代码问题", "字段级", "字段2",
                        "打开", 0L, false, null, "006208", null)));
        when(dailyDataDao.findComparisons(start)).thenReturn(List.of(splitComparison(start, 60L, 20L)));
        when(dailyDataDao.findComparisons(end)).thenReturn(List.of(splitComparison(end, 70L, 5L)));
        when(issueDao.findCurrentReportIssues(start, end)).thenReturn(List.of(
                latestIssue(2L, "field-1", end, "字段级", "字段1", "0"),
                latestIssue(3L, "field-2", end, "字段级", "字段2", "0")));

        var report = service(new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter()).generate(start, end);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(report.content()))) {
            var summary = workbook.getSheet("汇总信息");
            assertEquals(0.82828283d, findSummaryRow(summary, start).getCell(12).getNumericCellValue(), 0.000000001d);
            assertEquals(0.77777778d, findSummaryRow(summary, end).getCell(12).getNumericCellValue(), 0.000000001d);
            assertFalse(sheetText(workbook.getSheet("接口比对明细")).contains(start));
        }
        var view = new ReplayReportSummaryCodec().decode(report.summaryViewJson());
        assertEquals(new BigDecimal("0.77777778"), view.totalRow().values().get("matchPassRate"));
    }

    @Test
    void usesLatestConclusionsWithEachBatchsFrozenQuantityAndInterfaceDomainForAttribution() throws Exception {
        ReplayIssueTestFixtures.createSchema(jdbc);
        ReplayIssueDao realIssues = new ReplayIssueDao(jdbc);
        ReplayDailyDataDao realDaily = new ReplayDailyDataDao(jdbc);
        ReplayWeeklyReportDao realReports = new ReplayWeeklyReportDao(jdbc);
        String start = "RPT20260901-01", end = "RPT20260908-01";
        LocalDateTime first = LocalDateTime.of(2026, 9, 1, 9, 0), second = first.plusDays(7);
        seedHistoricalBatch(realDaily, start, first, 60L, 20L);
        realIssues.beginBatchStatistics(start, first);
        for (long id : List.of(1L, 2L)) {
            jdbc.update("INSERT INTO dii_replay_issue(id,source_sheet,group_name,is_sandbox,row_order,batch_no,transaction_code,"
                            + "issue_level,field_name,issue_type,issue_status,affected_transaction_count,review_status,"
                            + "defect_repair_date,issue_key,imported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, "公共组", "公共组", false, id, start, "006208", id == 1L ? "交易级" : "字段级", "字段" + id,
                    "合理差异", "无需处理", id == 1L ? "9" : "0", "APPROVED", LocalDate.of(2026, 9, 1), "historical-" + id, first);
            realIssues.upsertOccurrenceBatch(id, "historical-" + id, start, first,
                    com.axonlink.ai.replay.dto.ReplayIssueStatus.NO_ACTION);
        }
        seedHistoricalBatch(realDaily, end, second, 40L, 40L);
        realIssues.beginBatchStatistics(end, second);
        jdbc.update("UPDATE dii_replay_issue SET batch_no=?,affected_transaction_count=CASE WHEN id=1 THEN '25' ELSE '0' END,"
                + "review_status='PENDING',defect_repair_date=NULL", end);
        for (long id : List.of(1L, 2L)) realIssues.upsertOccurrenceBatch(id, "historical-" + id, end, second,
                com.axonlink.ai.replay.dto.ReplayIssueStatus.NO_ACTION);
        realIssues.beginBatchStatistics("RPT20260909-01", second.plusDays(1));
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='999',review_status='APPROVED',"
                + "defect_repair_date='2026-09-09'");
        for (long id : List.of(1L, 2L)) realIssues.updateLatestHistoryOccurrenceBatch(id, second.plusDays(1), end);

        var service = new ReplayWeeklyReportService(realDaily, realIssues, realReports,
                new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter(), jdbc,
                Clock.fixed(second.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        var report = service.generate(start, end);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(report.content()))) {
            var sheet = workbook.getSheet("汇总信息");
            var previousRow = findSummaryRow(sheet, start);
            var currentRow = findSummaryRow(sheet, end);
            assertEquals("存款组", previousRow.getCell(1).getStringCellValue());
            assertEquals(9d, previousRow.getCell(9).getNumericCellValue());
            assertEquals(0.91111111d, previousRow.getCell(12).getNumericCellValue(), 0.000000001d);
            assertEquals(25d, currentRow.getCell(9).getNumericCellValue());
            assertEquals(1.10810811d, currentRow.getCell(12).getNumericCellValue(), 0.000000001d);
        }
        var view = new ReplayReportSummaryCodec().decode(report.summaryViewJson());
        assertEquals(new BigDecimal("1.10810811"), view.totalRow().values().get("matchPassRate"));
    }

    @Test
    void returnsPermanentSnapshotWithoutRecalculation() {
        ReplayWeeklyReportSnapshot snapshot = new ReplayWeeklyReportSnapshot(
                "RPT20260901-01", "RPT20260908-01", "RPT20260908-01周报.xlsx", "xlsx",
                new byte[]{7, 8, 9}, 3, LocalDateTime.of(2026, 9, 8, 18, 0));
        when(weeklyReportDao.findSnapshot("RPT20260901-01", "RPT20260908-01"))
                .thenReturn(Optional.of(snapshot));
        ReplayDailyReportCalculator calculator = mock(ReplayDailyReportCalculator.class);
        ReplayDailyReportWorkbookWriter writer = mock(ReplayDailyReportWorkbookWriter.class);
        ReplayWeeklyReportService service = service(calculator, writer);

        ReplayWeeklyReportSnapshot result = service.generate("RPT20260901-01", "RPT20260908-01");

        assertEquals("查询周报(20260901-20260908).xlsx", result.fileName());
        assertArrayEquals(new byte[]{7, 8, 9}, result.content());
        assertEquals("xlsx", result.contentType());
        assertEquals(LocalDateTime.of(2026, 9, 8, 18, 0), result.generatedAt());
        verifyNoInteractions(dailyDataDao, issueDao, calculator, writer);
        verify(weeklyReportDao, never()).saveSnapshot(any());
    }

    @Test
    void rejectsAnotherRangeWhenTheEndingBatchAlreadyHasAWeeklyReport() {
        ReplayWeeklyReportSnapshot existing = new ReplayWeeklyReportSnapshot(
                "RPT20260901-01", "RPT20260908-01", "RPT20260908-01周报.xlsx", "xlsx",
                new byte[]{7, 8, 9}, 3, LocalDateTime.of(2026, 9, 8, 18, 0));
        when(weeklyReportDao.findSnapshot("RPT20260903-01", "RPT20260908-01"))
                .thenReturn(Optional.empty());
        when(weeklyReportDao.findSnapshotByEndBatchNo("RPT20260908-01"))
                .thenReturn(Optional.of(existing));
        ReplayWeeklyReportService service = service(new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter());

        assertThrows(ReplayWeeklyReportService.EndBatchAlreadyGeneratedException.class,
                () -> service.generate("RPT20260903-01", "RPT20260908-01"));

        verifyNoInteractions(dailyDataDao, issueDao);
        verify(weeklyReportDao, never()).saveSnapshot(any());
    }

    @Test
    void mapsConcurrentEndingBatchInsertCollisionToTheBusinessConflict() {
        ReplayWeeklyReportSnapshot existing = new ReplayWeeklyReportSnapshot(
                "RPT20260901-01", "RPT20260908-01", "RPT20260908-01周报.xlsx", "xlsx",
                new byte[]{7, 8, 9}, 3, LocalDateTime.of(2026, 9, 8, 18, 0));
        when(weeklyReportDao.findSnapshot("RPT20260903-01", "RPT20260908-01"))
                .thenReturn(Optional.empty());
        when(weeklyReportDao.findSnapshotByEndBatchNo("RPT20260908-01"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(
                batch("RPT20260901-01", 1), batch("RPT20260903-01", 3),
                batch("RPT20260908-01", 8)));
        when(dailyDataDao.findSummaries("RPT20260903-01")).thenReturn(List.of(summary(
                "RPT20260903-01", "START")));
        when(dailyDataDao.findSummaries("RPT20260908-01")).thenReturn(List.of(summary(
                "RPT20260908-01", "END")));
        when(issueDao.findBatchReportIssueStatistics(any())).thenReturn(List.of());
        when(dailyDataDao.findComparisons("RPT20260908-01")).thenReturn(List.of());
        when(dailyDataDao.findCoverageSummaries("RPT20260908-01")).thenReturn(List.of());
        when(dailyDataDao.findCoverageDetails("RPT20260908-01")).thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("duplicate end batch"))
                .when(weeklyReportDao).saveSnapshot(any());
        ReplayWeeklyReportService service = service(new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter());

        assertThrows(ReplayWeeklyReportService.EndBatchAlreadyGeneratedException.class,
                () -> service.generate("RPT20260903-01", "RPT20260908-01"));
    }

    @Test
    void rejectsMissingDailyDataCrossFamilySameOrReverseRanges() {
        when(weeklyReportDao.findSnapshot(any(), any())).thenReturn(Optional.empty());
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(
                batch("DZ20260901-01", 1), batch("DZ20260908-01", 8),
                batch("RPT20260901-01", 1), batch("RPT20260908-01", 8)));
        ReplayWeeklyReportService service = service(new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter());

        assertThrows(ReplayWeeklyReportService.BatchDataNotFoundException.class,
                () -> service.generate("RPT20260831-01", "RPT20260908-01"));
        assertThrows(ReplayWeeklyReportService.InvalidRangeException.class,
                () -> service.generate("RPT20260901-01", "DZ20260908-01"));
        assertThrows(ReplayWeeklyReportService.InvalidRangeException.class,
                () -> service.generate("RPT20260901-01", "RPT20260901-01"));
        assertThrows(ReplayWeeklyReportService.InvalidRangeException.class,
                () -> service.generate("RPT20260908-01", "RPT20260901-01"));
        assertThrows(ReplayWeeklyReportService.MalformedBatchException.class,
                () -> service.generate("BATCH", "RPT20260908-01"));
    }

    @Test
    void rejectsMissingRawSummaryWithoutSavingPartialSnapshot() {
        when(weeklyReportDao.findSnapshot("DZ20260901-01", "DZ20260908-01")).thenReturn(Optional.empty());
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(
                batch("DZ20260901-01", 1), batch("DZ20260908-01", 8)));
        when(dailyDataDao.findSummaries("DZ20260901-01")).thenReturn(List.of());
        when(dailyDataDao.findSummaries("DZ20260908-01")).thenReturn(List.of(summary(
                "DZ20260908-01", "END")));
        when(issueDao.findBatchReportIssueStatistics(any())).thenReturn(List.of());
        when(dailyDataDao.findComparisons(any())).thenReturn(List.of());
        when(dailyDataDao.findCoverageSummaries(any())).thenReturn(List.of());
        when(dailyDataDao.findCoverageDetails(any())).thenReturn(List.of());
        ReplayWeeklyReportService service = service(new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter());

        assertThrows(ReplayWeeklyReportService.BatchDataNotFoundException.class,
                () -> service.generate("DZ20260901-01", "DZ20260908-01"));

        verify(weeklyReportDao, never()).saveSnapshot(any());
    }

    @Test
    void regenerateReplacesExactSnapshotAndClearsMailStatus() {
        ReplayWeeklyReportDao realReportDao = new ReplayWeeklyReportDao(jdbc);
        ReplayWeeklyReportMailDao mailDao = new ReplayWeeklyReportMailDao(jdbc);
        ReplayIssueTestFixtures.createSchema(jdbc);
        ReplayWeeklyReportSnapshot old = new ReplayWeeklyReportSnapshot(
                "RPT20260901-01", "RPT20260908-01", "RPT20260908-01周报.xlsx", "xlsx",
                new byte[]{1}, 1, LocalDateTime.of(2026, 9, 8, 18, 0));
        realReportDao.saveSnapshot(old);
        mailDao.markSending(old.startBatchNo(), old.endBatchNo(), "标题", "正文",
                "sender@example.com", List.of("to@example.com"), List.of());
        mailDao.markSent(old.startBatchNo(), old.endBatchNo());
        when(dailyDataDao.findBatchesWithDataInFamilyOrder()).thenReturn(List.of(
                batch("RPT20260901-01", 1), batch("RPT20260908-01", 8)));
        when(dailyDataDao.findSummaries("RPT20260901-01")).thenReturn(List.of(summary(
                "RPT20260901-01", "START")));
        when(dailyDataDao.findSummaries("RPT20260908-01")).thenReturn(List.of(summary(
                "RPT20260908-01", "END")));
        when(issueDao.findBatchReportIssueStatistics(any())).thenReturn(List.of());
        when(dailyDataDao.findComparisons(any())).thenReturn(List.of());
        when(dailyDataDao.findCoverageSummaries(any())).thenReturn(List.of());
        when(dailyDataDao.findCoverageDetails(any())).thenReturn(List.of());
        ReplayDailyReportWorkbookWriter writer = mock(ReplayDailyReportWorkbookWriter.class);
        when(writer.write(any(), any(), any(), any(), any())).thenReturn(new byte[]{7, 8, 9});
        ReplayWeeklyReportService realService = new ReplayWeeklyReportService(
                dailyDataDao, issueDao, realReportDao, new ReplayDailyReportCalculator(), writer, jdbc,
                Clock.fixed(LocalDateTime.of(2026, 9, 9, 10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

        ReplayWeeklyReportSnapshot regenerated = realService.regenerate(
                "RPT20260901-01", "RPT20260908-01");

        assertArrayEquals(new byte[]{7, 8, 9}, regenerated.content());
        assertArrayEquals(new byte[]{7, 8, 9}, realReportDao.findSnapshot(
                old.startBatchNo(), old.endBatchNo()).orElseThrow().content());
        assertTrue(mailDao.find(old.startBatchNo(), old.endBatchNo()).isEmpty());
    }

    @Test
    void regenerateRejectsMissingExactSnapshot() {
        ReplayWeeklyReportService service = service(new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter());

        assertThrows(ReplayWeeklyReportService.SnapshotNotFoundException.class,
                () -> service.regenerate("RPT20260901-01", "RPT20260908-01"));
        verifyNoInteractions(dailyDataDao, issueDao);
    }

    private ReplayWeeklyReportService service(ReplayDailyReportCalculator calculator,
                                               ReplayDailyReportWorkbookWriter writer) {
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 9, 8, 18, 10)
                .toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new ReplayWeeklyReportService(dailyDataDao, issueDao, weeklyReportDao,
                calculator, writer, jdbc, clock);
    }

    private static ReplayDailyBatch batch(String batchNo, int day) {
        return new ReplayDailyBatch(batchNo, batchNo.startsWith("DZ") ? "DZ" : "RPT",
                LocalDateTime.of(2026, 9, day, 9, 0), null, true,
                LocalDateTime.of(2026, 9, day, 18, 0));
    }

    private static ReplayDailySummaryRow summary(String batchNo, String marker) {
        return new ReplayDailySummaryRow(batchNo, "公共组", 10L, 100L, 2L, 80L, 1L,
                new BigDecimal("0.80"), 3L, 1, marker);
    }

    private static ReplayDailySummaryRow volumeSummary(String batchNo, String domain, long sent) {
        return new ReplayDailySummaryRow(batchNo, domain, 0L, sent, 0L, 0L, 0L, BigDecimal.ZERO, 0L, 1, "{}");
    }

    private static ReplayInterfaceComparisonRow comparison(String batchNo, String marker) {
        return new ReplayInterfaceComparisonRow(batchNo, ReplayDailyRowType.DETAIL, marker, "S1", "交易",
                "开发", "行内", "公共组", 100L, 1L, 2L, 3L, 4L, 80L, 1L,
                new BigDecimal("0.90"), new BigDecimal("0.80"), new BigDecimal("1.25"),
                new BigDecimal("2.50"), 1, marker);
    }

    private static ReplayInterfaceComparisonRow splitComparison(String batch, long noDifference, long withDifference) {
        return new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL, "006208", "S1", "交易",
                "开发", "行内", "公共组", 100L, 0L, 0L, 2L, 0L, 80L, 1L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}",
                noDifference, withDifference);
    }

    private static void seedHistoricalBatch(ReplayDailyDataDao dao, String batch, LocalDateTime at, long noDiff, long withDiff) {
        var summary = new ReplayDailySummaryRow(batch, "存款组", 1L, 100L, 2L, 80L, 1L,
                new BigDecimal("0.80"), 0L, 1, "{}");
        var detail = new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL, "006208", "S1", "交易", "开发", "行内",
                "存款组", 100L, 0L, 0L, 2L, 0L, 80L, 1L, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}", noDiff, withDiff);
        dao.replaceBatch(new com.axonlink.ai.replay.dto.ReplayDailyWorkbookData(batch, List.of(summary), List.of(detail),
                List.of(), List.of()), at);
    }

    private static ReplayCoverageSummaryRow coverageSummary(String batchNo, String marker) {
        return new ReplayCoverageSummaryRow(batchNo, ReplayDailyRowType.DOMAIN_DETAIL, marker,
                100L, 90L, 10L, 0L, 0L, 0L, new BigDecimal("0.90"), 1, marker);
    }

    private static ReplayCoverageDetailRow coverageDetail(String batchNo, String marker) {
        return new ReplayCoverageDetailRow(batchNo, marker, "交易", "公共组", "S1", "", "是",
                LocalDate.of(2026, 9, 1), 90L, "已发送", "", "开发", "行内", 1, marker);
    }

    private static ReplayDailyIssueStatisticRow issue(long issueId, String issueType, String status) {
        return new ReplayDailyIssueStatisticRow(issueId, "公共组", false, issueType, "字段级",
                "任意", status, 1L, false, null, null, null);
    }

    private static com.axonlink.ai.replay.dto.ReplayIssueRow latestIssue(
            long id, String key, String batch, String level, String field, String amount) {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("id", id);
        values.put("issueKey", key);
        values.put("batchNo", batch);
        values.put("transactionCode", "006208");
        values.put("groupName", "其他领域");
        values.put("issueLevel", level);
        values.put("fieldName", field);
        values.put("affectedTransactionCount", amount);
        values.put("issueStatus", com.axonlink.ai.replay.dto.ReplayIssueStatus.NO_ACTION);
        values.put("reviewStatus", ReplayIssueReviewStatus.APPROVED);
        values.put("defectRepairDate", LocalDate.of(2026, 11, 4));
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .convertValue(values, com.axonlink.ai.replay.dto.ReplayIssueRow.class);
    }

    private static org.apache.poi.ss.usermodel.Row findSummaryRow(
            org.apache.poi.ss.usermodel.Sheet sheet, String batchNo) {
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            if (row.getCell(0) != null && batchNo.equals(row.getCell(0).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("Missing summary row for batch: " + batchNo);
    }

    private static String sheetText(org.apache.poi.ss.usermodel.Sheet sheet) {
        StringBuilder text = new StringBuilder();
        sheet.forEach(row -> row.forEach(cell -> text.append(cell).append('|')));
        return text.toString();
    }
}
