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
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayWeeklyBatchScopedMetricsTest {
    private static final String START = "DZ20261029-000348-4883";
    private static final String END = "DZ20261104-201323-3518";
    private static final String OTHER = "DZ20261105-090000-9999";
    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 10, 29, 8, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 11, 4, 21, 0);

    @Test
    void weeklyKeepsEachDzBatchsMetricsAndProblemStatisticsDespiteUnrelatedSameCodeIssues() throws Exception {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        var dailyDao = new ReplayDailyDataDao(jdbc);
        var issueDao = new ReplayIssueDao(jdbc);
        saveBatch(dailyDao, START, START_AT, false);
        saveBatch(dailyDao, END, END_AT, true);
        List<String> statuses = List.of("新建", "打开", "重新打开", "延后修复", "修复待验证", "已修复", "无需处理");
        List<String> fields = List.of("字段1", "528成功ccbs失败", "528失败ccbs成功",
                "二者都失败响应码不一致", "字段5", "字段6", "免比对");
        for (int index = 0; index < statuses.size(); index++) {
            long id = index + 1L;
            boolean transaction = id == 2 || id == 3 || id == 4 || id == 7;
            insertIssue(jdbc, id, statuses.get(index), transaction ? "交易级" : "字段级", fields.get(index), "777");
            occurrence(jdbc, id, START);
            occurrence(jdbc, id, END);
        }
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED' WHERE id=7");
        long startRound = issueDao.insertImportRound("dz-start", START_AT, ReplayIssueOperator.system(), 4);
        long endRound = issueDao.insertImportRound("dz-end", END_AT, ReplayIssueOperator.system(), 4);
        original(issueDao, startRound, START, START_AT, 2L, fields.get(1), "12");
        original(issueDao, endRound, END, END_AT, 2L, fields.get(1), "18");
        original(issueDao, startRound, START, START_AT, 3L, fields.get(2), "3");
        original(issueDao, endRound, END, END_AT, 3L, fields.get(2), "5");
        original(issueDao, startRound, START, START_AT, 4L, fields.get(3), "2");
        original(issueDao, endRound, END, END_AT, 4L, fields.get(3), "4");
        original(issueDao, startRound, START, START_AT, 7L, fields.get(6), "9");
        original(issueDao, endRound, END, END_AT, 7L, fields.get(6), "25");
        insertIssue(jdbc, 8L, "打开", "交易级", "528成功ccbs失败", "888");
        insertIssue(jdbc, 9L, "无需处理", "交易级", "免比对", "999");
        jdbc.update("UPDATE dii_replay_issue SET review_status='APPROVED' WHERE id=9");
        occurrence(jdbc, 8L, OTHER);
        occurrence(jdbc, 9L, OTHER);
        var calculator = new ReplayDailyReportCalculator();
        var writer = new ReplayDailyReportWorkbookWriter();
        var daily = new ReplayIssueDailyReportService(dailyDao, issueDao, calculator, writer, jdbc);
        var weekly = new ReplayWeeklyReportService(dailyDao, issueDao,
                new ReplayWeeklyReportDao(jdbc), calculator, writer, jdbc);

        byte[] dailyContent = daily.generate(END);
        var weeklyReport = weekly.generate(START, END);

        assertReport(dailyContent);
        assertReport(weeklyReport.content());
        var codec = new ReplayReportSummaryCodec();
        assertSummary(codec.decode(dailyDao.findReportSnapshot(END).orElseThrow().summaryViewJson()));
        assertSummary(codec.decode(weeklyReport.summaryViewJson()));
    }

    private static void assertReport(byte[] content) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet("汇总信息");
            Row start = findBatch(sheet, START);
            assertTransactionMetrics(start, 12d, 3d, 2d, 9d, 0.83588175d, 0.80733945d);
            assertEquals(6d, start.getCell(13).getNumericCellValue());
            assertEquals(5d, start.getCell(14).getNumericCellValue());
            assertEquals(0.83333333d, start.getCell(24).getNumericCellValue(), 1e-9);
            Row end = findBatch(sheet, END);
            assertTransactionMetrics(end, 18d, 5d, 4d, 25d, 0.84974093d, 0.74611399d);
            assertEquals(6d, end.getCell(13).getNumericCellValue());
            assertEquals(5d, end.getCell(14).getNumericCellValue());
            assertEquals(0.16666667d, end.getCell(15).getNumericCellValue(), 1e-9);
            for (int column = 16; column <= 20; column++) {
                assertEquals(1d, end.getCell(column).getNumericCellValue());
            }
        }
    }

    private static void assertTransactionMetrics(Row row, double e, double f, double h, double j,
                                                   double successRate, double matchRate) {
        assertEquals("公共组", row.getCell(1).getStringCellValue());
        assertEquals(e, row.getCell(4).getNumericCellValue());
        assertEquals(f, row.getCell(5).getNumericCellValue());
        assertEquals(h, row.getCell(7).getNumericCellValue());
        assertEquals(j, row.getCell(9).getNumericCellValue());
        assertEquals(successRate, row.getCell(11).getNumericCellValue(), 1e-9);
        assertEquals(matchRate, row.getCell(12).getNumericCellValue(), 1e-9);
    }

    private static void assertSummary(ReplayReportSummaryView view) {
        assertEquals("DZ", view.family());
        for (var values : List.of(view.rows().get(0).values(), view.totalRow().values())) {
            assertEquals(18L, values.get("c528SuccessCcbsFail"));
            assertEquals(5L, values.get("c528FailCcbsSuccess"));
            assertEquals(4L, values.get("bothFailDiffCode"));
            assertEquals(25L, values.get("noAction"));
            assertEquals(new BigDecimal("0.74611399"), values.get("matchPassRate"));
            assertEquals(6L, values.get("issueTotal"));
            assertEquals(5L, values.get("previousUnresolvedTotal"));
            assertEquals(new BigDecimal("0.16666667"), values.get("previousResolutionRate"));
            for (String key : List.of("unanalyzed", "analyzedPendingFix", "notFullyFixed", "dataMigrationIssue", "codeNotReleased")) {
                assertEquals(1L, values.get(key));
            }
        }
    }

    private static void saveBatch(ReplayDailyDataDao dailyDao, String batch, LocalDateTime at, boolean split) {
        var summary = new ReplayDailySummaryRow(batch, "公共组", 1L, 1000L,
                20L, 800L, 10L, new BigDecimal("0.8"), 7L, 1, "{}");
        var comparison = new ReplayInterfaceComparisonRow(batch, ReplayDailyRowType.DETAIL,
                "6208", "S6208", "交易", "开发", "行内", "公共组", 1000L,
                0L, 0L, 20L, 0L, 800L, 10L, BigDecimal.ZERO, new BigDecimal("0.8"),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, "{}", split ? 700L : null, split ? 100L : null);
        dailyDao.replaceBatch(new ReplayDailyWorkbookData(batch, List.of(summary),
                List.of(comparison), List.of(), List.of()), at);
    }

    private static void insertIssue(JdbcTemplate jdbc, long id, String status, String level, String field, String count) {
        jdbc.update("INSERT INTO dii_replay_issue(id,source_sheet,group_name,is_sandbox,row_order,domain,"
                        + "issue_type,issue_level,field_name,issue_status,batch_no,transaction_code,"
                        + "affected_transaction_count,issue_key,imported_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "公共组", "公共组", false, 1, "公共组", "代码问题", level, field, status, OTHER,
                "6208", count, "dz-scope-" + id, END_AT.plusDays(1));
    }

    private static void occurrence(JdbcTemplate jdbc, long id, String batch) {
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch(replay_issue_id,issue_key,batch_name,"
                        + "first_occurred_at,last_occurred_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                id, "dz-scope-" + id, batch, START_AT, END_AT, START_AT, END_AT);
    }

    private static void original(ReplayIssueDao issueDao, long round, String batch, LocalDateTime at,
                                 long id, String field, String count) {
        String raw = "{\"id\":" + id + ",\"issueKey\":\"dz-scope-" + id + "\",\"batchNo\":\"" + batch
                + "\",\"transactionCode\":\"6208\",\"issueLevel\":\"交易级\",\"fieldName\":\"" + field
                + "\",\"affectedTransactionCount\":\"" + count + "\"}";
        issueDao.insertIssueRound(round, id, "dz-scope-" + id, true, ReplayIssueStatus.OPEN, ReplayIssueStatus.OPEN,
                "导入更新", "公共组", 1, at, raw, batch);
    }

    private static Row findBatch(Sheet sheet, String batch) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && batch.equals(row.getCell(0).toString())) return row;
        }
        throw new AssertionError("未找到批次行：" + batch);
    }
}
