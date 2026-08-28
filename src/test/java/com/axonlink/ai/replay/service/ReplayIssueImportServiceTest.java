package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayIssueSummaryDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void springSelectsProductionConstructorAndWiresDependencies() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReplayIssueExcelParser.class, () -> parser);
            context.registerBean(ReplayIssueDao.class, () -> dao);
            context.registerBean(ReplayIssueImportGate.class, () -> new ReplayIssueImportGate());
            context.registerBean(ReplayIssueSummaryParser.class, ReplayIssueSummaryParser::new);
            context.registerBean(ReplayIssueSummaryDao.class, () -> new ReplayIssueSummaryDao(dao.jdbc()));
            context.registerBean(ReplayIssueDailyReportService.class,
                    () -> new ReplayIssueDailyReportService(dao, "target/test-daily-reports"));
            context.register(ReplayIssueImportService.class);
            context.refresh();

            ReplayIssueImportResult result = context.getBean(ReplayIssueImportService.class)
                    .importFile(ReplayIssueTestFixtures.validWorkbook(1));

            assertEquals(8, result.totalRows());
            assertEquals(8, dao.count(ALL));
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
}
