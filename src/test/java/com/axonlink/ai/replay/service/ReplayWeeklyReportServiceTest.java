package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
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
    void generatesAcrossIntermediateBatchesWithStartAsPreviousAndEndDetailsOnly() throws Exception {
        when(weeklyReportDao.findSnapshot("RPT20260901-01", "RPT20260908-01")).thenReturn(Optional.empty());
        when(dailyDataDao.findGeneratedBatchesInFamilyOrder()).thenReturn(List.of(
                batch("RPT20260901-01", 1), batch("RPT20260903-01", 3), batch("RPT20260908-01", 8)));
        when(dailyDataDao.findSummaries("RPT20260901-01")).thenReturn(List.of(summary(
                "RPT20260901-01", "START-SUMMARY")));
        when(dailyDataDao.findSummaries("RPT20260908-01")).thenReturn(List.of(summary(
                "RPT20260908-01", "END-SUMMARY")));
        when(issueDao.findDailyReportIssueStatistics(any())).thenReturn(List.of());
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
            String interfaceText = sheetText(workbook.getSheet("接口比对明细"));
            String coverageText = sheetText(workbook.getSheet("回放交易覆盖情况"));
            assertTrue(interfaceText.contains("END-IFACE"), interfaceText);
            assertTrue(coverageText.contains("END-COVERAGE-DETAIL"), coverageText);
            assertFalse(interfaceText.contains("START"), interfaceText);
        }
        assertEquals("RPT20260908-01周报.xlsx", generated.fileName());
        verify(dailyDataDao, never()).findComparisons("RPT20260901-01");
        verify(dailyDataDao, never()).findCoverageDetails("RPT20260901-01");
        ArgumentCaptor<ReplayWeeklyReportSnapshot> saved = ArgumentCaptor.forClass(ReplayWeeklyReportSnapshot.class);
        verify(weeklyReportDao).saveSnapshot(saved.capture());
        assertArrayEquals(generated.content(), saved.getValue().content());
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

        assertArrayEquals(new byte[]{7, 8, 9}, result.content());
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
        when(dailyDataDao.findGeneratedBatchesInFamilyOrder()).thenReturn(List.of(
                batch("RPT20260901-01", 1), batch("RPT20260903-01", 3),
                batch("RPT20260908-01", 8)));
        when(dailyDataDao.findSummaries("RPT20260903-01")).thenReturn(List.of(summary(
                "RPT20260903-01", "START")));
        when(dailyDataDao.findSummaries("RPT20260908-01")).thenReturn(List.of(summary(
                "RPT20260908-01", "END")));
        when(issueDao.findDailyReportIssueStatistics(any())).thenReturn(List.of());
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
    void rejectsMissingDailySnapshotCrossFamilySameOrReverseRanges() {
        when(weeklyReportDao.findSnapshot(any(), any())).thenReturn(Optional.empty());
        when(dailyDataDao.findGeneratedBatchesInFamilyOrder()).thenReturn(List.of(
                batch("DZ20260901-01", 1), batch("DZ20260908-01", 8),
                batch("RPT20260901-01", 1), batch("RPT20260908-01", 8)));
        ReplayWeeklyReportService service = service(new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter());

        assertThrows(ReplayWeeklyReportService.DailyReportNotGeneratedException.class,
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
        when(dailyDataDao.findGeneratedBatchesInFamilyOrder()).thenReturn(List.of(
                batch("DZ20260901-01", 1), batch("DZ20260908-01", 8)));
        when(dailyDataDao.findSummaries("DZ20260901-01")).thenReturn(List.of());
        when(dailyDataDao.findSummaries("DZ20260908-01")).thenReturn(List.of(summary(
                "DZ20260908-01", "END")));
        when(issueDao.findDailyReportIssueStatistics(any())).thenReturn(List.of());
        when(dailyDataDao.findComparisons(any())).thenReturn(List.of());
        when(dailyDataDao.findCoverageSummaries(any())).thenReturn(List.of());
        when(dailyDataDao.findCoverageDetails(any())).thenReturn(List.of());
        ReplayWeeklyReportService service = service(new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter());

        assertThrows(ReplayWeeklyReportService.BatchDataNotFoundException.class,
                () -> service.generate("DZ20260901-01", "DZ20260908-01"));

        verify(weeklyReportDao, never()).saveSnapshot(any());
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

    private static ReplayInterfaceComparisonRow comparison(String batchNo, String marker) {
        return new ReplayInterfaceComparisonRow(batchNo, ReplayDailyRowType.DETAIL, marker, "S1", "交易",
                "开发", "行内", "公共组", 100L, 1L, 2L, 3L, 4L, 80L, 1L,
                new BigDecimal("0.90"), new BigDecimal("0.80"), new BigDecimal("1.25"),
                new BigDecimal("2.50"), 1, marker);
    }

    private static ReplayCoverageSummaryRow coverageSummary(String batchNo, String marker) {
        return new ReplayCoverageSummaryRow(batchNo, ReplayDailyRowType.DOMAIN_DETAIL, marker,
                100L, 90L, 10L, 0L, 0L, 0L, new BigDecimal("0.90"), 1, marker);
    }

    private static ReplayCoverageDetailRow coverageDetail(String batchNo, String marker) {
        return new ReplayCoverageDetailRow(batchNo, marker, "交易", "公共组", "S1", "", "是",
                LocalDate.of(2026, 9, 1), 90L, "已发送", "", "开发", "行内", 1, marker);
    }

    private static String sheetText(org.apache.poi.ss.usermodel.Sheet sheet) {
        StringBuilder text = new StringBuilder();
        sheet.forEach(row -> row.forEach(cell -> text.append(cell).append('|')));
        return text.toString();
    }
}
