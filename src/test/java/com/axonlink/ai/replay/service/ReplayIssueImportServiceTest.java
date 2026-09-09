package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReplayIssueImportServiceTest {

    private static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 8, 4, 2, 0);
    private static final ReplayIssueQuery ALL = new ReplayIssueQuery(50, 0, null, null, null, null, null);

    private ReplayIssueExcelParser parser;
    private ReplayIssueDao dao;
    private ReplayIssueImportService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        parser = new ReplayIssueExcelParser();
        dao = new ReplayIssueDao(jdbc);
        service = new ReplayIssueImportService(parser, dao,
                Clock.fixed(Instant.parse("2026-08-04T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));
    }

    @Test
    void importReplacesSnapshotAndReturnsPerSheetCounts() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("旧数据", false, 1, "OLD", "old")), IMPORTED_AT);

        ReplayIssueImportResult result = service.importFile(ReplayIssueTestFixtures.validWorkbook(2));

        assertEquals(16, result.totalRows());
        assertEquals(2, result.rowsBySheet().get("沙箱-贷款组"));
        assertEquals(8, result.sandboxRows());
        assertEquals(8, result.nonSandboxRows());
        assertEquals(IMPORTED_AT, result.importedAt());
        assertEquals(17, dao.count(ALL));
    }

    @Test
    void queryAndDzImportsCannotOverwriteExistingPlannedCompletionDate() throws Exception {
        MockMultipartFile file = ReplayIssueTestFixtures.validWorkbook(1);
        var incoming = parser.parse(file, ReplayIssueImportMode.QUERY).rows().get(0);
        service.importFile(file, ReplayIssueImportMode.QUERY);
        long id = dao.findCurrentByIssueKeyForUpdate(incoming.issueKey()).id();
        dao.updatePlannedCompletionDate(id, LocalDate.of(2026, 8, 26));

        ReplayIssueImportService queryReimport = new ReplayIssueImportService(parser, dao,
                Clock.fixed(Instant.parse("2026-08-04T03:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));
        queryReimport.importFile(file, ReplayIssueImportMode.QUERY);
        assertEquals(LocalDate.of(2026, 8, 26), dao.findCurrentByIdForUpdate(id).plannedCompletionDate());

        ReplayIssueImportService dzReimport = new ReplayIssueImportService(parser, dao,
                Clock.fixed(Instant.parse("2026-08-04T04:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));
        dzReimport.importFile(file, ReplayIssueImportMode.DZ);
        assertEquals(LocalDate.of(2026, 8, 26), dao.findCurrentByIdForUpdate(id).plannedCompletionDate());
    }

    @Test
    void productionImportPersistsDailyRowsWithoutGeneratingAReport() throws Exception {
        ReplayIssueDailyReportService dailyReportService = mock(ReplayIssueDailyReportService.class);
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReplayIssueExcelParser.class, () -> parser);
            context.registerBean(ReplayIssueDao.class, () -> dao);
            context.registerBean(ReplayIssueImportGate.class, () -> new ReplayIssueImportGate());
            context.registerBean(ReplayIssueSummaryParser.class, ReplayIssueSummaryParser::new);
            context.registerBean(ReplayIssueDailyReportService.class, () -> dailyReportService);
            context.registerBean(ReplayDailyWorkbookParser.class, ReplayDailyWorkbookParser::new);
            context.registerBean(ReplayDailyDataDao.class, () -> dailyDataDao);
            context.register(ReplayIssueImportService.class);
            context.refresh();

            ReplayIssueImportResult result = context.getBean(ReplayIssueImportService.class)
                    .importFile(ReplayIssueTestFixtures.dailyWorkbook());

            assertEquals(8, result.totalRows());
            assertEquals(8, dao.count(ALL));
            assertEquals(4, dailyDataDao.findSummaries("RPT20260904-094201-5355").size());
            verifyNoInteractions(dailyReportService);
        }
    }

    @Test
    void concurrentImportIsRejectedWithoutChangingRows() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")), IMPORTED_AT);
        ReplayIssueImportService busy = new ReplayIssueImportService(parser, dao,
                Clock.fixed(IMPORTED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(0)));

        assertThrows(ReplayIssueImportBusyException.class,
                () -> busy.importFile(ReplayIssueTestFixtures.validWorkbook(1)));

        assertEquals(1, dao.count(ALL));
    }

    @Test
    void rejectsFilesOverFiftyMiB() {
        MockMultipartFile oversized = new MockMultipartFile("file", "replay-issues.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[50 * 1024 * 1024 + 1]);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.importFile(oversized));

        assertEquals("文件不能超过 50MB", exception.getMessage());
    }

    @Test
    void parserFailurePreservesSnapshotAndReleasesPermit() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")), IMPORTED_AT);
        MockMultipartFile invalid = new MockMultipartFile("file", "replay-issues.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3});

        assertThrows(Exception.class, () -> service.importFile(invalid));

        assertEquals(1, dao.count(ALL));

        ReplayIssueImportResult result = service.importFile(ReplayIssueTestFixtures.validWorkbook(1));

        assertEquals(8, result.totalRows());
        assertEquals(9, dao.count(ALL));
    }

    @Test
    void formalImportPersistsIssuesAndAllDailyDatasets() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        dailyDataDao.saveReportSnapshot(snapshot("RPT20260903-094201-5355"));
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));

        ReplayIssueImportResult result = service.importFile(ReplayIssueTestFixtures.dailyWorkbook());

        assertEquals(8, result.totalRows());
        assertEquals(8, dao.count(ALL));
        assertEquals(4, dailyDataDao.findSummaries("RPT20260904-094201-5355").size());
        assertEquals(2, dailyDataDao.findComparisons("RPT20260904-094201-5355").size());
        assertEquals(2, dailyDataDao.findCoverageSummaries("RPT20260904-094201-5355").size());
        assertEquals(1, dailyDataDao.findCoverageDetails("RPT20260904-094201-5355").size());
        assertTrue(dailyDataDao.findReportSnapshot("RPT20260903-094201-5355").isPresent());
    }

    @Test
    void formalQueryImportWithOnlyDailySheetsDoesNotChangeIssues() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        dailyDataDao.saveReportSnapshot(snapshot("RPT20260903-094201-5355"));
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "上批活动问题")),
                IMPORTED_AT);
        int importRoundsBefore = tableCount("dii_replay_import_round");
        int issueRoundsBefore = tableCount("dii_replay_issue_round");
        int historyBefore = tableCount("dii_replay_issue_history");
        int occurrenceBatchesBefore = tableCount("dii_replay_issue_occurrence_batch");

        ReplayIssueImportResult result = service.importFile(withoutIssueSheets(ReplayIssueTestFixtures.dailyWorkbook()));

        assertEquals(0, result.totalRows());
        assertEquals(0, result.autoRepairedRows());
        assertEquals(8, result.rowsBySheet().size());
        assertTrue(result.rowsBySheet().values().stream().allMatch(count -> count == 0));
        assertEquals(1, dao.count(ALL));
        assertEquals("打开", dao.findCurrentByIssueKeyForUpdate("key-1").issueStatus().displayValue());
        assertEquals("上批活动问题", dao.findCurrentByIssueKeyForUpdate("key-1").issueDescription());
        assertEquals(importRoundsBefore, tableCount("dii_replay_import_round"));
        assertEquals(issueRoundsBefore, tableCount("dii_replay_issue_round"));
        assertEquals(historyBefore, tableCount("dii_replay_issue_history"));
        assertEquals(occurrenceBatchesBefore, tableCount("dii_replay_issue_occurrence_batch"));
        assertEquals(4, dailyDataDao.findSummaries("RPT20260904-094201-5355").size());
        assertTrue(dailyDataDao.findReportSnapshot("RPT20260903-094201-5355").isPresent());
    }

    @Test
    void formalDzImportWithOnlyDailySheetsConvertsBatchWithoutChangingIssues() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "动账导入前")),
                IMPORTED_AT);
        int importRoundsBefore = tableCount("dii_replay_import_round");
        int issueRoundsBefore = tableCount("dii_replay_issue_round");
        int historyBefore = tableCount("dii_replay_issue_history");
        int occurrenceBatchesBefore = tableCount("dii_replay_issue_occurrence_batch");

        ReplayIssueImportResult result = service.importFile(
                withoutIssueSheets(ReplayIssueTestFixtures.dailyWorkbook()), ReplayIssueImportMode.DZ);

        assertEquals(0, result.totalRows());
        assertEquals(0, result.autoRepairedRows());
        assertEquals(1, dao.count(ALL));
        assertEquals("打开", dao.findCurrentByIssueKeyForUpdate("key-1").issueStatus().displayValue());
        assertEquals("动账导入前", dao.findCurrentByIssueKeyForUpdate("key-1").issueDescription());
        assertEquals(importRoundsBefore, tableCount("dii_replay_import_round"));
        assertEquals(issueRoundsBefore, tableCount("dii_replay_issue_round"));
        assertEquals(historyBefore, tableCount("dii_replay_issue_history"));
        assertEquals(occurrenceBatchesBefore, tableCount("dii_replay_issue_occurrence_batch"));
        assertEquals(4, dailyDataDao.findSummaries("DZ20260904-094201-5355").size());
        assertEquals(0, dailyDataDao.findSummaries("RPT20260904-094201-5355").size());
    }

    @Test
    void formalImportWithAnExistingEmptyIssueSheetKeepsEmptySnapshotSemantics() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "应自动修复")),
                IMPORTED_AT);

        ReplayIssueImportResult result = service.importFile(withOnlyEmptyPublicIssueSheet(
                ReplayIssueTestFixtures.dailyWorkbook()));

        assertEquals(0, result.totalRows());
        assertEquals(1, result.autoRepairedRows());
        assertEquals("已修复", dao.findCurrentByIssueKeyForUpdate("key-1").issueStatus().displayValue());
        assertEquals(1, tableCount("dii_replay_import_round"));
    }

    @Test
    void formalImportWithoutDailyDetailSheetsImportsOnlyIssues() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));

        ReplayIssueImportResult result = service.importFile(withoutDailyDetailSheets(
                ReplayIssueTestFixtures.dailyWorkbook()));

        assertEquals(8, result.totalRows());
        assertEquals(8, dao.count(ALL));
        assertEquals(0, tableCount("dii_replay_daily_summary"));
        assertEquals(0, tableCount("dii_replay_interface_comparison"));
        assertEquals(0, tableCount("dii_replay_coverage_summary"));
        assertEquals(0, tableCount("dii_replay_coverage_detail"));
    }

    @Test
    void formalDzImportWithoutDailyDetailSheetsStillConvertsIssueBatch() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));

        service.importFile(withoutDailyDetailSheets(ReplayIssueTestFixtures.dailyWorkbook()), ReplayIssueImportMode.DZ);

        assertTrue(dao.findCurrentByIssueKeyForUpdate("TRAN|6208|响应码|公共组|1")
                .batchNo().startsWith("DZ"));
        assertEquals(0, tableCount("dii_replay_daily_summary"));
    }

    @Test
    void formalImportRejectsOnlyOneMissingDailyDetailSheetBeforeWriting() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.importFile(withoutSheet(ReplayIssueTestFixtures.dailyWorkbook(), "接口比对明细")));

        assertTrue(exception.getMessage().contains("接口比对明细"));
        assertEquals(0, dao.count(ALL));
        assertEquals(0, tableCount("dii_replay_daily_summary"));
    }

    @Test
    void formalImportRejectsWorkbookWithoutDailyDetailsOrIssueSheets() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));
        MockMultipartFile file = withoutIssueSheets(withoutDailyDetailSheets(
                ReplayIssueTestFixtures.dailyWorkbook()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.importFile(file));

        assertTrue(exception.getMessage().contains("问题清单页签"));
        assertEquals(0, dao.count(ALL));
        assertEquals(0, tableCount("dii_replay_daily_summary"));
    }

    @Test
    void formalImportDoesNotAttemptToInvalidateGeneratedReports() throws Exception {
        ReplayDailyDataDao failingDailyDao = new ReplayDailyDataDao(dao.jdbc()) {
            @Override
            public int deleteAllReportSnapshots() {
                throw new IllegalStateException("snapshot invalidation failed");
            }
        };
        failingDailyDao.saveReportSnapshot(snapshot("RPT20260903-094201-5355"));
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), failingDailyDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));

        ReplayIssueImportResult result = service.importFile(ReplayIssueTestFixtures.dailyWorkbook());

        assertEquals(8, result.totalRows());
        assertEquals(8, dao.count(ALL));
        assertEquals(4, failingDailyDao.findSummaries("RPT20260904-094201-5355").size());
        assertTrue(failingDailyDao.findReportSnapshot("RPT20260903-094201-5355").isPresent());
    }

    @Test
    void dailyPersistenceFailureRollsBackIssueMerge() {
        ReplayDailyDataDao failingDailyDao = new ReplayDailyDataDao(dao.jdbc()) {
            @Override
            public void replaceBatch(ReplayDailyWorkbookData data, LocalDateTime importedAt) {
                throw new IllegalStateException("daily write failed");
            }
        };
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), failingDailyDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));

        assertThrows(IllegalStateException.class,
                () -> service.importFile(ReplayIssueTestFixtures.dailyWorkbook()));

        assertEquals(0, dao.count(ALL));
        assertEquals(0, dao.jdbc().queryForObject("SELECT COUNT(*) FROM dii_replay_issue_history", Integer.class));
        assertEquals(0, dao.jdbc().queryForObject("SELECT COUNT(*) FROM dii_replay_import_round", Integer.class));
        assertEquals(0, dao.jdbc().queryForObject("SELECT COUNT(*) FROM dii_replay_issue_occurrence_batch", Integer.class));
    }

    @Test
    void formalImportRejectsIssueBatchDifferentFromDailyBatchBeforeWriting() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(dao.jdbc());
        service = new ReplayIssueImportService(parser, dao, new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));
        MockMultipartFile file;
        MockMultipartFile original = ReplayIssueTestFixtures.dailyWorkbook();
        try (XSSFWorkbook workbook = new XSSFWorkbook(original.getInputStream());
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            workbook.getSheet("公共组").getRow(1).getCell(2).setCellValue("RPT20260904-094201-OTHER");
            workbook.write(output);
            file = new MockMultipartFile("file", "batch-mismatch.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.importFile(file));

        assertTrue(exception.getMessage().contains("批次"));
        assertEquals(0, dao.count(ALL));
    }

    private static ReplayDailyReportSnapshot snapshot(String batchNo) {
        return new ReplayDailyReportSnapshot(batchNo, batchNo + "日报.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3}, 3L, LocalDateTime.of(2026, 9, 4, 10, 30));
    }

    private int tableCount(String tableName) {
        return dao.jdbc().queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private static MockMultipartFile withoutIssueSheets(MockMultipartFile source) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(source.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String sheetName : ReplayIssueTestFixtures.TARGET_SHEETS) {
                int sheetIndex = workbook.getSheetIndex(sheetName);
                if (sheetIndex >= 0) {
                    workbook.removeSheetAt(sheetIndex);
                }
            }
            workbook.write(output);
            return new MockMultipartFile("file", "daily-without-issues.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private static MockMultipartFile withOnlyEmptyPublicIssueSheet(MockMultipartFile source) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(source.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String sheetName : ReplayIssueTestFixtures.TARGET_SHEETS) {
                if ("公共组".equals(sheetName)) {
                    continue;
                }
                int sheetIndex = workbook.getSheetIndex(sheetName);
                if (sheetIndex >= 0) {
                    workbook.removeSheetAt(sheetIndex);
                }
            }
            workbook.getSheet("公共组").removeRow(workbook.getSheet("公共组").getRow(1));
            workbook.write(output);
            return new MockMultipartFile("file", "daily-with-empty-public-issue-sheet.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private static MockMultipartFile withoutDailyDetailSheets(MockMultipartFile source) throws Exception {
        return withoutSheet(withoutSheet(source, "接口比对明细"), "回放交易覆盖情况");
    }

    private static MockMultipartFile withoutSheet(MockMultipartFile source, String sheetName) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(source.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int sheetIndex = workbook.getSheetIndex(sheetName);
            if (sheetIndex >= 0) {
                workbook.removeSheetAt(sheetIndex);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "replay-import.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
