package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateProperties;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateService;
import com.axonlink.ai.user.persistence.SysUserDao;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReplayDatabaseIssueCustomSqlIdentityTest {
    private static final String CODE = "sql_0123456789abcdef0123456789abcdef";
    private static final String FIRST_BATCH = "DB20261110-01";
    private static final String SECOND_BATCH = "DB20261110-02";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueDao dao;
    private ReplayDatabaseIssueService issues;
    private ReplayDatabaseIssuePlanDateService dates;

    @BeforeEach
    void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        var migrations = List.of("V62__dii_replay_database_comparison_fields.sql", "V63__dii_replay_database_comparison_versions.sql",
                "V91__replay_db_compare_custom_sql.sql", "V77__replay_database_issues.sql", "V78__replay_database_issue_review_projection.sql",
                "V79__replay_database_issue_repair_date_projection.sql", "V80__replay_database_issue_collaborator_projection.sql",
                "V81__replay_database_issue_completion_totals.sql", "V82__replay_database_table_person.sql",
                "V83__replay_database_issue_plan_date.sql", "V89__replay_database_issue_registration_version.sql");
        new ResourceDatabasePopulator(migrations.stream().map(name -> new ClassPathResource("db/daoindex/" + name))
                .toArray(ClassPathResource[]::new)).execute(jdbc.getDataSource());
        com.axonlink.ai.replay.dbcompare.ReplayDbCompareMultiConfigurationFixture.install(jdbc);
        new ResourceDatabasePopulator(new ClassPathResource("db/user/01_user_ddl.sql")).execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES "
                + "('owner_a','负责人甲','OA',1),('owner_b','负责人乙','OB',1),('dev_a','开发甲','DA',1),('dev_b','开发乙','DB',1)");
        person("source_a", "贷款组", "dev_a", "开发甲", "owner_a", "负责人甲");
        person("source_b", "存款组", "dev_b", "开发乙", "owner_b", "负责人乙");
        person(CODE, "错误映射", "dev_b", "错误开发", "owner_b", "错误负责人");
        dao = new ReplayDatabaseIssueDao(jdbc);
        var users = new SysUserDao(jdbc);
        var policy = new ReplayIssuePlanDateService(mock(ReplayIssueDao.class), users, mock(ReplayTransactionPersonDao.class),
                new ReplayIssuePlanDateProperties());
        dates = new ReplayDatabaseIssuePlanDateService(dao, policy, users, CLOCK);
        issues = new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(), dao, users, CLOCK);
        issues.setPlanDates(dates);
    }

    @Test
    void ordinaryConfigurationAliasesRequireVersionBindingAndDisplayTheirFrozenIdentity() {
        currentSql("source_b", false);
        jdbc.update("UPDATE dii_replay_db_compare_registration SET registration_type='TABLE',custom_sql=NULL,result_columns_json=NULL,configuration_name='当前配置'");
        long version = version("v1");
        snapshot(version, "base", "source_a");
        jdbc.update("UPDATE dii_replay_db_compare_version_table SET registration_type='TABLE',custom_sql=NULL,result_columns_json=NULL,configuration_name='冻结配置'");
        assertThrows(IllegalArgumentException.class, () -> issues.importFile(aliasFile(), null, actor("dev_a")));
        issues.importFile(aliasFile(), null, actor("dev_a"), null, "v1");
        var input = dao.findByIssueId(CODE);
        var listed = item(((Number) input.get("id")).longValue(), "owner_a");
        assertEquals(CODE, listed.get("tableName"));
        assertEquals("source_a", listed.get("sourceTableName"));
        assertEquals("冻结配置", listed.get("configurationName"));
        assertEquals("贷款组", listed.get("domain"));
        assertEquals("开发甲(dev_a)", listed.get("owner"));
        assertNotNull(dao.findByIssueId(CODE + "-value"));
        jdbc.update("DELETE FROM dii_replay_database_table_person WHERE table_name='source_a'");
        assertTrue(dao.metadata(CODE, version).isEmpty());
        assertEquals("source_a", item(((Number) input.get("id")).longValue(), "owner_a").get("sourceTableName"));
    }

    @Test
    void importingOneConfigurationPreservesAbsentSiblingsFromTheSameFrozenPhysicalSource() {
        currentSql("source_a", false);
        long version = version("v1");
        snapshot(version, "base", "source_a");
        jdbc.update("UPDATE dii_replay_db_compare_version_table SET registration_type='TABLE',custom_sql=NULL,result_columns_json=NULL,configuration_name='额外配置'");
        jdbc.update("INSERT INTO dii_replay_db_compare_version_table(version_id,source_registration_id,source_registration_version,schema_name,table_name,domain_name,registered_date,registration_type,source_table_name,configuration_name) "
                + "VALUES (?,2,0,'base','source_a','贷款组',CURRENT_DATE,'TABLE','source_a','默认配置')", version);
        issues.importFile(aliasFileForBatch(FIRST_BATCH, CODE, "source_a"), null, actor("dev_a"), null, "v1");
        var before = dao.findByIssueId(CODE);
        jdbc.update("DELETE FROM dii_replay_database_table_person WHERE table_name='source_a'");
        issues.importFile(aliasFileForBatch(SECOND_BATCH, "source_a"), null, actor("dev_a"), null, "v1");
        var after = dao.findByIssueId(CODE);
        assertEquals(before.get("id"), after.get("id"));
        assertEquals(before.get("version"), after.get("version"));
        assertEquals(before.get("issueStatus"), after.get("issueStatus"));
        assertEquals("", after.get("defectRepairDate"));
        assertEquals(FIRST_BATCH, jdbc.queryForObject("SELECT batch_no FROM dii_replay_database_issue_table_state WHERE table_name=?", String.class, CODE));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history WHERE issue_id=? AND batch_no=?", Integer.class, before.get("id"), SECOND_BATCH));
    }

    @Test
    void boundSnapshotWinsOverCurrentRegistrationAndKeepsDeletedSqlIssuesMaintainable() {
        currentSql("source_b", true);
        assertEquals(java.util.Set.of(CODE), dao.customSqlComparisonCodes(List.of(CODE.toUpperCase(), "source_a")));
        long version = version("v1");
        snapshot(version, "base", "source_a");
        bind(FIRST_BATCH, version);
        long id = issue(CODE, FIRST_BATCH, "total_amt");

        var ownerView = item(id, "owner_a");
        assertEquals("贷款组", ownerView.get("domain"));
        assertEquals("开发甲(dev_a)", ownerView.get("owner"));
        assertEquals(true, ownerView.get("canReview"));
        assertEquals(false, item(id, "owner_b").get("canReview"));
        assertEquals(true, item(id, "dev_a").get("canEditPlanDate"));
        assertEquals(false, item(id, "dev_b").get("canEditPlanDate"));
        assertEquals(1L, dao.list(Map.of("domains", List.of("贷款组"))).get("total"));
        assertEquals(0L, dao.list(Map.of("domains", List.of("错误映射"))).get("total"));
        assertEquals("贷款组", dao.metadata(CODE, version).get("domain"));
        var mailPeople = dao.technologyOwners(List.of(new ReplayDatabaseIssueDao.TableIdentity(CODE, FIRST_BATCH)));
        assertEquals(1, mailPeople.size());
        assertEquals("dev_a", mailPeople.get(0).developerUsernames());
        assertEquals("汇总口径", dao.metadata(CODE, version).get("tableComment"));
        assertEquals("2026-11-11", dates.update(id, Map.of("version", 0, "plannedCompletionDate", "2026-11-11"),
                actor("dev_a")).get("plannedCompletionDate"));
        assertThrows(SecurityException.class, () -> dates.update(id,
                Map.of("version", 1, "plannedCompletionDate", "2026-11-12"), actor("dev_b")));
    }

    @Test
    void sameComparisonInDifferentBoundVersionsUsesEachIssuesFrozenSource() {
        currentSql("source_b", false);
        long firstVersion = version("v1");
        snapshot(firstVersion, "base", "source_a");
        long secondVersion = version("v2");
        snapshot(secondVersion, "base", "source_b");
        bind(FIRST_BATCH, firstVersion);
        bind(SECOND_BATCH, secondVersion);
        long first = issue(CODE, FIRST_BATCH, "field_a");
        long second = issue(CODE, SECOND_BATCH, "field_b");

        assertEquals(true, item(first, "owner_a").get("canReview"));
        assertEquals(false, item(second, "owner_a").get("canReview"));
        assertEquals(false, item(first, "owner_b").get("canReview"));
        assertEquals(true, item(second, "owner_b").get("canReview"));
        assertEquals(true, item(first, "dev_a").get("canEditPlanDate"));
        assertEquals(false, item(second, "dev_a").get("canEditPlanDate"));

        assertEquals("负责人甲(owner_a)", item(first, "owner_a").get("technologyOwnerDisplay"));
        assertEquals("负责人乙(owner_b)", item(second, "owner_a").get("technologyOwnerDisplay"));
        var result = dao.list(Map.of("technologyOwners", List.of("负责人甲(owner_a)")));
        assertEquals(1L, result.get("total"));
        assertEquals(first, ((Map<?,?>) ((List<?>) result.get("items")).get(0)).get("id"));
        assertEquals(List.of(Map.of("value", "负责人乙(owner_b)", "count", 1L)),
                dao.filterOptions("technologyOwnerDisplay", Map.of("batchNos", List.of(SECOND_BATCH))).get("items"));
    }

    @Test
    void ambiguousOrMissingBoundSnapshotNeverFallsBackToCurrentPersonnel() {
        currentSql("source_a", false);
        long ambiguousVersion = version("ambiguous");
        snapshot(ambiguousVersion, "schema_a", "source_a");
        snapshot(ambiguousVersion, "schema_b", "source_b");
        bind(FIRST_BATCH, ambiguousVersion);
        long ambiguous = issue(CODE, FIRST_BATCH, "ambiguous");
        long missingVersion = version("missing");
        bind(SECOND_BATCH, missingVersion);
        long missing = issue(CODE, SECOND_BATCH, "missing");

        for (long id : List.of(ambiguous, missing)) {
            assertEquals("", item(id, "owner_a").get("domain"));
            assertEquals(false, item(id, "owner_a").get("canReview"));
            assertEquals(false, item(id, "owner_b").get("canReview"));
            assertEquals(false, item(id, "dev_a").get("canEditPlanDate"));
        }
        assertTrue(dao.metadata(CODE, ambiguousVersion).isEmpty());
        assertTrue(dao.metadata(CODE, missingVersion).isEmpty());
        assertEquals(2L, dao.list(Map.of()).get("total"));
        assertEquals(0L, dao.list(Map.of("domains", List.of("贷款组", "存款组", "错误映射"))).get("total"));
    }

    @Test
    void ordinaryUnboundListingAndPersonnelFiltersWorkWithoutVersionTables() {
        long id = issue("source_a", FIRST_BATCH, "legacy");
        jdbc.execute("DROP TABLE dii_replay_db_compare_version_field");
        jdbc.execute("DROP TABLE dii_replay_db_compare_version_table");
        jdbc.execute("DROP TABLE dii_replay_db_compare_version");

        assertEquals(1L, dao.list(Map.of("domains", List.of("贷款组"))).get("total"));
        assertEquals(true, item(id, "owner_a").get("canReview"));
        assertEquals(true, item(id, "dev_a").get("canEditPlanDate"));
        assertEquals(List.of("贷款组"), dao.distinct("domain_name"));
        assertFalse(((List<?>) dao.filterOptions("domain", Map.of()).get("items")).isEmpty());
    }

    @Test
    void unboundOrdinaryIssueKeepsItsPhysicalPersonnelAndUnboundSqlGetsNoPermissions() {
        currentSql("source_a", true);
        long table = issue("source_a", FIRST_BATCH, "legacy");
        long sql = issue(CODE, FIRST_BATCH, "unbound");

        assertEquals("贷款组", item(table, "owner_a").get("domain"));
        assertEquals(true, item(table, "owner_a").get("canReview"));
        assertEquals(true, item(table, "dev_a").get("canEditPlanDate"));
        assertEquals("贷款组", dao.metadata("source_a").get("domain"));
        assertEquals(false, item(sql, "owner_a").get("canReview"));
        assertEquals(false, item(sql, "owner_b").get("canReview"));
        assertEquals(false, item(sql, "dev_a").get("canEditPlanDate"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void physicalTableCustomSqlKeepsUnboundIssuesMappedToItsPersonnel(boolean deleted) {
        currentSql("source_a", "source_a", deleted);
        long id = issue("source_a", FIRST_BATCH, "legacy");

        assertEquals(java.util.Set.of("source_a"),
                dao.customSqlComparisonCodes(List.of("SOURCE_A", "source_b")));
        assertEquals("贷款组", item(id, "owner_a").get("domain"));
        assertEquals("开发甲(dev_a)", item(id, "owner_a").get("owner"));
        assertEquals(true, item(id, "owner_a").get("canReview"));
        assertEquals(false, item(id, "owner_b").get("canReview"));
        assertEquals(true, item(id, "dev_a").get("canEditPlanDate"));
        assertEquals(false, item(id, "dev_b").get("canEditPlanDate"));
        assertEquals(1L, dao.list(Map.of("domains", List.of("贷款组"))).get("total"));
        assertEquals(0L, dao.list(Map.of("domains", List.of("存款组"))).get("total"));
        assertEquals("贷款组", dao.metadata("source_a").get("domain"));
        var people = dao.technologyOwners(List.of(new ReplayDatabaseIssueDao.TableIdentity("source_a", FIRST_BATCH)));
        assertEquals(1, people.size());
        assertEquals("dev_a", people.get(0).developerUsernames());
    }

    @Test
    void historicalSqlAliasStillRequiresASelectedVersionAfterRegistrationUsesPhysicalTable() {
        currentSql("source_a", "source_a", false);
        snapshot(version("v1"), "base", "source_a");

        var error = assertThrows(IllegalArgumentException.class,
                () -> issues.importFile(aliasFile(), null, actor("dev_a"), null, null));

        assertTrue(error.getMessage().contains("必须关联登记版本"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_batch", Long.class));
        assertEquals(java.util.Set.of(CODE, "source_a"),
                dao.customSqlComparisonCodes(List.of(CODE.toUpperCase(), "SOURCE_A", "source_b")));
    }

    @Test
    void unboundHistoricalSqlAliasNeverUsesPhysicalPersonnelAfterRegistrationUsesPhysicalTable() {
        currentSql("source_a", "source_a", false);
        snapshot(version("v1"), "base", "source_a");
        long id = issue(CODE, FIRST_BATCH, "unbound");

        assertEquals("", item(id, "owner_b").get("domain"));
        assertEquals("", item(id, "owner_b").get("owner"));
        assertEquals(false, item(id, "owner_b").get("canReview"));
        assertEquals(false, item(id, "dev_b").get("canEditPlanDate"));
        assertEquals(0L, dao.list(Map.of("domains", List.of("错误映射"))).get("total"));
        assertTrue(dao.distinct("domain_name").isEmpty());
        assertEquals(List.of(Map.of("value", "", "count", 1L)),
                dao.filterOptions("domain", Map.of()).get("items"));
        assertEquals("", item(id, "owner_b").get("technologyOwnerDisplay"));
        assertEquals(0L, dao.list(Map.of("technologyOwners", List.of("错误负责人(owner_b)"))).get("total"));
        assertEquals(1L, dao.list(Map.of("technologyOwners", List.of(""))).get("total"));
        assertEquals(List.of(Map.of("value", "", "count", 1L)),
                dao.filterOptions("technologyOwnerDisplay", Map.of()).get("items"));
        assertTrue(dao.metadata(CODE).isEmpty());
        assertTrue(dao.technologyOwners(List.of(new ReplayDatabaseIssueDao.TableIdentity(CODE, FIRST_BATCH))).isEmpty());
    }

    @Test
    void boundHistoricalSqlAliasKeepsFrozenPersonnelAfterRegistrationUsesPhysicalTable() {
        currentSql("source_a", "source_a", false);
        long version = version("v1");
        snapshot(version, "base", "source_a");

        issues.importFile(aliasFile(), null, actor("dev_a"), null, "v1");

        assertEquals(version, dao.batchRegistrationVersionId(FIRST_BATCH));
        var rows = dao.current();
        assertFalse(rows.isEmpty());
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            assertEquals("贷款组", item(id, "owner_a").get("domain"));
            assertEquals("开发甲(dev_a)", item(id, "owner_a").get("owner"));
            assertEquals(true, item(id, "dev_a").get("canEditPlanDate"));
            assertEquals(false, item(id, "dev_b").get("canEditPlanDate"));
        }
        assertEquals("贷款组", dao.metadata(CODE, version).get("domain"));
        var people = dao.technologyOwners(List.of(new ReplayDatabaseIssueDao.TableIdentity(CODE, FIRST_BATCH)));
        assertEquals(1, people.size());
        assertEquals("dev_a", people.get(0).developerUsernames());
    }

    private static MockMultipartFile aliasFile() {
        return aliasFileForBatch(FIRST_BATCH, CODE);
    }

    private static MockMultipartFile aliasFileForBatch(String batch, String... codes) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var tableSheet = workbook.createSheet("表明细");
            var tableHeaders = tableSheet.createRow(0);
            var headers = List.of("表", "仅ccbs存在", "仅528存在", "主键相同、字段不同", "批次号");
            for (int index = 0; index < headers.size(); index++) {
                tableHeaders.createCell(index).setCellValue(headers.get(index));
            }

            var fieldSheet = workbook.createSheet("表字段明细");
            var fieldHeaders = fieldSheet.createRow(0);
            var names = List.of("表", "字段名称", "差异数量", "批次号");
            for (int index = 0; index < names.size(); index++) {
                fieldHeaders.createCell(index).setCellValue(names.get(index));
            }
            for (int index = 0; index < codes.length; index++) {
                var table = tableSheet.createRow(index + 1);
                table.createCell(0).setCellValue(codes[index]);
                table.createCell(1).setCellValue(1);
                table.createCell(2).setCellValue(0);
                table.createCell(3).setCellValue(1);
                table.createCell(4).setCellValue(batch);
                var field = fieldSheet.createRow(index + 1);
                field.createCell(0).setCellValue(codes[index]);
                field.createCell(1).setCellValue("value");
                field.createCell(2).setCellValue(1);
                field.createCell(3).setCellValue(batch);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "database-issues.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void person(String source, String domain, String developer, String developerName, String owner, String ownerName) {
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,developer_usernames,developer_display,"
                + "technology_owner_usernames,technology_owner_display,imported_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                source, domain, developer, developerName + "(" + developer + ")", owner, ownerName + "(" + owner + ")");
    }

    private void currentSql(String source, boolean deleted) {
        currentSql(CODE, source, deleted);
    }

    private void currentSql(String code, String source, boolean deleted) {
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(schema_name,table_name,domain_name,registered_date,deleted,"
                + "created_by,created_name,created_at,updated_by,updated_name,updated_at,registration_type,source_table_name,custom_sql,result_columns_json) "
                + "VALUES ('base',?,'旧领域',CURRENT_DATE,?,'u','User',CURRENT_TIMESTAMP,'u','User',CURRENT_TIMESTAMP,'CUSTOM_SQL',?,'SELECT 1','[]')",
                code, deleted, source);
    }

    private long version(String name) {
        jdbc.update("INSERT INTO dii_replay_db_compare_version(version_no,configuration_hash,table_count,field_count,generated_by,generated_name,generated_at) "
                + "VALUES (?, ?,1,1,'u','User',CURRENT_TIMESTAMP)", name, "a".repeat(64));
        return jdbc.queryForObject("SELECT id FROM dii_replay_db_compare_version WHERE version_no=?", Long.class, name);
    }

    private void snapshot(long version, String schema, String source) {
        jdbc.update("INSERT INTO dii_replay_db_compare_version_table(version_id,source_registration_id,source_registration_version,schema_name,"
                + "table_name,table_comment,domain_name,registered_date,registration_type,source_table_name,custom_sql,result_columns_json) "
                + "VALUES (?,1,0,?,?,'汇总口径','冻结领域',CURRENT_DATE,'CUSTOM_SQL',?,'SELECT 1','[]')", version, schema, CODE, source);
    }

    private void bind(String batch, long version) {
        dao.batch(batch, "test.xlsx", "2026-11-10 10:00:00", "u", "User", 1, 1, 0, 0, version);
    }

    private long issue(String code, String batch, String field) {
        var row = new LinkedHashMap<String, Object>();
        row.put("issueId", code + "-" + field);
        row.put("tableName", code);
        row.put("tableComment", "汇总口径");
        row.put("domain", "导入旧领域");
        row.put("owner", "导入旧开发");
        row.put("issueLevel", "字段核对不一致");
        row.put("fieldName", field);
        row.put("differenceCount", 1L);
        row.put("batchNo", batch);
        row.put("issueStatus", "无需处理");
        row.put("issueType", "数据问题");
        row.put("reviewStatus", "待审核");
        row.put("firstOccurredDate", "2026-11-10");
        dao.insert(row);
        return ((Number) row.get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> item(long id, String username) {
        return ((List<Map<String, Object>>) issues.list(Map.of(), actor(username)).get("items")).stream()
                .filter(row -> ((Number) row.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    private ReplayIssueOperator actor(String username) {
        return new ReplayIssueOperator(username, username);
    }
}
