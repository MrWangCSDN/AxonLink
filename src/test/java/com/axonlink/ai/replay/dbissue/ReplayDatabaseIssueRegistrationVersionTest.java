package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.security.UserPrincipalResolver;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReplayDatabaseIssueRegistrationVersionTest {
    private static final String BATCH = "DB20261105-1";
    private static final String FIRST = "20261027-141302";
    private static final String SECOND = "20261104-201323";
    private static final ReplayIssueOperator ACTOR = new ReplayIssueOperator("editor", "编辑人");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueDao dao;
    private ReplayDatabaseIssueService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(List.of(
                "V62__dii_replay_database_comparison_fields.sql", "V63__dii_replay_database_comparison_versions.sql",
                "V77__replay_database_issues.sql", "V78__replay_database_issue_review_projection.sql",
                "V79__replay_database_issue_repair_date_projection.sql", "V80__replay_database_issue_collaborator_projection.sql",
                "V81__replay_database_issue_completion_totals.sql", "V82__replay_database_table_person.sql",
                "V83__replay_database_issue_plan_date.sql", "V89__replay_database_issue_registration_version.sql").stream()
                .map(name -> new ClassPathResource("db/daoindex/" + name)).toArray(ClassPathResource[]::new))
                .execute(jdbc.getDataSource());
        dao = new ReplayDatabaseIssueDao(jdbc);
        service = new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(), dao, new SysUserDao(jdbc), CLOCK);
        var resolver = mock(UserPrincipalResolver.class);
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP", "editor", null));
        mvc = MockMvcBuilders.standaloneSetup(new ReplayDatabaseIssueController(dao, service, resolver,
                new DaoIndexAnalysisProperties())).build();
    }

    @Test
    void importsWithSelectedHistoricalVersionAndExposesThePersistedContext() throws Exception {
        long id = version(FIRST, 0);
        table(id, "public", "ACCT_MASTER");

        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import")
                        .file(file("acct_master")).param("registrationVersionNo", FIRST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.registrationVersionNo").value(FIRST));

        assertEquals(id, binding());
        mvc.perform(get("/api/ai/parallel-replay/database-issues/import-context").param("batchNo", BATCH))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.batchNo").value(BATCH))
                .andExpect(jsonPath("$.data.registrationVersion.versionNo").value(FIRST))
                .andExpect(jsonPath("$.data.registrationVersion.generatedName").value("版本生成者"))
                .andExpect(jsonPath("$.data.registrationVersion.tableCount").value(1))
                .andExpect(jsonPath("$.data.registrationVersion.fieldCount").value(0))
                .andExpect(jsonPath("$.data.registrationVersion.generatedAt").isNotEmpty());
    }

    @Test
    void anonymousImportContextRequestsRequireLogin() throws Exception {
        var anonymousResolver = mock(UserPrincipalResolver.class);
        var anonymousMvc = MockMvcBuilders.standaloneSetup(new ReplayDatabaseIssueController(
                dao, service, anonymousResolver, new DaoIndexAnalysisProperties())).build();

        anonymousMvc.perform(get("/api/ai/parallel-replay/database-issues/import-context").param("batchNo", BATCH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    void sameBatchPartialReimportWithoutVersionKeepsTheOriginalBindingAfterANewerVersionExists() {
        long first = version(FIRST, 2);
        table(first, "public", "acct_master");
        table(first, "public", "txn_log");
        service.importFile(file("acct_master"), null, ACTOR, null, FIRST);
        long second = version(SECOND, 3);
        table(second, "public", "acct_master");

        var response = service.importFile(file("txn_log"), null, ACTOR);

        assertEquals(first, binding());
        assertEquals(FIRST, response.get("registrationVersionNo"));
        assertNotNull(dao.findByIssueId("txn_log"));
    }

    @Test
    void aLegacyUnboundBatchCanFirstBindDuringASameBatchReimportAndRetainThatBinding() {
        service.importFile(file("acct_master"), null, ACTOR);
        assertNull(binding());
        long first = version(FIRST, 0);
        table(first, "public", "acct_master");

        var bound = service.importFile(file("acct_master"), null, ACTOR, null, FIRST);
        var reused = service.importFile(file("acct_master"), null, ACTOR, null);

        assertEquals(first, binding());
        assertEquals(FIRST, bound.get("registrationVersionNo"));
        assertEquals(FIRST, reused.get("registrationVersionNo"));
        assertEquals(1L, count("dii_replay_database_issue_batch"));
    }

    @Test
    void explicitlyChangingABoundVersionReturnsConflictAndLeavesImportedRowsIntact() throws Exception {
        long first = version(FIRST, 1);
        table(first, "public", "acct_master");
        long second = version(SECOND, 2);
        table(second, "public", "acct_master");
        service.importFile(file("acct_master"), null, ACTOR, null, FIRST);
        String before = jdbc.queryForObject("SELECT data_snapshot FROM dii_replay_database_issue WHERE issue_id='acct_master'", String.class);

        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import")
                        .file(file("acct_master")).param("registrationVersionNo", SECOND))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", containsString(FIRST)));

        assertEquals(first, binding());
        assertEquals(before, jdbc.queryForObject("SELECT data_snapshot FROM dii_replay_database_issue WHERE issue_id='acct_master'", String.class));
    }

    @Test
    void aMissingSelectedVersionReturnsNotFoundBeforeAnyIssueWrite() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import")
                        .file(file("acct_master")).param("registrationVersionNo", "missing-version"))
                .andExpect(status().isNotFound());
        assertEquals(0L, count("dii_replay_database_issue"));
        assertEquals(0L, count("dii_replay_database_issue_history"));
        assertEquals(0L, count("dii_replay_database_issue_batch"));
    }

    @Test
    void versionCoverageReportsEveryMissingAndAmbiguousTableBeforeAnyIssueWrite() throws Exception {
        long version = version(FIRST, 0);
        table(version, "schema_a", "acct_master");
        table(version, "schema_b", "ACCT_MASTER");

        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import")
                        .file(file("acct_master", "missing_table")).param("registrationVersionNo", FIRST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("acct_master")))
                .andExpect(jsonPath("$.message", containsString("missing_table")));
        assertEquals(0L, count("dii_replay_database_issue"));
        assertEquals(0L, count("dii_replay_database_issue_batch"));
    }

    @Test
    void aUniqueVersionTableWithNoFieldsAcceptsAnImportedFieldDifference() {
        long version = version(FIRST, 0);
        table(version, "public", "acct_master");

        service.importFile(file("acct_master"), null, ACTOR, null, FIRST);

        assertEquals(version, binding());
        assertNotNull(dao.findByIssueId("acct_master-value"));
    }

    @Test
    void legacyImportWithoutASelectedVersionStillWorksWithoutVersionTables() throws Exception {
        jdbc.execute("DROP TABLE dii_replay_db_compare_version_field");
        jdbc.execute("DROP TABLE dii_replay_db_compare_version_table");
        jdbc.execute("DROP TABLE dii_replay_db_compare_version");

        var response = service.importFile(file("acct_master"), null, ACTOR, null);

        assertNull(binding());
        assertTrue(response.get("registrationVersionNo") == null || "".equals(response.get("registrationVersionNo")));
        mvc.perform(get("/api/ai/parallel-replay/database-issues/import-context").param("batchNo", BATCH))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.registrationVersion").isEmpty());
    }

    @Test
    void failureAfterBatchMetadataWriteRollsBackBindingIssuesAndHistoryTogether() {
        long version = version(FIRST, 0);
        table(version, "public", "acct_master");
        var failingDao = new ReplayDatabaseIssueDao(jdbc) {
            @Override
            public void batch(String batch, String filename, String at, String username, String name,
                              int input, int created, int updated, int repaired, Long registrationVersionId) {
                super.batch(batch, filename, at, username, name, input, created, updated, repaired, registrationVersionId);
                throw new IllegalStateException("metadata failure");
            }
        };
        var failing = new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(), failingDao, new SysUserDao(jdbc), CLOCK);

        assertThrows(IllegalStateException.class, () -> failing.importFile(file("acct_master"), null, ACTOR, null, FIRST));

        assertEquals(0L, count("dii_replay_database_issue_batch"));
        assertEquals(0L, count("dii_replay_database_issue"));
        assertEquals(0L, count("dii_replay_database_issue_history"));
    }

    private Long binding() {
        return jdbc.queryForObject("SELECT registration_version_id FROM dii_replay_database_issue_batch WHERE batch_no=?", Long.class, BATCH);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long version(String number, int fields) {
        jdbc.update("INSERT INTO dii_replay_db_compare_version(version_no,configuration_hash,table_count,field_count,generated_by,generated_name,generated_at) "
                + "VALUES (?,REPEAT('a',64),1,?,'generator','版本生成者','2026-11-04 20:00:00')", number, fields);
        return jdbc.queryForObject("SELECT id FROM dii_replay_db_compare_version WHERE version_no=?", Long.class, number);
    }

    private void table(long version, String schema, String table) {
        jdbc.update("INSERT INTO dii_replay_db_compare_version_table(version_id,source_registration_id,source_registration_version,schema_name,table_name,domain_name,registered_date) "
                + "VALUES (?,1,1,?,?,'贷款组','2026-10-27')", version, schema, table);
    }

    private static MockMultipartFile file(String... tables) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var tableSheet = workbook.createSheet("表明细");
            var fieldSheet = workbook.createSheet("表字段明细");
            var th = tableSheet.createRow(0);
            List<String> headers = List.of("表", "仅ccbs存在", "仅528存在", "主键相同、字段不同", "批次号");
            for (int index = 0; index < headers.size(); index++) th.createCell(index).setCellValue(headers.get(index));
            var fh = fieldSheet.createRow(0);
            List<String> fieldHeaders = List.of("表", "字段名称", "差异数量", "批次号");
            for (int index = 0; index < fieldHeaders.size(); index++) fh.createCell(index).setCellValue(fieldHeaders.get(index));
            for (int index = 0; index < tables.length; index++) {
                var row = tableSheet.createRow(index + 1);
                row.createCell(0).setCellValue(tables[index]); row.createCell(1).setCellValue(1);
                row.createCell(2).setCellValue(0); row.createCell(3).setCellValue(1); row.createCell(4).setCellValue(BATCH);
                var field = fieldSheet.createRow(index + 1);
                field.createCell(0).setCellValue(tables[index]); field.createCell(1).setCellValue("value");
                field.createCell(2).setCellValue(1); field.createCell(3).setCellValue(BATCH);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "database-issues.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
