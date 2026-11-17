package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateProperties;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateService;
import com.axonlink.ai.user.persistence.SysUserDao;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

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

    private void person(String source, String domain, String developer, String developerName, String owner, String ownerName) {
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,developer_usernames,developer_display,"
                + "technology_owner_usernames,technology_owner_display,imported_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                source, domain, developer, developerName + "(" + developer + ")", owner, ownerName + "(" + owner + ")");
    }

    private void currentSql(String source, boolean deleted) {
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(schema_name,table_name,domain_name,registered_date,deleted,"
                + "created_by,created_name,created_at,updated_by,updated_name,updated_at,registration_type,source_table_name,custom_sql,result_columns_json) "
                + "VALUES ('base',?,'旧领域',CURRENT_DATE,?,'u','User',CURRENT_TIMESTAMP,'u','User',CURRENT_TIMESTAMP,'CUSTOM_SQL',?,'SELECT 1','[]')",
                CODE, deleted, source);
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
