package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueSentTransactionCountImportTest {

    private static final String BATCH = "RPT20260904-094201-5355";
    private static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 9, 5, 2, 0);
    private static final ReplayIssueQuery ALL = new ReplayIssueQuery(200, 0, null, null, null, null, null);

    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayDailyDataDao dailyDao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        dailyDao = new ReplayDailyDataDao(jdbc);
    }

    @Test
    void importsDetailSumsWithExactTransactionIdentifiersAndLosslessListValues() throws Exception {
        MockMultipartFile file = workbook(List.of("006208", "6208", "ZERO", "MISSING"), List.of(
                detail(" 006208 ", 12L), detail("006208", 30L),
                detail("6208", 9_007_199_254_740_993L), detail("ZERO", 0L),
                total(777L)), true);

        ReplayIssueImportResult result = service(0).importFile(file);

        assertEquals(4, result.totalRows());
        assertEquals(42L, storedCountForCode("006208"));
        assertEquals(9_007_199_254_740_993L, storedCountForCode("6208"));
        assertEquals(0L, storedCountForCode("ZERO"));
        assertNull(storedCountForCode("MISSING"));
        assertEquals("9007199254740993", listedCountForCode("6208"));
        assertEquals("0", listedCountForCode("ZERO"));
        assertNull(listedCountForCode("MISSING"));
        assertEquals(5, dailyDao.findComparisons(BATCH).size());
    }

    @Test
    void dailyOnlyImportOverwritesEveryIssueInItsModeAndPreservesManualStateAndHistory() throws Exception {
        seedIssues(
                new Seed(" 6208 ", "RPT20260820-older", 101L),
                new Seed("6208", "RPT20260901-other", 102L),
                new Seed("MISSING", "RPT20260901-other", 103L),
                new Seed("6208", "DZ20260901-other", 204L),
                new Seed("6208", "OTHER20260901-other", 305L));
        jdbc.update("UPDATE dii_replay_issue SET initial_analysis='人工分析', final_solution='人工方案', "
                + "remark='人工备注', planned_completion_date=?, issue_status='无需处理'", LocalDate.of(2026, 9, 8));
        int historyBefore = tableCount("dii_replay_issue_history");
        int occurrenceBefore = tableCount("dii_replay_issue_occurrence_batch");

        ReplayIssueImportResult result = service(0).importFile(
                workbook(List.of(), List.of(detail("6208", 55L), total(999L)), false));

        assertEquals(0, result.totalRows());
        assertEquals(55L, storedCount("key-1"));
        assertEquals(55L, storedCount("key-2"));
        assertNull(storedCount("key-3"));
        assertEquals(204L, storedCount("key-4"));
        assertEquals(305L, storedCount("key-5"));
        assertEquals("人工分析", jdbc.queryForObject("SELECT initial_analysis FROM dii_replay_issue WHERE issue_key='key-1'", String.class));
        assertEquals("人工方案", jdbc.queryForObject("SELECT final_solution FROM dii_replay_issue WHERE issue_key='key-1'", String.class));
        assertEquals("人工备注", jdbc.queryForObject("SELECT remark FROM dii_replay_issue WHERE issue_key='key-1'", String.class));
        assertEquals("无需处理", dao.findCurrentByIssueKeyForUpdate("key-1").issueStatus().displayValue());
        assertEquals(LocalDate.of(2026, 9, 8), dao.findCurrentByIssueKeyForUpdate("key-1").plannedCompletionDate());
        assertEquals(historyBefore, tableCount("dii_replay_issue_history"));
        assertEquals(occurrenceBefore, tableCount("dii_replay_issue_occurrence_batch"));
        assertEquals(0, tableCount("dii_replay_import_round"));

        service(1).importFile(workbook(List.of(), List.of(detail("6208", 66L)), false), ReplayIssueImportMode.DZ);

        assertEquals(55L, storedCount("key-1"));
        assertEquals(66L, storedCount("key-4"));
        assertEquals(305L, storedCount("key-5"));
        assertEquals(1, dailyDao.findComparisons("DZ20260904-094201-5355").size());
        assertEquals(historyBefore, tableCount("dii_replay_issue_history"));
    }

    @Test
    void anyUnknownDetailMakesItsTransactionUnknownAndEveryImportClearsUnmatchedCounts() throws Exception {
        seedIssues(new Seed("6208", "RPT20260901-old", 101L),
                new Seed("ZERO", "RPT20260901-old", 102L),
                new Seed("TOTAL-ONLY", "RPT20260901-old", 103L));
        service(0).importFile(workbook(List.of(), List.of(
                detail("6208", 10L), detail("6208", null), detail("ZERO", 0L), total(999L)), false));

        assertNull(storedCount("key-1"));
        assertEquals(0L, storedCount("key-2"));
        assertNull(storedCount("key-3"));

        service(1).importFile(workbook(List.of(), List.of(detail("6208", 7L)), false));

        assertEquals(7L, storedCount("key-1"));
        assertNull(storedCount("key-2"));
        assertNull(storedCount("key-3"));
    }

    @Test
    void issueOnlyImportClearsCurrentModeWithoutFallingBackToPersistedDailyRows() throws Exception {
        seedIssues(new Seed("6208", "RPT20260901-old", 101L), new Seed("6208", "DZ20260901-old", 202L));
        MockMultipartFile full = workbook(List.of("6208"), List.of(detail("6208", 999L)), true);
        dailyDao.replaceBatch(new ReplayDailyWorkbookParser().parse(full, ReplayIssueImportMode.QUERY), IMPORTED_AT);

        service(0).importFile(withoutDailyDetails(full));

        assertNull(storedCount("key-1"));
        assertEquals(202L, storedCount("key-2"));
        assertNull(storedCountForCode("6208", BATCH));
        assertEquals(999L, dailyDao.findComparisons(BATCH).get(0).sentTransactionCount());
    }

    @Test
    void importWithoutDailyParserAlsoClearsTheModeInsteadOfRetainingStaleCounts() throws Exception {
        seedIssues(new Seed("6208", "RPT20260901-old", 101L), new Seed("6208", "DZ20260901-old", 202L));
        ReplayIssueImportService legacy = new ReplayIssueImportService(new ReplayIssueExcelParser(), dao,
                fixedClock(0), new ReplayIssueImportGate());

        legacy.importFile(withoutDailyDetails(workbook(List.of("6208"), List.of(detail("6208", 999L)), true)));

        assertNull(storedCount("key-1"));
        assertEquals(202L, storedCount("key-2"));
    }

    @Test
    void countWriteFailureRollsBackIssueMergeDailyReplacementAndExistingCount() throws Exception {
        seedIssues(new Seed("6208", "RPT20260901-old", 88L));
        MockMultipartFile oldFile = workbook(List.of(), List.of(detail("6208", 5L)), false);
        dailyDao.replaceBatch(new ReplayDailyWorkbookParser().parse(oldFile, ReplayIssueImportMode.QUERY), IMPORTED_AT);
        jdbc.execute("ALTER TABLE dii_replay_issue ADD CONSTRAINT reject_sent_77 CHECK (sent_transaction_count <> 77)");
        MockMultipartFile incoming = workbook(List.of("6208"), List.of(detail("6208", 77L)), true);

        assertThrows(RuntimeException.class, () -> service(0).importFile(incoming));

        assertEquals(1, dao.count(ALL));
        assertEquals(88L, storedCount("key-1"));
        assertEquals(5L, dailyDao.findComparisons(BATCH).get(0).sentTransactionCount());
        assertEquals(0, tableCount("dii_replay_import_round"));
        assertEquals(0, tableCount("dii_replay_issue_round"));
        assertEquals(0, tableCount("dii_replay_issue_history"));
        assertEquals(1, tableCount("dii_replay_issue_occurrence_batch"));
    }

    @Test
    void duplicateCountOverflowIsAUsefulValidationErrorBeforeAnyWrites() throws Exception {
        seedIssues(new Seed("6208", "RPT20260901-old", 88L));
        MockMultipartFile incoming = workbook(List.of("6208"),
                List.of(detail("6208", Long.MAX_VALUE), detail("6208", 1L)), true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service(0).importFile(incoming));

        assertTrue(error.getMessage().contains("接口比对明细"));
        assertTrue(error.getMessage().contains("6208"));
        assertEquals(1, dao.count(ALL));
        assertEquals(88L, storedCount("key-1"));
        assertEquals(0, tableCount("dii_replay_import_round"));
        assertEquals(0, tableCount("dii_replay_interface_comparison"));
    }

    @Test
    void unknownDetailWinsOverOverflowRegardlessOfRowOrder() throws Exception {
        seedIssues(new Seed("6208", "RPT20260901-old", 88L));

        service(0).importFile(workbook(List.of(), List.of(
                detail("6208", Long.MAX_VALUE), detail("6208", 1L), detail("6208", null)), false));

        assertNull(storedCount("key-1"));

        jdbc.update("UPDATE dii_replay_issue SET sent_transaction_count=88 WHERE issue_key='key-1'");
        service(1).importFile(workbook(List.of(), List.of(
                detail("6208", null), detail("6208", Long.MAX_VALUE), detail("6208", 1L)), false));

        assertNull(storedCount("key-1"));
        assertEquals(3, dailyDao.findComparisons(BATCH).size());
    }

    @Test
    void queryRequestWithDzWorkbookUpdatesItsActualFamilyAndPreservesQueryCounts() throws Exception {
        seedIssues(new Seed("6208", "RPT20260901-old", 101L), new Seed("6208", "DZ20260901-old", 202L));
        MockMultipartFile incoming = asDz(workbook(List.of("6208"), List.of(detail("6208", 77L)), true));

        service(0).importFile(incoming, ReplayIssueImportMode.QUERY);

        assertEquals(101L, storedCount("key-1"));
        assertEquals(77L, storedCount("key-2"));
        assertEquals(77L, storedCountForCode("6208", "DZ" + BATCH.substring(3)));
        assertEquals(1, dailyDao.findComparisons("DZ" + BATCH.substring(3)).size());
    }

    @Test
    void queryRequestWithDzIssueOnlyWorkbookClearsItsActualFamilyAndPreservesQueryCounts() throws Exception {
        seedIssues(new Seed("6208", "RPT20260901-old", 101L), new Seed("6208", "DZ20260901-old", 202L));
        MockMultipartFile incoming = withoutDailyDetails(asDz(
                workbook(List.of("6208"), List.of(detail("6208", 77L)), true)));

        service(0).importFile(incoming, ReplayIssueImportMode.QUERY);

        assertEquals(101L, storedCount("key-1"));
        assertNull(storedCount("key-2"));
        assertNull(storedCountForCode("6208", "DZ" + BATCH.substring(3)));
    }

    @Test
    void aProblemSheetSentCountDoesNotReplaceTheInterfaceSource() throws Exception {
        MockMultipartFile original = withoutDailyDetails(workbook(List.of("6208"), List.of(), true));
        MockMultipartFile source;
        try (XSSFWorkbook book = new XSSFWorkbook(original.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = book.getSheet("公共组");
            int column = sheet.getRow(0).getLastCellNum();
            sheet.getRow(0).createCell(column).setCellValue("发送交易量");
            sheet.getRow(1).createCell(column).setCellValue(999L);
            book.write(output);
            source = new MockMultipartFile("file", "issue-only.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }

        service(0).importFile(source);

        assertNull(storedCountForCode("6208"));
    }

    private ReplayIssueImportService service(int minute) {
        return new ReplayIssueImportService(new ReplayIssueExcelParser(), dao, new ReplayDailyWorkbookParser(),
                dailyDao, fixedClock(minute), new ReplayIssueImportGate());
    }

    private static Clock fixedClock(int minute) {
        return Clock.fixed(Instant.parse("2026-09-05T02:00:00Z").plusSeconds(minute * 60L), ZoneOffset.UTC);
    }

    private void seedIssues(Seed... seeds) {
        List<ReplayIssueRow> rows = new ArrayList<>();
        for (int index = 0; index < seeds.length; index++) {
            rows.add(ReplayIssueTestFixtures.row("公共组", false, index + 1, seeds[index].transactionCode(), "既有问题"));
        }
        dao.replaceAll(rows, IMPORTED_AT);
        for (int index = 0; index < seeds.length; index++) {
            jdbc.update("UPDATE dii_replay_issue SET batch_no=?, sent_transaction_count=? WHERE issue_key=?",
                    seeds[index].batch(), seeds[index].sent(), "key-" + (index + 1));
        }
    }

    private Long storedCount(String key) {
        return jdbc.queryForObject("SELECT sent_transaction_count FROM dii_replay_issue WHERE issue_key=?", Long.class, key);
    }

    private Long storedCountForCode(String code) {
        return storedCountForCode(code, BATCH);
    }

    private Long storedCountForCode(String code, String batch) {
        return jdbc.queryForObject("SELECT sent_transaction_count FROM dii_replay_issue WHERE transaction_code=? AND batch_no=?",
                Long.class, code, batch);
    }

    private Object listedCountForCode(String code) {
        return dao.list(ALL).stream().filter(row -> code.equals(row.get("transaction_code")))
                .findFirst().orElseThrow().get("sent_transaction_count");
    }

    private int tableCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Comparison detail(String code, Long sent) {
        return new Comparison(code, sent, false);
    }

    private static Comparison total(Long sent) {
        return new Comparison(null, sent, true);
    }

    private static MockMultipartFile workbook(List<String> issueCodes, List<Comparison> comparisons,
                                               boolean includeIssueSheets) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(ReplayIssueTestFixtures.dailyWorkbook().getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String name : ReplayIssueTestFixtures.TARGET_SHEETS) {
                Sheet sheet = workbook.getSheet(name);
                if (!includeIssueSheets) {
                    workbook.removeSheetAt(workbook.getSheetIndex(name));
                } else {
                    clearDataRows(sheet);
                }
            }
            if (includeIssueSheets) {
                Sheet sheet = workbook.getSheet("公共组");
                for (int index = 0; index < issueCodes.size(); index++) {
                    String code = issueCodes.get(index);
                    Row row = sheet.createRow(index + 1);
                    Map<String, String> values = Map.of(
                            "领域", "公共组", "序号", String.valueOf(index + 1), "批次", BATCH,
                            "交易码", code, "交易名称", "交易" + code, "问题级别", "交易级",
                            "问题描述", "问题" + code, "问题类型", "数据差异",
                            "issue_id", "issue-" + index, "issue_key", "TRAN|" + code + "|响应码|公共组|" + index);
                    for (int column = 0; column < ReplayIssueTestFixtures.HEADERS.size(); column++) {
                        row.createCell(column).setCellValue(values.getOrDefault(ReplayIssueTestFixtures.HEADERS.get(column), ""));
                    }
                }
            }
            Sheet comparisonSheet = workbook.getSheet("接口比对明细");
            clearDataRows(comparisonSheet);
            for (int index = 0; index < comparisons.size(); index++) {
                Comparison comparison = comparisons.get(index);
                Row row = comparisonSheet.createRow(index + 1);
                row.createCell(0).setCellValue(comparison.total() ? "合计" : BATCH);
                if (comparison.transactionCode() != null) row.createCell(1).setCellValue(comparison.transactionCode());
                if (comparison.sent() != null) row.createCell(7).setCellValue(comparison.sent().toString());
            }
            workbook.write(output);
            return file(output);
        }
    }

    private static void clearDataRows(Sheet sheet) {
        for (int index = sheet.getLastRowNum(); index > 0; index--) {
            Row row = sheet.getRow(index);
            if (row != null) sheet.removeRow(row);
        }
    }

    private static MockMultipartFile withoutDailyDetails(MockMultipartFile file) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.removeSheetAt(workbook.getSheetIndex("接口比对明细"));
            workbook.removeSheetAt(workbook.getSheetIndex("回放交易覆盖情况"));
            workbook.write(output);
            return file(output);
        }
    }

    private static MockMultipartFile asDz(MockMultipartFile file) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    row.forEach(cell -> {
                        if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().startsWith("RPT")) {
                            cell.setCellValue("DZ" + cell.getStringCellValue().substring(3));
                        }
                    });
                }
            }
            workbook.write(output);
            return file(output);
        }
    }

    private static MockMultipartFile file(ByteArrayOutputStream output) {
        return new MockMultipartFile("file", "replay-sent-volume.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }

    private record Seed(String transactionCode, String batch, Long sent) { }
    private record Comparison(String transactionCode, Long sent, boolean total) { }
}
