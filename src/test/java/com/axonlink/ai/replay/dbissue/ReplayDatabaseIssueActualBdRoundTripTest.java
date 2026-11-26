package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.ReplayDbCompareMultiConfigurationFixture;
import com.axonlink.ai.replay.dbissue.report.ReplayDatabaseIssueReportDao;
import com.axonlink.ai.replay.dbissue.report.ReplayDatabaseIssueReportService;
import com.axonlink.ai.replay.dbissue.report.ReplayDatabaseIssueReportWorkbookWriter;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Consumes the unchanged workbook produced by the real BD export after importing Axon's NEW17 package. */
class ReplayDatabaseIssueActualBdRoundTripTest {
    private static final String FIXTURES = "replay/database-multi-config/";
    private static final String CURRENT = "DB20261126-01";
    private static final String PREVIOUS = "DB20261125-01";
    private static final ReplayIssueOperator ACTOR = new ReplayIssueOperator("creator", "创建人");
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueDao dao;
    private ReplayDatabaseIssueService imports;
    private ReplayDatabaseIssueReportService reports;
    private JsonNode context;
    private long versionId;

    @BeforeEach
    void setup() throws Exception {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(List.of(
                "V62__dii_replay_database_comparison_fields.sql", "V63__dii_replay_database_comparison_versions.sql",
                "V70__replay_db_compare_scope.sql", "V91__replay_db_compare_custom_sql.sql",
                "V77__replay_database_issues.sql", "V78__replay_database_issue_review_projection.sql",
                "V79__replay_database_issue_repair_date_projection.sql", "V80__replay_database_issue_collaborator_projection.sql",
                "V81__replay_database_issue_completion_totals.sql", "V82__replay_database_table_person.sql",
                "V83__replay_database_issue_plan_date.sql", "V89__replay_database_issue_registration_version.sql").stream()
                .map(name -> new ClassPathResource("db/daoindex/" + name)).toArray(ClassPathResource[]::new))
                .execute(jdbc.getDataSource());
        ReplayDbCompareMultiConfigurationFixture.install(jdbc);
        context = json.readTree(resource("version-context.json"));
        installFrozenVersion();
        jdbc.update("INSERT INTO dii_replay_database_table_person(table_name,domain_name,developer_display,imported_at) "
                + "VALUES ('a','贷款组','当前开发(creator)',CURRENT_TIMESTAMP)");
        dao = new ReplayDatabaseIssueDao(jdbc);
        imports = new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(), dao, new SysUserDao(jdbc),
                Clock.fixed(Instant.parse("2026-11-26T13:00:00Z"), ZoneId.of("Asia/Shanghai")));
        reports = new ReplayDatabaseIssueReportService(new ReplayDatabaseIssueReportDao(jdbc));
    }

    @Test
    void actualBdWorkbookImportsIndependentCodesAndReportsFourFieldsTwoIdenticalAndCodeScopedCarryover() throws Exception {
        byte[] bytes = resource("actual-bd-export.xlsx");
        assertEquals(context.path("sha256").path(context.path("excel").asText()).asText(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var file = new MockMultipartFile("file", "BD-Axon-actual.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        var parsed = new ReplayDatabaseIssueExcelParser().parse(file, CURRENT);
        assertEquals(Set.of("a", "a_1"), parsed.tables());
        assertEquals(Set.of("a", "a_1", "a-amount", "a_1-amount"),
                parsed.issues().stream().map(ReplayDatabaseIssueExcelParser.IssueInput::issueId).collect(Collectors.toSet()));
        assertEquals(3, parsed.issues().stream().filter(issue -> issue.issueLevel().equals("字段级"))
                .mapToLong(ReplayDatabaseIssueExcelParser.IssueInput::differenceCount).sum());

        imports.importFile(file, CURRENT, ACTOR, null, context.path("reorderedVersion").asText());
        assertEquals("20261126-200001", context.path("reorderedVersion").asText());
        assertEquals(versionId, dao.batchRegistrationVersionId(CURRENT));
        var first = dao.findByIssueId("a-amount");
        var second = dao.findByIssueId("a_1-amount");
        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals("a", first.get("sourceTableName"));
        assertEquals("a", second.get("sourceTableName"));
        assertEquals("条件一", first.get("configurationName"));
        assertEquals("条件二", second.get("configurationName"));
        assertNull(dao.findByIssueId("a0"));

        dao.batch(PREVIOUS, "controlled-prior-facts", "2026-11-25 10:00:00", ACTOR.username(), ACTOR.realName(), 2, 0, 2, 0, versionId);
        previous(first, 1);
        previous(second, 0);
        var report = reports.build(CURRENT);
        assertEquals(PREVIOUS, report.previousBatchNo());
        assertEquals(2, report.total().comparedTableCount());
        assertEquals(4L, report.total().fieldCount());
        assertEquals(2L, report.total().identicalFieldCount());
        assertEquals(2, report.total().issueCount());
        assertEquals(3L, report.total().sameKeyDifferentRowCount());
        assertEquals(4, report.total().total528());
        assertEquals(4, report.total().totalCcbs());
        assertEquals(1, report.total().previousUnrepairedIssueCount());
        assertEquals(2, report.rows().stream().filter(row -> row.domain().equals("存款组")).findFirst().orElseThrow().issueCount());
        assertEquals(0, report.rows().stream().filter(row -> row.domain().equals("贷款组")).findFirst().orElseThrow().issueCount());

        byte[] exported = new ReplayDatabaseIssueReportWorkbookWriter().write(report);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            var total = workbook.getSheet("领域汇总").getRow(report.rows().size() + 3);
            assertEquals(4, total.getCell(7).getNumericCellValue());
            assertEquals(2, total.getCell(8).getNumericCellValue());
            assertEquals(1, total.getCell(11).getNumericCellValue());
        }
        String output = System.getProperty("axon.actual.roundtrip.report.path");
        if (output != null) {
            Path target = Path.of(output);
            Files.createDirectories(target.getParent());
            Files.write(target, exported);
        }

        // Only a existed as a positive field problem before: repairing it must not make a_1 a carryover.
        jdbc.update("UPDATE dii_replay_database_issue SET defect_repair_date='2026-11-26' WHERE id=?", first.get("id"));
        assertEquals(0, reports.build(CURRENT).total().previousUnrepairedIssueCount());
        assertEquals("", dao.findByIssueId("a_1-amount").get("defectRepairDate"));
    }

    private void installFrozenVersion() throws Exception {
        jdbc.update("INSERT INTO dii_replay_db_compare_version(version_no,configuration_hash,table_count,field_count,generated_by,generated_name,generated_at) "
                + "VALUES (?, ?,3,6,'creator','创建人','2026-11-26 20:00:01')", context.path("reorderedVersion").asText(),
                context.path("sha256").path("axon-reordered.sql").asText());
        versionId = jdbc.queryForObject("SELECT id FROM dii_replay_db_compare_version WHERE version_no=?", Long.class,
                context.path("reorderedVersion").asText());
        for (JsonNode configuration : json.readTree(resource("axon-frozen-configurations.json"))) {
            long tableId = insertSnapshot(configuration.path("sourceRegistrationId").asLong(), configuration.path("tableName").asText(),
                    configuration.path("sourceTableName").asText(), configuration.path("configurationName").asText(),
                    configuration.path("domainName").asText(), configuration.path("whereSql").asText());
            for (JsonNode field : configuration.path("fields")) {
                jdbc.update("INSERT INTO dii_replay_db_compare_version_field(version_table_id,column_name,column_comment,ordinal_position,primary_key,comparison_order) "
                        + "VALUES (?,?,?,?,?,?)", tableId, field.path("columnName").asText(), field.path("columnComment").asText(),
                        field.path("ordinalPosition").asInt(), field.path("primaryKey").asBoolean(), field.path("comparisonOrder").asInt());
            }
        }
        // The actual reordered package also contains unexecuted a0; its fields must stay outside this report.
        long unused = insertSnapshot(3, "a0", "a0", "插入排序中间的配置", "存款组", "");
        for (String field : List.of("id", "amount")) {
            int position = field.equals("id") ? 1 : 2;
            jdbc.update("INSERT INTO dii_replay_db_compare_version_field(version_table_id,column_name,ordinal_position,primary_key,comparison_order) "
                    + "VALUES (?,?,?,?,?)", unused, field, position, field.equals("id"), position);
        }
    }

    private long insertSnapshot(long registration, String code, String source, String name, String domain, String scope) {
        jdbc.update("INSERT INTO dii_replay_db_compare_version_table(version_id,source_registration_id,source_registration_version,schema_name,table_name,table_comment,domain_name,registered_date,registration_type,source_table_name,configuration_name,where_sql) "
                + "VALUES (?,?,0,'base',?,'真实表',?,'2026-11-26','TABLE',?,?,?)", versionId, registration, code, domain, source, name, scope);
        return jdbc.queryForObject("SELECT id FROM dii_replay_db_compare_version_table WHERE version_id=? AND table_name=?", Long.class, versionId, code);
    }

    private void previous(Map<String, Object> issue, long difference) {
        jdbc.update("INSERT INTO dii_replay_database_issue_history(issue_id,batch_no,operation_type,operation_at,import_event,after_snapshot,incoming_snapshot) "
                + "VALUES (?,?,'导入','2026-11-25 10:00:00',1,'{}',?)", issue.get("id"), PREVIOUS,
                "{\"differenceCount\":" + difference + "}");
    }

    private byte[] resource(String name) throws Exception {
        try (var input = new ClassPathResource(FIXTURES + name).getInputStream()) {
            return input.readAllBytes();
        }
    }
}
