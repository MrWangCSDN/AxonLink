package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayLegacyProblemStatisticsReportTest {
    private static final String PREVIOUS = "RPT20261103-090016-9885";
    private static final String CURRENT = "RPT20261104-194516-0435";
    private static final LocalDateTime PREVIOUS_AT = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final LocalDateTime CURRENT_AT = LocalDateTime.of(2026, 11, 4, 20, 0);
    private JdbcTemplate jdbc;
    private ReplayDailyDataDao dailyDao;
    private ReplayIssueDao issueDao;
    private ReplayIssueDailyReportService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dailyDao = new ReplayDailyDataDao(jdbc);
        issueDao = new ReplayIssueDao(jdbc);
        service = new ReplayIssueDailyReportService(dailyDao, issueDao,
                new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter(), jdbc);
    }

    @Test
    void recoversLegacyProblemStatisticsWithoutBorrowingLaterBatchQuantities() throws Exception {
        saveBatch(PREVIOUS, PREVIOUS_AT, 7L);
        saveBatch(CURRENT, CURRENT_AT, 1L);
        issueDao.beginBatchStatistics(CURRENT, CURRENT_AT);
        List<String> statuses = List.of("新建", "打开", "重新打开", "延后修复", "修复待验证", "已修复");
        for (int index = 0; index < statuses.size(); index++) {
            long id = index + 1L;
            insertIssue(id, CURRENT, statuses.get(index), id == 2 ? "交易级" : "字段级",
                    id == 2 ? "528成功ccbs失败" : "字段" + id,
                    id == 3 ? "历史脏数据" : id == 2 ? "99" : "0");
            occurrence(id, PREVIOUS);
        }
        insertIssue(7L, CURRENT, "无需处理", "交易级", "免比对", "25");
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED' WHERE id=7");
        occurrence(7L, PREVIOUS);
        issueDao.upsertOccurrenceBatch(7L, "legacy-7", CURRENT, CURRENT_AT, ReplayIssueStatus.NO_ACTION);
        long round = issueDao.insertImportRound("legacy-original", PREVIOUS_AT, ReplayIssueOperator.system(), 2);
        original(round, 2L, "528成功ccbs失败", "12");
        original(round, 7L, "免比对", "9");

        byte[] bytes = service.generate(CURRENT);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("汇总信息");
            Row previous = findBatch(sheet, PREVIOUS);
            assertEquals(12d, previous.getCell(4).getNumericCellValue(), "前批原始交易问题影响量为12，不能借后批99");
            assertEquals(0d, previous.getCell(5).getNumericCellValue());
            assertEquals(0d, previous.getCell(7).getNumericCellValue());
            assertEquals(9d, previous.getCell(9).getNumericCellValue(), "前批无需处理原量9，不能借后批25");
            assertEquals(6d, previous.getCell(13).getNumericCellValue(), "问题总数排除无需处理，字段脏数量不影响计数");
            assertEquals(5d, previous.getCell(14).getNumericCellValue(), "原口径统计已分析问题类型");
            assertEquals(0.83333333d, previous.getCell(24).getNumericCellValue(), 0.000000001d);
            Row current = findBatch(sheet, CURRENT);
            assertEquals(25d, current.getCell(9).getNumericCellValue());
            assertUnresolved(current, 5d, 0.16666667d, 1d);
        }
        ReplayReportSummaryView view = savedView();
        for (var values : List.of(view.rows().get(0).values(), view.totalRow().values())) {
            assertEquals(5L, values.get("previousUnresolvedTotal"));
            assertEquals(new BigDecimal("0.16666667"), values.get("previousResolutionRate"));
            for (String key : List.of("unanalyzed", "analyzedPendingFix", "notFullyFixed", "dataMigrationIssue", "codeNotReleased")) {
                assertEquals(1L, values.get(key));
            }
            assertEquals(25L, values.get("noAction"));
        }
        String html = new ReplayReportMailHtmlRenderer().render("日报", List.of(view));
        String detail = html.substring(html.indexOf("<tbody>"), html.indexOf("</tr>", html.indexOf("<tbody>")));
        List<String> cells = Pattern.compile("<td[^>]*>(.*?)</td>").matcher(detail).results()
                .map(match -> match.group(1)).toList();
        assertEquals(List.of("5", "16.67%", "1", "1", "1", "1", "1"), cells.subList(14, 21));
        var weekly = new ReplayWeeklyReportService(dailyDao, issueDao,
                new com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao(jdbc),
                new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter(), jdbc).generate(PREVIOUS, CURRENT);
        var weeklyView = new ReplayReportSummaryCodec().decode(weekly.summaryViewJson());
        assertEquals(5L, weeklyView.totalRow().values().get("previousUnresolvedTotal"));
        assertEquals(new BigDecimal("0.16666667"), weeklyView.totalRow().values().get("previousResolutionRate"));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(weekly.content()))) {
            var previous = findBatch(workbook.getSheet("汇总信息"), PREVIOUS);
            assertEquals(6d, previous.getCell(13).getNumericCellValue());
            assertEquals(12d, previous.getCell(4).getNumericCellValue());
            assertEquals(9d, previous.getCell(9).getNumericCellValue());
            assertUnresolved(findBatch(workbook.getSheet("汇总信息"), CURRENT), 5d, 0.16666667d, 1d);
        }
    }

    @Test
    void regeneratesLegacyProblemConclusionsWhileGenerateKeepsItsSavedReport() throws Exception {
        saveBatch(PREVIOUS, PREVIOUS_AT, 1L);
        saveBatch(CURRENT, CURRENT_AT, 0L);
        issueDao.beginBatchStatistics(CURRENT, CURRENT_AT);
        insertIssue(11L, PREVIOUS, "打开", "字段级", "字段", "0");
        occurrence(11L, PREVIOUS);
        byte[] first = service.generate(CURRENT);
        assertEquals(1L, savedView().rows().get(0).values().get("previousUnresolvedTotal"));

        jdbc.update("UPDATE dii_replay_issue SET issue_status='已修复',issue_type='参数问题' WHERE id=11");

        assertArrayEquals(first, service.generate(CURRENT));
        byte[] regenerated = service.regenerate(CURRENT);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(regenerated))) {
            Sheet sheet = workbook.getSheet("汇总信息");
            Row previous = findBatch(sheet, PREVIOUS);
            assertEquals(1d, previous.getCell(13).getNumericCellValue());
            assertEquals(0d, previous.getCell(14).getNumericCellValue());
            assertEquals(1d, previous.getCell(15).getNumericCellValue(), "重新生成使用本批问题的当前类型");
            assertUnresolved(findBatch(sheet, CURRENT), 0d, 1d, 0d);
        }
        assertEquals(0L, savedView().rows().get(0).values().get("previousUnresolvedTotal"));
        assertEquals(new BigDecimal("1.00000000"), savedView().rows().get(0).values().get("previousResolutionRate"));
    }

    @Test
    void keepsCompleteFrozenStatisticsInsteadOfReplacingThemWithLegacyCurrentConclusions() throws Exception {
        saveBatch(PREVIOUS, PREVIOUS_AT, 1L);
        issueDao.beginBatchStatistics(PREVIOUS, PREVIOUS_AT);
        insertIssue(21L, PREVIOUS, "打开", "字段级", "字段", "0");
        issueDao.upsertOccurrenceBatch(21L, "legacy-21", PREVIOUS, PREVIOUS_AT, ReplayIssueStatus.OPEN);
        saveBatch(CURRENT, CURRENT_AT, 0L);
        issueDao.beginBatchStatistics(CURRENT, CURRENT_AT);
        jdbc.update("UPDATE dii_replay_issue SET issue_status='已修复',issue_type='参数问题' WHERE id=21");

        byte[] bytes = service.generate(CURRENT);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("汇总信息");
            Row previous = findBatch(sheet, PREVIOUS);
            assertEquals(1d, previous.getCell(14).getNumericCellValue());
            assertEquals(0d, previous.getCell(15).getNumericCellValue());
            Row current = findBatch(sheet, CURRENT);
            assertEquals(1d, current.getCell(14).getNumericCellValue());
            assertEquals(0d, current.getCell(15).getNumericCellValue());
            assertEquals(1d, current.getCell(17).getNumericCellValue());
        }
    }

    private void saveBatch(String batch, LocalDateTime at, long sourceIssueTotal) {
        var summary = new ReplayDailySummaryRow(batch, "公共组", 1L, 100L,
                2L, 80L, 1L, new BigDecimal("0.8"), sourceIssueTotal, 1, "{}");
        var comparison = new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL,
                "6208", "S6208", "交易", "开发", "行内", "公共组", 100L,
                0L, 0L, 2L, 0L, 80L, 1L, BigDecimal.ZERO, new BigDecimal("0.8"),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}");
        dailyDao.replaceBatch(new ReplayDailyWorkbookData(batch, List.of(summary),
                List.of(comparison), List.of(), List.of()), at);
    }

    private void insertIssue(long id, String batch, String status, String level, String field, String quantity) {
        jdbc.update("INSERT INTO dii_replay_issue(id,source_sheet,group_name,is_sandbox,row_order,domain,"
                        + "issue_type,issue_level,field_name,issue_status,batch_no,transaction_code,"
                        + "affected_transaction_count,issue_key,imported_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "公共组", "公共组", false, 1, "公共组", "代码问题", level, field, status, batch,
                "6208", quantity, "legacy-" + id, CURRENT_AT);
    }

    private void occurrence(long id, String batch) {
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch(replay_issue_id,issue_key,batch_name,"
                        + "first_occurred_at,last_occurred_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                id, "legacy-" + id, batch, PREVIOUS_AT, PREVIOUS_AT, PREVIOUS_AT, PREVIOUS_AT);
    }

    private void original(long round, long id, String field, String quantity) {
        String raw = "{\"id\":" + id + ",\"issueKey\":\"legacy-" + id + "\",\"batchNo\":\"" + PREVIOUS
                + "\",\"transactionCode\":\"6208\",\"issueLevel\":\"交易级\",\"fieldName\":\"" + field
                + "\",\"affectedTransactionCount\":\"" + quantity + "\"}";
        issueDao.insertIssueRound(round, id, "legacy-" + id, true, ReplayIssueStatus.NEW, ReplayIssueStatus.OPEN,
                "导入更新", "公共组", 1, PREVIOUS_AT, raw, PREVIOUS);
    }

    private ReplayReportSummaryView savedView() {
        return new ReplayReportSummaryCodec().decode(dailyDao.findReportSnapshot(CURRENT).orElseThrow().summaryViewJson());
    }

    private static void assertUnresolved(Row row, double total, double rate, double categoryCount) {
        assertEquals(total, row.getCell(14).getNumericCellValue());
        assertEquals(rate, row.getCell(15).getNumericCellValue(), 0.000000001d);
        for (int column = 16; column <= 20; column++) {
            assertEquals(categoryCount, row.getCell(column).getNumericCellValue());
        }
    }

    private static Row findBatch(Sheet sheet, String batch) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && batch.equals(row.getCell(0).toString())) return row;
        }
        throw new AssertionError("未找到批次行：" + batch);
    }
}
