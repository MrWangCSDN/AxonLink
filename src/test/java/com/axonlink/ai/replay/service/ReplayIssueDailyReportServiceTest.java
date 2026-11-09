package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.DailyIssueSlice;
import com.axonlink.ai.replay.dto.DailyReportRow;
import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyBatch;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOption;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOptionPage;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayDailyReportMailDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ReplayIssueDailyReportServiceTest {

    private JdbcTemplate jdbc;
    private ReplayIssueDao issueDao;
    private ReplayDailyDataDao dailyDataDao;
    private ReplayDailyReportMailDao dailyReportMailDao;
    private ReplayIssueDailyReportService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        issueDao = new ReplayIssueDao(jdbc);
        dailyDataDao = new ReplayDailyDataDao(jdbc);
        dailyReportMailDao = new ReplayDailyReportMailDao(jdbc);
        service = new ReplayIssueDailyReportService(dailyDataDao, issueDao,
                new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter(), jdbc);
    }

    @Test
    void springContextUsesTheProductionConstructorWhenTestClockConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReplayDailyDataDao.class, () -> mock(ReplayDailyDataDao.class));
            context.registerBean(ReplayIssueDao.class, () -> mock(ReplayIssueDao.class));
            context.registerBean(ReplayDailyReportCalculator.class, () -> mock(ReplayDailyReportCalculator.class));
            context.registerBean(ReplayDailyReportWorkbookWriter.class, () -> mock(ReplayDailyReportWorkbookWriter.class));
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(ReplayIssueDailyReportService.class);

            context.refresh();

            assertNotNull(context.getBean(ReplayIssueDailyReportService.class));
        }
    }

    @Test
    void exposesCanonicalNamesForHistoricalReportAttachmentOptions() {
        ReplayDailyDataDao dataDao = mock(ReplayDailyDataDao.class);
        ReplayReportAttachmentOptionPage stored = new ReplayReportAttachmentOptionPage(List.of(
                new ReplayReportAttachmentOption("RPT20260908-01", "RPT", "RPT20260908-01日报.xlsx", 12,
                        LocalDateTime.of(2026, 9, 8, 10, 0)),
                new ReplayReportAttachmentOption("DZ20260909-01", "DZ", "DZ20260909-01日报.xlsx", 15,
                        LocalDateTime.of(2026, 9, 9, 10, 0))), 2, 10, 25);
        when(dataDao.searchReportAttachmentOptions("日报", "", 2, 10)).thenReturn(stored);
        ReplayIssueDailyReportService reportService = new ReplayIssueDailyReportService(
                dataDao, mock(ReplayIssueDao.class), new ReplayDailyReportCalculator(),
                new ReplayDailyReportWorkbookWriter(), jdbc);

        ReplayReportAttachmentOptionPage result = reportService.searchAttachmentOptions("日报", "", 2, 10);

        assertEquals(List.of("查询日报-20260908.xlsx", "账务日报-20260909.xlsx"),
                result.items().stream().map(ReplayReportAttachmentOption::fileName).toList());
        assertEquals(2, result.page());
        assertEquals(10, result.size());
        assertEquals(25, result.total());
    }

    @Test
    void aggregateGroupsByGroupNameAndSandbox() {
        List<DailyIssueSlice> slices = List.of(
                new DailyIssueSlice("存款组", true, "代码问题", "交易级", "已修复"),
                new DailyIssueSlice("存款组", true, "数据差异", "字段级", "打开"),
                new DailyIssueSlice("贷款组", false, "代码问题", "交易级", "已修复"),
                new DailyIssueSlice("贷款组", false, "", "交易级", "新建"));

        Map<String, DailyReportRow> byGroup = new LinkedHashMap<>();
        Map<String, List<DailyIssueSlice>> grouped = new LinkedHashMap<>();
        for (DailyIssueSlice slice : slices) {
            String key = slice.groupName() + "|" + slice.sandbox();
            grouped.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(slice);
        }
        for (Map.Entry<String, List<DailyIssueSlice>> entry : grouped.entrySet()) {
            DailyIssueSlice first = entry.getValue().get(0);
            byGroup.put(entry.getKey(), DailyReportRow.aggregate(first.groupName(), first.sandbox(), entry.getValue()));
        }

        DailyReportRow sandboxDeposit = byGroup.get("存款组|true");
        assertEquals(2, sandboxDeposit.totalCount());
        assertEquals(1, sandboxDeposit.fixedCount());
        assertEquals(1, sandboxDeposit.unresolvedCount());
        assertEquals(50.0, sandboxDeposit.fixRate());
        assertEquals(50.0, sandboxDeposit.inspectionProgress());
        assertEquals(1L, sandboxDeposit.fixedByIssueType().get("代码问题").longValue());

        DailyReportRow prodLoan = byGroup.get("贷款组|false");
        assertEquals(2, prodLoan.totalCount());
        assertEquals(1, prodLoan.fixedCount());
        assertEquals(1, prodLoan.unresolvedCount());
        assertEquals(50.0, prodLoan.resolutionProgress());
        assertEquals(50.0, prodLoan.inspectionProgress());
    }

    @Test
    void aggregateCountsOnlyTransactionLevelNoActionAsReasonableDifference() {
        DailyReportRow row = DailyReportRow.aggregate("公共组", false, List.of(
                new DailyIssueSlice("公共组", false, "合理差异", "交易级", "无需处理"),
                new DailyIssueSlice("公共组", false, "合理差异", "字段级", "无需处理"),
                new DailyIssueSlice("公共组", false, "合理差异", "交易级", "打开")));

        assertEquals(1L, row.reasonableDifferenceCount());
    }

    @Test
    void findDailySlicesByBatchReturnsJoinedRows() {
        insertIssueWithOccurrence(1L, "存款组", false, "代码问题", "已修复", "BATCH-A");
        insertIssueWithOccurrence(2L, "存款组", false, "数据差异", "打开", "BATCH-A");
        insertIssueWithOccurrence(3L, "贷款组", true, "代码问题", "新建", "BATCH-B");

        assertEquals(2, issueDao.findDailySlicesByBatch("BATCH-A").size());
        assertEquals(1, issueDao.findDailySlicesByBatch("BATCH-B").size());
    }

    @Test
    void findDailySlicesByBatchUsesCurrentIssueStatusAndType() {
        insertIssueWithOccurrence(10L, "存款组", false, "代码问题", "打开", "BATCH-A");
        jdbc.update("UPDATE dii_replay_issue SET issue_status=?, issue_type=? WHERE id=?",
                "已修复", "参数问题", 10L);

        List<DailyIssueSlice> batch = issueDao.findDailySlicesByBatch("BATCH-A");

        assertEquals(1, batch.size());
        assertEquals("已修复", batch.get(0).lastStatus());
        assertEquals("参数问题", batch.get(0).issueType());
        assertEquals(1L, DailyReportRow.aggregate("存款组", false, batch)
                .fixedByIssueType().get("参数问题"));
    }

    @Test
    void findDailySlicesByBatchReturnsIssueLevelAndCountsReasonableDifference() {
        insertIssueWithOccurrence(11L, "公共组", false, "合理差异", "交易级", "无需处理", "BATCH-A");
        insertIssueWithOccurrence(12L, "公共组", false, "合理差异", "字段级", "无需处理", "BATCH-A");
        insertIssueWithOccurrence(13L, "公共组", false, "合理差异", "交易级", "打开", "BATCH-A");

        List<DailyIssueSlice> slices = issueDao.findDailySlicesByBatch("BATCH-A");

        assertEquals(List.of("交易级", "字段级", "交易级"),
                slices.stream().map(DailyIssueSlice::issueLevel).toList());
        assertEquals(1L, DailyReportRow.aggregate("公共组", false, slices).reasonableDifferenceCount());
    }

    @Test
    void listsBatchesFromStoredDailySummaries() {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "RPT-OLD");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "RPT-NEW");

        List<ReplayDailyBatch> batches = service.listBatches();

        assertEquals(List.of("RPT20260902-01", "RPT20260901-01"),
                batches.stream().map(ReplayDailyBatch::batchNo).toList());
        assertEquals("RPT20260901-01", batches.get(0).previousBatchNo());
    }

    @Test
    void reportPipelineComponentsAreDiscoverableBySpring() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);

        List<String> componentClasses = scanner.findCandidateComponents(
                        "com.axonlink.ai.replay.service").stream()
                .map(definition -> definition.getBeanClassName())
                .toList();

        assertTrue(componentClasses.contains(ReplayIssueDailyReportService.class.getName()));
        assertTrue(componentClasses.contains(ReplayDailyReportCalculator.class.getName()));
        assertTrue(componentClasses.contains(ReplayDailyReportWorkbookWriter.class.getName()));
    }

    @Test
    void generatesSelectedBatchUsingItsSameFamilyPreviousBatch() throws Exception {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "RPT-PREV");
        seedDailyBatch("DZ20260901-01", LocalDateTime.of(2026, 9, 1, 10, 0), "DZ-ONLY");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "RPT-CURRENT");

        byte[] workbook = service.generate("RPT20260902-01");

        try (var parsed = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            assertEquals(3, parsed.getNumberOfSheets());
            String summary = sheetText(parsed.getSheet("汇总信息"));
            assertTrue(summary.contains("批次号：RPT20260901-01（上批次）"), summary);
            assertTrue(summary.contains("批次号：RPT20260902-01（本批次）"), summary);
            assertFalse(summary.contains("DZ20260901-01"), summary);
        }
    }

    @Test
    void generatedDailyWorkbookExcludesNoActionFromProblemStatistics() throws Exception {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        insertIssueWithOccurrence(101L, "公共组", false, "合理差异", "交易级",
                "无需处理", "RPT20260901-01");
        insertIssueWithOccurrence(102L, "公共组", false, "代码问题", "字段级",
                "已修复", "RPT20260901-01");
        insertIssueWithOccurrence(103L, "公共组", false, "参数问题", "字段级",
                "打开", "RPT20260901-01");
        insertIssueWithOccurrence(104L, "公共组", false, "合理差异", "交易级",
                "无需处理", "RPT20260902-01");
        insertIssueWithOccurrence(105L, "公共组", false, "外围问题", "字段级",
                "已修复", "RPT20260902-01");
        insertIssueWithOccurrence(106L, "公共组", false, "平台问题", "字段级",
                "新建", "RPT20260902-01");

        byte[] workbook = service.generate("RPT20260902-01");

        try (var parsed = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            Sheet summary = parsed.getSheet("汇总信息");
            var previousRow = findSummaryRow(summary, "RPT20260901-01");
            var currentRow = findSummaryRow(summary, "RPT20260902-01");
            assertEquals(2d, previousRow.getCell(13).getNumericCellValue());
            assertEquals(1d, previousRow.getCell(14).getNumericCellValue());
            assertEquals(1d, previousRow.getCell(15).getNumericCellValue());
            assertEquals(0d, previousRow.getCell(16).getNumericCellValue());
            assertEquals(1d, previousRow.getCell(24).getNumericCellValue());
            assertEquals(2d, currentRow.getCell(13).getNumericCellValue());
            assertEquals(1d, currentRow.getCell(14).getNumericCellValue());
            assertEquals(0.5d, currentRow.getCell(15).getNumericCellValue());
            assertEquals(1d, currentRow.getCell(17).getNumericCellValue());
        }
    }

    @Test
    void deductsOnlyApprovedTransactionLevelNoActionFromRatesAndRefreshesAfterApproval() throws Exception {
        String previous = "RPT20260901-01", current = "RPT20260902-01";
        seedDailyBatch(previous, LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch(current, LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        insertIssueWithOccurrence(201L, "公共组", false, "合理差异", "交易级", "无需处理", previous);
        insertIssueWithOccurrence(202L, "公共组", false, "合理差异", "交易级", "无需处理", current);
        insertIssueWithOccurrence(203L, "公共组", false, "合理差异", "交易级", "无需处理", current);
        insertIssueWithOccurrence(204L, "公共组", false, "合理差异", "交易级", "无需处理", current);
        insertIssueWithOccurrence(205L, "公共组", false, "合理差异", "交易级", "无需处理", current);
        insertIssueWithOccurrence(206L, "公共组", false, "合理差异", "字段级", "无需处理", current);
        insertIssueWithOccurrence(207L, "公共组", true, "合理差异", "交易级", "无需处理", current);
        insertIssueWithOccurrence(208L, "公共组", false, "代码问题", "交易级", "打开", current);
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED', affected_transaction_count='6' WHERE id=201");
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED', affected_transaction_count='9' WHERE id=202");
        jdbc.update("UPDATE dii_replay_issue SET review_status='PENDING', affected_transaction_count='2' WHERE id=203");
        jdbc.update("UPDATE dii_replay_issue SET review_status=NULL, affected_transaction_count='3' WHERE id=204");
        jdbc.update("UPDATE dii_replay_issue SET review_status='UNKNOWN', affected_transaction_count='4' WHERE id=205");
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED', affected_transaction_count='50' WHERE id IN (206,207,208)");
        jdbc.update("UPDATE dii_replay_issue SET transaction_code='PREVIOUS-IFACE' WHERE id=201");
        jdbc.update("UPDATE dii_replay_issue SET transaction_code='CURRENT-IFACE' WHERE id IN (202,203,204,205,206,208)");
        jdbc.update("UPDATE dii_replay_issue SET transaction_code='UNMATCHED' WHERE id=207");
        captureBatchStatistics(previous);
        captureBatchStatistics(current);

        byte[] generated = service.generate(current);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(generated))) {
            Sheet summary = workbook.getSheet("汇总信息");
            var previousRow = findSummaryRow(summary, previous);
            var currentRow = findSummaryRow(summary, current);
            assertEquals(6d, previousRow.getCell(9).getNumericCellValue());
            assertEquals(9d, currentRow.getCell(9).getNumericCellValue());
            assertEquals(0.91111111d, currentRow.getCell(11).getNumericCellValue(), 0.000000001d);
            assertEquals(0.87777778d, currentRow.getCell(12).getNumericCellValue(), 0.000000001d);
            assertEquals(1d, currentRow.getCell(13).getNumericCellValue(), "问题统计仍排除全部无需处理");
        }
        var savedView = new ReplayReportSummaryCodec().decode(
                dailyDataDao.findReportSnapshot(current).orElseThrow().summaryViewJson());
        assertEquals(9L, savedView.totalRow().values().get("noAction"));
        assertEquals(new BigDecimal("0.87777778"), savedView.totalRow().values().get("matchPassRate"));

        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED' WHERE id=203");
        issueDao.updateLatestHistoryOccurrenceBatch(203L, LocalDateTime.of(2026, 9, 2, 10, 0), current);
        assertArrayEquals(generated, service.generate(current), "已生成快照需显式重新生成");
        byte[] regenerated = service.regenerate(current);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(regenerated))) {
            var currentRow = findSummaryRow(workbook.getSheet("汇总信息"), current);
            assertEquals(11d, currentRow.getCell(9).getNumericCellValue());
            assertEquals(0.89772727d, currentRow.getCell(12).getNumericCellValue(), 0.000000001d);
        }
        var refreshedView = new ReplayReportSummaryCodec().decode(
                dailyDataDao.findReportSnapshot(current).orElseThrow().summaryViewJson());
        assertEquals(11L, refreshedView.totalRow().values().get("noAction"));
        assertEquals(new BigDecimal("0.89772727"), refreshedView.totalRow().values().get("matchPassRate"));
    }

    @Test
    void countsAcceptedFieldDifferencesOnlyAfterEveryFieldHasARepairDate() throws Exception {
        String previous = "RPT20260901-01", current = "RPT20260902-01";
        seedDailyBatch(previous, LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch(current, LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        jdbc.update("UPDATE dii_replay_interface_comparison SET both_success_no_field_difference=60, "
                + "both_success_with_field_difference=20 WHERE batch_no=?", previous);
        jdbc.update("UPDATE dii_replay_interface_comparison SET both_success_no_field_difference=70, "
                + "both_success_with_field_difference=10 WHERE batch_no=?", current);
        insertIssueWithOccurrence(301L, "公共组", false, "合理差异", "字段级", "无需处理", previous);
        insertIssueWithOccurrence(302L, "公共组", false, "合理差异", "字段级", "无需处理", current);
        insertIssueWithOccurrence(303L, "公共组", false, "代码问题", "字段级", "打开", current);
        insertIssueWithOccurrence(304L, "公共组", false, "规则性差异问题", "字段级", "无需处理", current);
        jdbc.update("UPDATE dii_replay_issue SET transaction_code='PREVIOUS-IFACE', review_status='APPROVED', "
                + "defect_repair_date='2026-09-01' WHERE id=301");
        jdbc.update("UPDATE dii_replay_issue SET transaction_code='CURRENT-IFACE', review_status='APPROVED', "
                + "defect_repair_date='2026-09-02' WHERE id IN (302,304)");
        jdbc.update("UPDATE dii_replay_issue SET transaction_code='CURRENT-IFACE' WHERE id=303");
        captureBatchStatistics(previous);
        captureBatchStatistics(current);

        byte[] first = service.generate(current);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(first))) {
            Sheet summary = workbook.getSheet("汇总信息");
            assertEquals(0.82828283d, findSummaryRow(summary, previous).getCell(12).getNumericCellValue(), 0.000000001d);
            assertEquals(0.72727273d, findSummaryRow(summary, current).getCell(12).getNumericCellValue(), 0.000000001d);
            assertEquals(0d, findSummaryRow(summary, current).getCell(9).getNumericCellValue(), "字段级不扣分母");
        }

        jdbc.update("UPDATE dii_replay_issue SET issue_status='已修复', defect_repair_date='2026-09-02' WHERE id=303");
        issueDao.updateLatestHistoryOccurrenceBatch(303L, LocalDateTime.of(2026, 9, 2, 10, 0), current);
        assertArrayEquals(first, service.generate(current));
        byte[] regenerated = service.regenerate(current);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(regenerated))) {
            var row = findSummaryRow(workbook.getSheet("汇总信息"), current);
            assertEquals(0.82828283d, row.getCell(12).getNumericCellValue(), 0.000000001d);
            assertEquals(0.82828283d, row.getCell(11).getNumericCellValue(), 0.000000001d);
        }
        var view = new ReplayReportSummaryCodec().decode(
                dailyDataDao.findReportSnapshot(current).orElseThrow().summaryViewJson());
        assertEquals(new BigDecimal("0.82828283"), view.rows().get(0).values().get("matchPassRate"));
        assertEquals(new BigDecimal("0.82828283"), view.totalRow().values().get("matchPassRate"));
    }

    @Test
    void generatesInterfaceAndCoverageSheetsFromSelectedBatchOnly() throws Exception {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS-ONLY");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "SELECTED-ONLY");

        byte[] workbook = service.generate("RPT20260902-01");

        try (var parsed = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            String comparisons = sheetText(parsed.getSheet("接口比对明细"));
            String coverage = sheetText(parsed.getSheet("回放交易覆盖情况"));
            assertTrue(comparisons.contains("SELECTED-ONLY-IFACE"), comparisons);
            assertFalse(comparisons.contains("PREVIOUS-ONLY-IFACE"), comparisons);
            assertTrue(coverage.contains("SELECTED-ONLY-COVERAGE"), coverage);
            assertFalse(coverage.contains("PREVIOUS-ONLY-COVERAGE"), coverage);
        }
    }

    @Test
    void loadsBothIssueProjectionsAgainstTheSelectedReportBatch() {
        ReplayDailyDataDao dataDao = mock(ReplayDailyDataDao.class);
        ReplayIssueDao projectionDao = mock(ReplayIssueDao.class);
        ReplayDailyReportWorkbookWriter writer = mock(ReplayDailyReportWorkbookWriter.class);
        ReplayDailyBatch selected = new ReplayDailyBatch("RPT20260902-01", "RPT",
                LocalDateTime.of(2026, 9, 2, 9, 0), "RPT20260901-01");
        when(dataDao.findBatchesRecentFirst()).thenReturn(List.of(selected));
        when(dataDao.findSummaries(any())).thenReturn(List.of());
        when(dataDao.findComparisons(any())).thenReturn(List.of());
        when(dataDao.findCoverageSummaries(any())).thenReturn(List.of());
        when(dataDao.findCoverageDetails(any())).thenReturn(List.of());
        when(projectionDao.findBatchReportIssueStatistics(any())).thenReturn(List.of());
        when(writer.write(any(), any(), any(), any(), any())).thenReturn(new byte[]{1, 2, 3});
        ReplayIssueDailyReportService orchestrator = new ReplayIssueDailyReportService(dataDao, projectionDao,
                new ReplayDailyReportCalculator(), writer, jdbc);

        byte[] generated = orchestrator.generate("RPT20260902-01");

        assertArrayEquals(new byte[]{1, 2, 3}, generated);
        verify(projectionDao).findBatchReportIssueStatistics("RPT20260901-01");
        verify(projectionDao).findBatchReportIssueStatistics("RPT20260902-01");
        verify(dataDao).findComparisons("RPT20260902-01");
        verify(dataDao).findCoverageSummaries("RPT20260902-01");
        verify(dataDao).findCoverageDetails("RPT20260902-01");
    }

    @Test
    void returnsStoredSnapshotWithoutRecalculatingWorkbook() {
        ReplayDailyDataDao dataDao = mock(ReplayDailyDataDao.class);
        ReplayIssueDao projectionDao = mock(ReplayIssueDao.class);
        ReplayDailyReportCalculator calculator = mock(ReplayDailyReportCalculator.class);
        ReplayDailyReportWorkbookWriter writer = mock(ReplayDailyReportWorkbookWriter.class);
        ReplayDailyReportSnapshot snapshot = new ReplayDailyReportSnapshot(
                "RPT20260902-01", "RPT20260902-01日报.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{7, 8, 9}, 3L, LocalDateTime.of(2026, 9, 7, 10, 30));
        when(dataDao.findReportSnapshot("RPT20260902-01")).thenReturn(Optional.of(snapshot));
        ReplayIssueDailyReportService orchestrator = new ReplayIssueDailyReportService(
                dataDao, projectionDao, calculator, writer, jdbc);

        assertArrayEquals(new byte[]{7, 8, 9}, orchestrator.generate("RPT20260902-01"));

        verify(dataDao, never()).findBatchesRecentFirst();
        verifyNoInteractions(projectionDao, calculator, writer);
        verify(dataDao, never()).saveReportSnapshot(any());
    }

    @Test
    void regenerateRebuildsExistingSnapshotAndClearsMailStatus() {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        byte[] oldBytes = service.generate("RPT20260902-01");
        dailyReportMailDao.markSending("RPT20260902-01", "标题", "正文", "sender@example.com",
                List.of("to@example.com"), List.of());
        dailyReportMailDao.markSent("RPT20260902-01");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 10, 0), "UPDATED");

        byte[] newBytes = service.regenerate("RPT20260902-01");

        assertFalse(java.util.Arrays.equals(oldBytes, newBytes));
        assertArrayEquals(newBytes, dailyDataDao.findReportSnapshot("RPT20260902-01").orElseThrow().content());
        assertTrue(dailyReportMailDao.find("RPT20260902-01").isEmpty());
    }

    @Test
    void regenerateRejectsMissingSnapshot() {
        assertThrows(ReplayIssueDailyReportService.SnapshotNotFoundException.class,
                () -> service.regenerate("RPT20260902-01"));
    }

    @Test
    void regenerateWriterFailurePreservesOldSnapshotAndMailStatus() {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        byte[] oldBytes = service.generate("RPT20260902-01");
        dailyReportMailDao.markSending("RPT20260902-01", "标题", "正文", "sender@example.com",
                List.of("to@example.com"), List.of());
        dailyReportMailDao.markSent("RPT20260902-01");
        ReplayDailyReportWorkbookWriter failingWriter = mock(ReplayDailyReportWorkbookWriter.class);
        when(failingWriter.write(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("write failed"));
        ReplayIssueDailyReportService failingService = new ReplayIssueDailyReportService(
                dailyDataDao, issueDao, new ReplayDailyReportCalculator(), failingWriter, jdbc);

        assertThrows(IllegalStateException.class,
                () -> failingService.regenerate("RPT20260902-01"));

        assertArrayEquals(oldBytes, dailyDataDao.findReportSnapshot("RPT20260902-01").orElseThrow().content());
        assertEquals("SENT", dailyReportMailDao.find("RPT20260902-01").orElseThrow().status());
    }

    @Test
    void savesCompletedWorkbookAfterCacheMiss() {
        ReplayDailyDataDao dataDao = mock(ReplayDailyDataDao.class);
        ReplayIssueDao projectionDao = mock(ReplayIssueDao.class);
        ReplayDailyReportWorkbookWriter writer = mock(ReplayDailyReportWorkbookWriter.class);
        ReplayDailyBatch selected = new ReplayDailyBatch("RPT20260902-01", "RPT",
                LocalDateTime.of(2026, 9, 2, 9, 0), "RPT20260901-01");
        when(dataDao.findReportSnapshot("RPT20260902-01")).thenReturn(Optional.empty());
        when(dataDao.findBatchesRecentFirst()).thenReturn(List.of(selected));
        when(dataDao.findSummaries(any())).thenReturn(List.of());
        when(dataDao.findComparisons(any())).thenReturn(List.of());
        when(dataDao.findCoverageSummaries(any())).thenReturn(List.of());
        when(dataDao.findCoverageDetails(any())).thenReturn(List.of());
        when(projectionDao.findBatchReportIssueStatistics(any())).thenReturn(List.of());
        when(writer.write(any(), any(), any(), any(), any())).thenReturn(new byte[]{1, 2, 3});
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 9, 7, 10, 30)
                .toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        ReplayIssueDailyReportService orchestrator = new ReplayIssueDailyReportService(dataDao, projectionDao,
                new ReplayDailyReportCalculator(), writer, jdbc, clock);

        assertArrayEquals(new byte[]{1, 2, 3}, orchestrator.generate("RPT20260902-01"));

        ArgumentCaptor<ReplayDailyReportSnapshot> saved = ArgumentCaptor.forClass(ReplayDailyReportSnapshot.class);
        verify(dataDao).saveReportSnapshot(saved.capture());
        assertEquals("RPT20260902-01", saved.getValue().batchNo());
        assertEquals("查询日报-20260902.xlsx", saved.getValue().fileName());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", saved.getValue().contentType());
        assertArrayEquals(new byte[]{1, 2, 3}, saved.getValue().content());
        assertEquals(3L, saved.getValue().fileSize());
        var summaryView = new ReplayReportSummaryCodec().decode(saved.getValue().summaryViewJson());
        assertEquals("RPT20260902-01", summaryView.endBatchNo());
        assertEquals("RPT", summaryView.family());
        assertEquals(LocalDateTime.of(2026, 9, 7, 10, 30), saved.getValue().generatedAt());
    }

    @Test
    void doesNotSaveSnapshotWhenWorkbookWritingFails() {
        ReplayDailyDataDao dataDao = mock(ReplayDailyDataDao.class);
        ReplayIssueDao projectionDao = mock(ReplayIssueDao.class);
        ReplayDailyReportWorkbookWriter writer = mock(ReplayDailyReportWorkbookWriter.class);
        ReplayDailyBatch selected = new ReplayDailyBatch("RPT20260902-01", "RPT",
                LocalDateTime.of(2026, 9, 2, 9, 0), "RPT20260901-01");
        when(dataDao.findReportSnapshot("RPT20260902-01")).thenReturn(Optional.empty());
        when(dataDao.findBatchesRecentFirst()).thenReturn(List.of(selected));
        when(dataDao.findSummaries(any())).thenReturn(List.of());
        when(dataDao.findComparisons(any())).thenReturn(List.of());
        when(dataDao.findCoverageSummaries(any())).thenReturn(List.of());
        when(dataDao.findCoverageDetails(any())).thenReturn(List.of());
        when(projectionDao.findBatchReportIssueStatistics(any())).thenReturn(List.of());
        when(writer.write(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("write failed"));
        ReplayIssueDailyReportService orchestrator = new ReplayIssueDailyReportService(dataDao, projectionDao,
                new ReplayDailyReportCalculator(), writer, jdbc);

        assertThrows(IllegalStateException.class, () -> orchestrator.generate("RPT20260902-01"));

        verify(dataDao, never()).saveReportSnapshot(any());
    }

    @Test
    void loadsOneReportSnapshotInAReadOnlyRepeatableReadTransaction() {
        ReplayDailyDataDao dataDao = mock(ReplayDailyDataDao.class);
        ReplayIssueDao projectionDao = mock(ReplayIssueDao.class);
        ReplayDailyReportWorkbookWriter writer = mock(ReplayDailyReportWorkbookWriter.class);
        ReplayDailyBatch selected = new ReplayDailyBatch("RPT20260902-01", "RPT",
                LocalDateTime.of(2026, 9, 2, 9, 0), "RPT20260901-01");
        JdbcTemplate snapshotJdbc = new JdbcTemplate(jdbc.getDataSource());
        List<Connection> snapshotConnections = new ArrayList<>();
        when(dataDao.findBatchesRecentFirst()).thenAnswer(invocation -> {
            recordSnapshotConnection(snapshotJdbc, snapshotConnections);
            return List.of(selected);
        });
        when(dataDao.findSummaries(any())).thenAnswer(invocation -> {
            recordSnapshotConnection(snapshotJdbc, snapshotConnections);
            return List.of();
        });
        when(dataDao.findComparisons(any())).thenAnswer(invocation -> {
            recordSnapshotConnection(snapshotJdbc, snapshotConnections);
            return List.of();
        });
        when(dataDao.findCoverageSummaries(any())).thenAnswer(invocation -> {
            recordSnapshotConnection(snapshotJdbc, snapshotConnections);
            return List.of();
        });
        when(dataDao.findCoverageDetails(any())).thenAnswer(invocation -> {
            recordSnapshotConnection(snapshotJdbc, snapshotConnections);
            return List.of();
        });
        when(projectionDao.findBatchReportIssueStatistics(any())).thenAnswer(invocation -> {
            recordSnapshotConnection(snapshotJdbc, snapshotConnections);
            return List.of();
        });
        when(writer.write(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.hasResource(snapshotJdbc.getDataSource()));
            return new byte[]{1, 2, 3};
        });
        ReplayIssueDailyReportService orchestrator = new ReplayIssueDailyReportService(dataDao, projectionDao,
                new ReplayDailyReportCalculator(), writer, snapshotJdbc);

        assertArrayEquals(new byte[]{1, 2, 3}, orchestrator.generate("RPT20260902-01"));

        assertEquals(9, snapshotConnections.size());
        Connection transactionConnection = snapshotConnections.get(0);
        assertTrue(snapshotConnections.stream().allMatch(connection -> connection == transactionConnection));
    }

    @Test
    void rejectsMissingSelectedBatch() {
        ReplayIssueDailyReportService.BatchNotFoundException exception = assertThrows(
                ReplayIssueDailyReportService.BatchNotFoundException.class,
                () -> service.generate("RPT20260909-01"));

        assertEquals("批次数据不存在", exception.getMessage());
    }

    @Test
    void generatesInitialDailyWithoutSameFamilyPreviousBatch() throws Exception {
        seedDailyBatch("DZ20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "DZ-FIRST");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "RPT-FIRST");

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(assertDoesNotThrow(() -> service.generate("RPT20260902-01"))))) {
            assertTrue(sheetText(workbook.getSheet("汇总信息")).contains("本批次为统计起点"));
            assertEquals(100d, findSummaryRow(workbook.getSheet("汇总信息"), "RPT20260902-01").getCell(3).getNumericCellValue());
        }
    }

    @Test
    void missingPreviousHistoryPreservesBothBatchRateTablesAndTheNextBatchNeedsNoEarlierReport() throws Exception {
        String previous = "RPT20260901-01", current = "RPT20260902-01", next = "RPT20260903-01";
        seedDailyBatch(previous, LocalDateTime.of(2026, 9, 1, 9, 0), "OLD");
        jdbc.update("DELETE FROM dii_replay_issue_statistics_batch WHERE batch_no=?", previous);
        seedDailyBatch(current, LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");

        byte[] bytes = assertDoesNotThrow(() -> service.generate(current));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("汇总信息");
            assertTrue(sheetText(sheet).contains("历史问题统计未建立"));
            assertFalse(sheetText(sheet).contains("本批次为统计起点"), "上一批原始数据存在时保留两批表");
            var previousRow = findSummaryRow(sheet, previous);
            assertEquals(100d, previousRow.getCell(3).getNumericCellValue());
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, previousRow.getCell(9).getCellType());
            assertEquals(0.82828283d, previousRow.getCell(11).getNumericCellValue(), 0.000000001d);
            assertEquals(0.79797980d, previousRow.getCell(12).getNumericCellValue(), 0.000000001d);
            for (int column = 13; column <= 24; column++) {
                assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, previousRow.getCell(column).getCellType());
            }
            var row = findSummaryRow(sheet, current);
            assertEquals(100d, row.getCell(3).getNumericCellValue());
            assertEquals(0.82828283d, row.getCell(11).getNumericCellValue(), 0.000000001d);
            for (int column = 14; column <= 20; column++) {
                assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, row.getCell(column).getCellType());
                assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, sheet.getRow(sheet.getLastRowNum()).getCell(column).getCellType());
            }
            long totals = 0;
            for (var sheetRow : sheet) if (sheetRow.getCell(1) != null && "合计".equals(sheetRow.getCell(1).toString())) totals++;
            assertEquals(2L, totals, "上一批原始数量和旧算法率仍须保留");
        }
        var view = new ReplayReportSummaryCodec().decode(dailyDataDao.findReportSnapshot(current).orElseThrow().summaryViewJson());
        var extracted = new ReplayLegacySummaryExtractor().extract(bytes,
                com.axonlink.ai.replay.dto.ReplayReportPeriod.DAILY, null, current);
        for (String key : List.of("previousUnresolvedTotal", "previousResolutionRate", "unanalyzed", "analyzedPendingFix",
                "notFullyFixed", "dataMigrationIssue", "codeNotReleased")) {
            assertTrue(view.rows().get(0).values().containsKey(key));
            assertEquals(null, view.rows().get(0).values().get(key));
            assertEquals(null, view.totalRow().values().get(key));
            assertEquals(null, extracted.rows().get(0).values().get(key));
        }
        jdbc.update("DELETE FROM dii_replay_daily_report_snapshot WHERE batch_no=?", current);
        seedDailyBatch(next, LocalDateTime.of(2026, 9, 3, 9, 0), "NEXT");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(service.generate(next)))) {
            assertEquals(100d, findSummaryRow(workbook.getSheet("汇总信息"), current).getCell(3).getNumericCellValue());
            assertFalse(sheetText(workbook.getSheet("汇总信息")).contains("历史问题统计未建立"));
            assertFalse(sheetText(workbook.getSheet("汇总信息")).contains("本批次为统计起点"));
        }
    }

    @Test
    void legacyCurrentWithoutHistoryGeneratesOriginalRatesWithoutChangingImportedData() throws Exception {
        String current = "RPT20260902-01";
        seedDailyBatch(current, LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        jdbc.update("DELETE FROM dii_replay_issue_statistics_batch WHERE batch_no=?", current);
        var originalSummaries = dailyDataDao.findSummaries(current);
        var originalComparisons = dailyDataDao.findComparisons(current);

        byte[] bytes = assertDoesNotThrow(() -> service.generate(current));

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var row = findSummaryRow(workbook.getSheet("汇总信息"), current);
            assertEquals(100d, row.getCell(3).getNumericCellValue());
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, row.getCell(9).getCellType());
            assertEquals(0.82828283d, row.getCell(11).getNumericCellValue(), 0.000000001d);
            assertEquals(0.79797980d, row.getCell(12).getNumericCellValue(), 0.000000001d,
                    "旧算法先反推并四舍五入无字段差异数量，再除以原始基数");
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, row.getCell(13).getCellType());
        }
        var view = new ReplayReportSummaryCodec().decode(
                dailyDataDao.findReportSnapshot(current).orElseThrow().summaryViewJson());
        assertEquals(new BigDecimal("0.82828283"), view.rows().get(0).values().get("successRate"));
        assertEquals(new BigDecimal("0.79797980"), view.rows().get(0).values().get("matchPassRate"));
        assertEquals(new BigDecimal("0.79797980"), view.totalRow().values().get("matchPassRate"));
        assertEquals(null, view.rows().get(0).values().get("noAction"));
        assertEquals(null, view.rows().get(0).values().get("issueTotal"));
        assertArrayEquals(bytes, service.generate(current), "已生成日报继续使用永久快照");
        assertEquals(originalSummaries, dailyDataDao.findSummaries(current));
        assertEquals(originalComparisons, dailyDataDao.findComparisons(current));
    }

    @Test
    void mixesLegacyPreviousRatesWithCurrentSplitCountsWithoutApplyingCurrentConclusionsToHistory() throws Exception {
        String previous = "RPT20260901-01", current = "RPT20260902-01";
        seedDailyBatch(previous, LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        jdbc.update("DELETE FROM dii_replay_issue_statistics_batch WHERE batch_no=?", previous);
        seedDailyBatch(current, LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        jdbc.update("UPDATE dii_replay_interface_comparison SET both_success_no_field_difference=70, "
                + "both_success_with_field_difference=10 WHERE batch_no=?", current);
        insertIssueWithOccurrence(901L, "公共组", false, "合理差异", "字段级", "无需处理", current);
        jdbc.update("UPDATE dii_replay_issue SET transaction_code='CURRENT-IFACE', review_status='APPROVED', "
                + "defect_repair_date='2026-09-02' WHERE id=901");
        captureBatchStatistics(current);

        byte[] bytes = service.generate(current);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var previousRow = findSummaryRow(workbook.getSheet("汇总信息"), previous);
            var currentRow = findSummaryRow(workbook.getSheet("汇总信息"), current);
            assertEquals(0.79797980d, previousRow.getCell(12).getNumericCellValue(), 0.000000001d);
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, previousRow.getCell(9).getCellType());
            assertEquals(0.82828283d, currentRow.getCell(12).getNumericCellValue(), 0.000000001d,
                    "当前批次使用70无差异+10已认可字段差异+2同码失败");
            assertEquals(0d, currentRow.getCell(9).getNumericCellValue());
            for (int column = 14; column <= 20; column++) {
                assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, currentRow.getCell(column).getCellType());
            }
        }
        var view = new ReplayReportSummaryCodec().decode(
                dailyDataDao.findReportSnapshot(current).orElseThrow().summaryViewJson());
        assertEquals(new BigDecimal("0.82828283"), view.rows().get(0).values().get("matchPassRate"));
    }

    @Test
    void unexpectedCurrentReadFailureIsNotTreatedAsLegacyCompatibility() {
        String current = "RPT20260902-01";
        seedDailyBatch(current, LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        var failingDao = org.mockito.Mockito.spy(issueDao);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
                .when(failingDao).findBatchReportIssueStatistics(current);
        var failingService = new ReplayIssueDailyReportService(dailyDataDao, failingDao,
                new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter(), jdbc);

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> failingService.generate(current));
        assertTrue(dailyDataDao.findReportSnapshot(current).isEmpty());
    }

    @Test
    void unexpectedPreviousReadFailureIsNotTurnedIntoABaseline() {
        String previous = "RPT20260901-01", current = "RPT20260902-01";
        seedDailyBatch(previous, LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch(current, LocalDateTime.of(2026, 9, 2, 9, 0), "CURRENT");
        var failingDao = org.mockito.Mockito.spy(issueDao);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
                .when(failingDao).findBatchReportIssueStatistics(previous);
        var failingService = new ReplayIssueDailyReportService(dailyDataDao, failingDao,
                new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter(), jdbc);

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> failingService.generate(current));
        assertTrue(dailyDataDao.findReportSnapshot(current).isEmpty());
    }

    @Test
    void rejectsMalformedBatchNumber() {
        ReplayIssueDailyReportService.MalformedBatchException exception = assertThrows(
                ReplayIssueDailyReportService.MalformedBatchException.class,
                () -> service.generate("BATCH-01"));

        assertEquals("批次号格式错误", exception.getMessage());
        assertThrows(ReplayIssueDailyReportService.MalformedBatchException.class,
                () -> service.generate("RPT"));
        assertThrows(ReplayIssueDailyReportService.MalformedBatchException.class,
                () -> service.generate("DZ"));
    }

    @Test
    void generationDoesNotCreateConfiguredSnapshotDirectory(@TempDir Path temporaryDirectory) throws Exception {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "SELECTED");
        Path configuredDirectory = temporaryDirectory.resolve("daily-reports");
        String previous = System.setProperty("replay.daily-report.directory", configuredDirectory.toString());
        try {
            assertTrue(service.generate("RPT20260902-01").length > 0);
            assertFalse(Files.exists(configuredDirectory));
        } finally {
            if (previous == null) {
                System.clearProperty("replay.daily-report.directory");
            } else {
                System.setProperty("replay.daily-report.directory", previous);
            }
        }
    }

    private void seedDailyBatch(String batchNo, LocalDateTime importedAt, String marker) {
        issueDao.beginBatchStatistics(batchNo, importedAt);
        dailyDataDao.replaceBatch(new ReplayDailyWorkbookData(batchNo,
                List.of(new ReplayDailySummaryRow(batchNo, "公共组", 10L, 100L,
                        2L, 80L, 1L, new BigDecimal("0.80"), 3L, 1, marker)),
                List.of(new ReplayInterfaceComparisonRow(batchNo, ReplayDailyRowType.DETAIL,
                        marker + "-IFACE", "S1", "交易", "开发", "行内", "公共组",
                        100L, 1L, 2L, 3L, 4L, 80L, 1L,
                        new BigDecimal("0.90"), new BigDecimal("0.80"),
                        new BigDecimal("1.25"), new BigDecimal("2.50"), 1, marker)),
                List.of(new ReplayCoverageSummaryRow(batchNo, ReplayDailyRowType.DETAIL,
                        marker + "-COVERAGE", 100L, 90L, 10L, 0L, 0L, 0L,
                        new BigDecimal("0.90"), 1, marker)),
                List.of(new ReplayCoverageDetailRow(batchNo, marker + "-COVERAGE", "交易", "公共组",
                        "S1", "", "是", LocalDate.of(2026, 9, 1), 90L,
                        "已发送", "", "开发", "行内", 1, marker))), importedAt);
    }

    private void insertIssueWithOccurrence(long issueId, String groupName, boolean sandbox,
                                           String issueType, String status, String batch) {
        insertIssueWithOccurrence(issueId, groupName, sandbox, issueType, "交易级", status, batch);
    }

    private void insertIssueWithOccurrence(long issueId, String groupName, boolean sandbox,
                                           String issueType, String issueLevel, String status, String batch) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 10, 0);
        jdbc.update("INSERT INTO dii_replay_issue(id,source_sheet,group_name,is_sandbox,row_order,domain,"
                        + "issue_type,issue_level,issue_status,imported_at,issue_id,issue_key) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                issueId, (sandbox ? "沙箱-" : "") + groupName, groupName, sandbox, 1,
                groupName, issueType, issueLevel, status, now, "issue-" + issueId, "key-" + issueId);
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch(replay_issue_id,issue_key,batch_name,"
                        + "first_occurred_at,last_occurred_at,last_status,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?)",
                issueId, "key-" + issueId, batch, now, now, status, now, now);
        if (batch.startsWith("RPT") || batch.startsWith("DZ")) {
            jdbc.update("UPDATE dii_replay_issue SET transaction_code=? WHERE id=?", "T-" + issueId, issueId);
            issueDao.beginBatchStatistics(batch, now);
            issueDao.upsertOccurrenceBatch(issueId, "key-" + issueId, batch, now,
                    com.axonlink.ai.replay.dto.ReplayIssueStatus.fromDisplayValue(status));
        }
    }

    private void captureBatchStatistics(String batch) {
        issueDao.beginBatchStatistics(batch, LocalDateTime.of(2026, 9, 2, 9, 0));
        for (var row : jdbc.queryForList("SELECT i.id,i.issue_key,i.issue_status FROM dii_replay_issue i "
                + "JOIN dii_replay_issue_occurrence_batch ob ON ob.replay_issue_id=i.id WHERE ob.batch_name=?", batch)) {
            issueDao.upsertOccurrenceBatch(((Number) row.get("id")).longValue(), row.get("issue_key").toString(),
                    batch, LocalDateTime.of(2026, 9, 2, 9, 0),
                    com.axonlink.ai.replay.dto.ReplayIssueStatus.fromDisplayValue(row.get("issue_status").toString()));
        }
    }

    private static String sheetText(Sheet sheet) {
        StringBuilder text = new StringBuilder();
        sheet.forEach(row -> row.forEach(cell -> text.append(cell).append('|')));
        return text.toString();
    }

    private static org.apache.poi.ss.usermodel.Row findSummaryRow(Sheet sheet, String batchNo) {
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            if (row.getCell(0) != null && batchNo.equals(row.getCell(0).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("Missing summary row for batch: " + batchNo);
    }

    private static void recordSnapshotConnection(JdbcTemplate jdbc, List<Connection> connections) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
        jdbc.execute((ConnectionCallback<Void>) connection -> null);
        ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(
                jdbc.getDataSource());
        assertNotNull(holder);
        connections.add(holder.getConnection());
    }
}
