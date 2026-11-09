package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayReportBatchAlgorithmSelectionTest {
    private static final String PREVIOUS = "RPT20261029-000247-2239";
    private static final String CURRENT = "RPT20261104-194516-0435";

    static Stream<Arguments> batchFormats() {
        return Stream.of(
                Arguments.of(false, false, "0.79797980", "0.79797980"),
                Arguments.of(false, true, "0.79797980", "0.62626263"),
                Arguments.of(true, false, "0.62626263", "0.79797980"),
                Arguments.of(true, true, "0.62626263", "0.62626263"));
    }

    @ParameterizedTest(name = "previousNew={0}, currentNew={1}")
    @MethodSource("batchFormats")
    void dailyAndWeeklyChooseEachBatchAlgorithmIndependently(
            boolean previousNew, boolean currentNew, String previousRate, String currentRate) throws Exception {
        var jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        var dailyDao = new ReplayDailyDataDao(jdbc);
        var issueDao = new ReplayIssueDao(jdbc);
        save(dailyDao, issueDao, PREVIOUS, LocalDateTime.of(2026, 10, 29, 8, 0), previousNew);
        save(dailyDao, issueDao, CURRENT, LocalDateTime.of(2026, 11, 4, 20, 0), currentNew);
        var calculator = new ReplayDailyReportCalculator();
        var writer = new ReplayDailyReportWorkbookWriter();
        var daily = new ReplayIssueDailyReportService(dailyDao, issueDao, calculator, writer, jdbc);
        var weekly = new ReplayWeeklyReportService(dailyDao, issueDao,
                new ReplayWeeklyReportDao(jdbc), calculator, writer, jdbc);

        assertRates(daily.generate(CURRENT), previousRate, currentRate);
        var dailyView = new ReplayReportSummaryCodec().decode(
                dailyDao.findReportSnapshot(CURRENT).orElseThrow().summaryViewJson());
        assertEquals(new BigDecimal(currentRate), dailyView.rows().get(0).values().get("matchPassRate"));
        assertEquals(new BigDecimal(currentRate), dailyView.totalRow().values().get("matchPassRate"));

        var generatedWeekly = weekly.generate(PREVIOUS, CURRENT);
        assertRates(generatedWeekly.content(), previousRate, currentRate);
        var weeklyView = new ReplayReportSummaryCodec().decode(generatedWeekly.summaryViewJson());
        assertEquals(new BigDecimal(currentRate), weeklyView.rows().get(0).values().get("matchPassRate"));
        assertEquals(new BigDecimal(currentRate), weeklyView.totalRow().values().get("matchPassRate"));
    }

    private static void save(ReplayDailyDataDao dailyDao, ReplayIssueDao issueDao,
                             String batch, LocalDateTime at, boolean newFormat) {
        issueDao.beginBatchStatistics(batch, at);
        var summary = new ReplayDailySummaryRow(batch, "公共组", 1L, 100L,
                2L, 80L, 1L, new BigDecimal("0.8"), 0L, 1, "{}");
        var comparison = new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL,
                "6208", "S6208", "交易", "开发", "行内", "公共组", 100L,
                0L, 0L, 2L, 0L, 80L, 1L, BigDecimal.ZERO, new BigDecimal("0.8"),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}",
                newFormat ? 60L : null, newFormat ? 20L : null);
        dailyDao.replaceBatch(new ReplayDailyWorkbookData(batch, List.of(summary),
                List.of(comparison), List.of(), List.of()), at);
    }

    private static void assertRates(byte[] content, String previousRate, String currentRate) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet("汇总信息");
            assertRate(findBatch(sheet, PREVIOUS), previousRate);
            assertRate(findBatch(sheet, CURRENT), currentRate);
        }
    }

    private static void assertRate(Row row, String expectedMatchRate) {
        assertEquals("公共组", row.getCell(1).getStringCellValue());
        assertEquals(0d, row.getCell(9).getNumericCellValue(), "空问题批次的无需处理数量已知为0");
        assertEquals(0.82828283d, row.getCell(11).getNumericCellValue(), 0.000000001d);
        assertEquals(Double.parseDouble(expectedMatchRate), row.getCell(12).getNumericCellValue(), 0.000000001d);
    }

    private static Row findBatch(Sheet sheet, String batch) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && batch.equals(row.getCell(0).toString())) return row;
        }
        throw new AssertionError("未找到批次行：" + batch);
    }
}
