package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.*;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.service.*;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Resolves the production Spring import entry point, rather than constructing a merge by hand. */
class ReplayReopenImportIntegrationTest extends ReplayReopenTestSupport {
    private AnnotationConfigApplicationContext context;
    private ReplayIssueImportService imports;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void productionImportEntryPoint() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(com.axonlink.ai.replay.persistence.ReplayIssueDao.class, () -> issues);
        context.registerBean(ReplayReopenRecorder.class, () -> recorder);
        context.registerBean(ReplayDailyDataDao.class, () -> new ReplayDailyDataDao(jdbc));
        context.register(ReplayIssueExcelParser.class, ReplayDailyWorkbookParser.class,
                ReplayIssueImportGate.class, ReplayIssueMergeService.class, ReplayIssueImportService.class);
        context.refresh();
        imports = context.getBean(ReplayIssueImportService.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void managedImportRecordsFactsForIssueOnlyAndDailyWorkbooks(boolean daily) throws Exception {
        MockMultipartFile file = daily ? ReplayIssueTestFixtures.dailyWorkbook() : file("RPT20261022-1");
        var parser = context.getBean(ReplayIssueExcelParser.class);
        var incoming = parser.parse(file, ReplayIssueImportMode.QUERY).rows().get(0);
        imports.importFile(file);
        long id = issues.findCurrentByIssueKeyForUpdate(incoming.issueKey()).id();
        jdbc.update("UPDATE dii_replay_issue SET issue_status='修复待验证' WHERE id=?", id);

        var result = imports.importFile(file);

        assertEquals(1L, dao.issueCounts(List.of(incoming.issueKey())).get(incoming.issueKey()));
        assertEquals(ReplayIssueStatus.REOPENED, issues.findCurrentByIdForUpdate(id).issueStatus());
        var fact = dao.events(filter()).items().get(0);
        assertEquals(incoming.batchNo(), fact.batchName());
        assertEquals(1, json.valueToTree(result).path("reopenedRows").asInt(-1));
        assertEquals(fact.sourceRoundId(), json.valueToTree(result).path("importRoundId").asLong(-1));
    }

    @Test
    void repeatedImportsInSameAndLaterBatchInheritReopenedStatusWithoutCountingAgain() throws Exception {
        issues.insertCurrent(row(ReplayIssueStatus.PENDING_VERIFICATION));
        imports.importFile(file("RPT20261022-1"));
        assertEquals(1L, dao.issueCounts(List.of("K")).get("K"));
        var original = dao.events(filter()).items().get(0);

        for (String batch : List.of("RPT20261022-1", "RPT20261023-1")) {
            var result = imports.importFile(file(batch));
            assertEquals(0, json.valueToTree(result).path("reopenedRows").asInt(-1));
            assertEquals(1, json.valueToTree(result).path("inheritedReopenedRows").asInt(-1));
            assertEquals(1L, dao.issueCounts(List.of("K")).get("K"));
            assertEquals(original, dao.events(filter()).items().get(0));
        }
    }

    @Test
    void manualStatusChangeKeepsOriginalBatchFactAndNextRealReopenAddsAnotherFact() throws Exception {
        long id = issues.insertCurrent(row(ReplayIssueStatus.PENDING_VERIFICATION));
        imports.importFile(file("RPT20261022-1"));
        assertEquals(1L, dao.issueCounts(List.of("K")).get("K"));
        var original = dao.events(filter()).items().get(0);
        var edit = new ReplayIssueEditService(issues, new SysUserDao(jdbc));
        var operator = new ReplayIssueOperator("alice", "Alice");
        edit.update(id, new ReplayIssueUpdateRequest(ReplayIssueStatus.DEFERRED,
                "代码问题", "重新排查", "", null), operator);

        assertEquals(ReplayIssueStatus.DEFERRED, issues.findCurrentByIdForUpdate(id).issueStatus());
        assertEquals(original, dao.events(filter()).items().get(0));
        assertEquals(1, dao.statistics(filter()).totalEvents());
        assertEquals("RPT20261022-1", dao.statistics(filter()).batches().get(0).batchName());

        edit.update(id, new ReplayIssueUpdateRequest(ReplayIssueStatus.PENDING_VERIFICATION,
                "代码问题", "已再次修复", "完成修复", null), operator);
        // A release after the real manual save satisfies the existing release-time protection.
        var result = imports.importFile(file("RPT20261023-1"), ReplayIssueImportMode.QUERY,
                "2099-10-23 12:00:00");
        assertEquals(1, json.valueToTree(result).path("reopenedRows").asInt(-1));
        assertEquals(0, json.valueToTree(result).path("inheritedReopenedRows").asInt(-1));
        assertEquals(2L, dao.issueCounts(List.of("K")).get("K"));
        assertTrue(dao.events(filter()).items().contains(original));
        assertEquals(2, dao.statistics(filter()).batches().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedFactWriteRollsBackProductionImportIncludingDailyData(boolean daily) throws Exception {
        MockMultipartFile file = daily ? ReplayIssueTestFixtures.dailyWorkbook() : file("RPT20261022-1");
        var incoming = context.getBean(ReplayIssueExcelParser.class).parse(file, ReplayIssueImportMode.QUERY).rows().get(0);
        imports.importFile(file);
        long id = issues.findCurrentByIssueKeyForUpdate(incoming.issueKey()).id();
        jdbc.update("UPDATE dii_replay_issue SET issue_status='修复待验证' WHERE id=?", id);
        jdbc.execute("ALTER TABLE dii_replay_reopen_event ADD CONSTRAINT reject_import_fact CHECK(occurrence_no<1)");
        long beforeDailyRows = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_daily_summary", Long.class);

        assertThrows(RuntimeException.class, () -> imports.importFile(file));

        assertEquals(ReplayIssueStatus.PENDING_VERIFICATION, issues.findCurrentByIdForUpdate(id).issueStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_import_round", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_reopen_event", Long.class));
        assertEquals(beforeDailyRows, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_daily_summary", Long.class));
    }

    private MockMultipartFile file(String batch) {
        return ReplayIssueTestFixtures.workbook(Map.of("公共组", List.of(Map.of(
                "领域", "公共组", "批次", batch, "交易码", "6208", "交易名称", "交易",
                "问题描述", "描述", "issue_id", "I", "issue_key", "K"))), ReplayIssueTestFixtures.HEADERS, true);
    }
}
