package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayDatabaseIssueTypeRulesTest {
    private ReplayDatabaseIssueService service;
    private ReplayDatabaseIssueDao dao;
    private final ReplayIssueOperator actor = new ReplayIssueOperator("editor", "编辑人");

    @BeforeEach
    void setup() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"),
                new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),
                new ClassPathResource("db/daoindex/V78__replay_database_issue_review_projection.sql"),
                new ClassPathResource("db/daoindex/V79__replay_database_issue_repair_date_projection.sql"),
                new ClassPathResource("db/daoindex/V80__replay_database_issue_collaborator_projection.sql"),
                new ClassPathResource("db/daoindex/V81__replay_database_issue_completion_totals.sql"),
                new ClassPathResource("db/daoindex/V83__replay_database_issue_plan_date.sql"),
                new ClassPathResource("db/user/01_user_ddl.sql")).execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('editor','编辑人','E001',1)");
        dao = new ReplayDatabaseIssueDao(jdbc);
        service = new ReplayDatabaseIssueService(new ReplayDatabaseIssueExcelParser(), dao, new SysUserDao(jdbc),
                Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("issueId", "type-rule"); row.put("tableName", "type_table"); row.put("tableComment", "");
        row.put("domain", ""); row.put("owner", ""); row.put("issueLevel", "字段级"); row.put("fieldName", "amount");
        row.put("differenceCount", 1L); row.put("total528", 0L); row.put("totalCcbs", 0L);
        row.put("batchNo", "DB20261012-01"); row.put("issueStatus", "打开"); row.put("issueType", "修改代码");
        row.put("initialAnalysis", ""); row.put("finalSolution", ""); row.put("remark", ""); row.put("cooperationPerson", "");
        row.put("reviewStatus", ""); row.put("reviewReason", ""); row.put("defectRepairDate", ""); row.put("plannedCompletionDate", "");
        row.put("importedAt", "2026-10-12 10:00:00"); row.put("lastManualSaveAt", ""); row.put("firstOccurredDate", "2026-10-12"); row.put("lastOccurredDate", "2026-10-12");
        dao.insert(row);
    }

    @Test
    void exposesSevenTypesAndEnforcesStatusSpecificChoices() {
        assertEquals(ReplayDatabaseIssueTypes.ALL, service.options(actor).get("issueTypes"));
        long id = ((Number) dao.findByIssueId("type-rule").get("id")).longValue();
        var updated = service.update(id, Map.of("version", 0, "issueStatus", "无需处理", "issueType", "规则性差异"), actor);
        assertEquals("规则性差异", updated.get("issueType"));
        long version = ((Number) updated.get("version")).longValue();
        assertThrows(IllegalArgumentException.class, () -> service.update(id,
                Map.of("version", version, "issueStatus", "打开", "issueType", "不参与比对"), actor));
    }

    @ParameterizedTest
    @ValueSource(strings = {"不参与比对", "规则性差异"})
    void savesEitherNoActionTypeWithoutChangingTheSelectedType(String type) {
        long id = ((Number) dao.findByIssueId("type-rule").get("id")).longValue();

        var updated = service.update(id,
                Map.of("version", 0, "issueStatus", "无需处理", "issueType", type), actor);

        assertEquals("无需处理", updated.get("issueStatus"));
        assertEquals(type, updated.get("issueType"));
        assertEquals("待审核", updated.get("reviewStatus"));
        assertEquals(type, dao.find(id).get("issueType"));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"修改比对规则", "修改迁移规则", "修改代码", "前后交易差异", "其他问题",
            "合理差异", "规则性差异问题", "外围问题"})
    void rejectsInvalidNoActionTypeWithTheTwoAllowedChoices(String type) {
        var before = dao.findByIssueId("type-rule");
        long id = ((Number) before.get("id")).longValue();

        var failure = assertThrows(IllegalArgumentException.class, () -> service.update(id,
                Map.of("version", 0, "issueStatus", "无需处理", "issueType", type), actor));

        assertEquals("无需处理的问题类型只能选择：不参与比对、规则性差异", failure.getMessage());
        assertEquals(before.get("version"), dao.find(id).get("version"));
        assertEquals("打开", dao.find(id).get("issueStatus"));
        assertEquals("修改代码", dao.find(id).get("issueType"));
        assertEquals(0, dao.history(id).size());
    }
}
