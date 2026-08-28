package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssuePlanDatePermissions;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssuePlanDateServiceTest {
    private static final String DATE_ERROR = "填写日期格式不合法，请按 2026-08-26 格式填写";

    private ReplayIssueDao issueDao;
    private ReplayIssuePlanDateService service;
    private JdbcTemplate jdbc;
    private long publicIssueId;
    private long loanIssueId;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "username VARCHAR(50), real_name VARCHAR(50), emp_no VARCHAR(50), email VARCHAR(100), "
                + "phone VARCHAR(50), department VARCHAR(100), status INT, remark VARCHAR(255), "
                + "creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.batchUpdate("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES (?,?,?,?)", List.of(
                new Object[]{"public-editor", "公共编辑人", "100001", 1},
                new Object[]{"loan-editor", "贷款编辑人", "200001", 1},
                new Object[]{"username-editor", "无工号编辑人", null, 1},
                new Object[]{"inactive", "停用人员", "100002", 0}));

        ReplayIssuePlanDateProperties properties = new ReplayIssuePlanDateProperties();
        LinkedHashMap<String, ReplayIssuePlanDateProperties.EditorGroup> editors = new LinkedHashMap<>();
        editors.put("公共组", editorGroup("100001", "100002", "username-editor"));
        editors.put("贷款组", editorGroup("200001", "public-editor"));
        properties.setEditors(editors);

        issueDao = new ReplayIssueDao(jdbc);
        issueDao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", true, 1, "6208", "公共沙箱问题"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "6209", "贷款问题")),
                java.time.LocalDateTime.of(2026, 8, 26, 9, 0));
        publicIssueId = issueDao.findCurrentByIssueKeyForUpdate("key-1").id();
        loanIssueId = issueDao.findCurrentByIssueKeyForUpdate("key-2").id();
        service = new ReplayIssuePlanDateService(issueDao, new SysUserDao(jdbc), properties,
                Clock.fixed(Instant.parse("2026-08-26T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void permissionsAreBasedOnActiveEmployeeNumberAndGroup() {
        ReplayIssuePlanDatePermissions permissions = service.permissions(
                new ReplayIssueOperator("public-editor", "公共编辑人"));

        assertEquals(List.of("公共组"), permissions.editableGroups());
        assertThrows(ReplayIssuePlanDateForbiddenException.class, () -> service.update(loanIssueId,
                "2026-08-26", new ReplayIssueOperator("public-editor", "公共编辑人")));
        assertThrows(ReplayIssuePlanDateForbiddenException.class, () -> service.update(publicIssueId,
                "2026-08-26", new ReplayIssueOperator("inactive", "停用人员")));
    }

    @Test
    void fallsBackToUsernameOnlyWhenActiveUserHasNoEmployeeNumber() {
        ReplayIssueOperator usernameOnly = new ReplayIssueOperator("username-editor", "无工号编辑人");

        assertEquals(List.of("公共组"), service.permissions(usernameOnly).editableGroups());
        ReplayIssueRow updated = service.update(publicIssueId, "2026-08-26", usernameOnly);
        assertEquals(LocalDate.of(2026, 8, 26), updated.plannedCompletionDate());

        ReplayIssueOperator employee = new ReplayIssueOperator("public-editor", "公共编辑人");
        assertEquals(List.of("公共组"), service.permissions(employee).editableGroups());
        assertThrows(ReplayIssuePlanDateForbiddenException.class,
                () -> service.update(loanIssueId, "2026-08-26", employee));
    }

    @Test
    void acceptsRealIsoDateAndSandboxUsesItsDomainPermission() {
        ReplayIssueRow updated = service.update(publicIssueId, "2026-08-26",
                new ReplayIssueOperator("public-editor", "公共编辑人"));

        assertEquals(LocalDate.of(2026, 8, 26), updated.plannedCompletionDate());
        assertEquals(1L, issueDao.countHistory("key-1"));
        var history = issueDao.findHistoryByIssueId(publicIssueId, 10).get(0);
        assertEquals("修改计划验证日期", history.operationType());
        org.junit.jupiter.api.Assertions.assertTrue(history.afterSnapshot().contains("2026-08-26"));
        org.junit.jupiter.api.Assertions.assertTrue(history.afterSnapshot().contains("public-editor") == false);
        assertEquals("public-editor", history.operatorUsername());
    }

    @Test
    void rejectsWrongFormatAndImpossibleDatesWithOneMessage() {
        ReplayIssueOperator operator = new ReplayIssueOperator("public-editor", "公共编辑人");
        for (String value : List.of("2026/08/26", "2026-08-32", "2026-02-30")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.update(publicIssueId, value, operator));
            assertEquals(DATE_ERROR, error.getMessage());
        }
        assertEquals(0L, issueDao.countHistory("key-1"));
    }

    @Test
    void unchangedAndBlankToBlankAreIdempotentButExistingDateCanBeCleared() {
        ReplayIssueOperator operator = new ReplayIssueOperator("public-editor", "公共编辑人");

        ReplayIssueRow blank = service.update(publicIssueId, "  ", operator);
        assertNull(blank.plannedCompletionDate());
        assertEquals(0L, issueDao.countHistory("key-1"));

        service.update(publicIssueId, "2026-08-26", operator);
        ReplayIssueRow unchanged = service.update(publicIssueId, "2026-08-26", operator);
        assertEquals(LocalDate.of(2026, 8, 26), unchanged.plannedCompletionDate());
        assertEquals(1L, issueDao.countHistory("key-1"));

        ReplayIssueRow cleared = service.update(publicIssueId, null, operator);
        assertNull(cleared.plannedCompletionDate());
        assertEquals(2L, issueDao.countHistory("key-1"));
        var latest = issueDao.findHistoryByIssueId(publicIssueId, 10).get(0);
        org.junit.jupiter.api.Assertions.assertTrue(latest.beforeSnapshot().contains("2026-08-26"));
        org.junit.jupiter.api.Assertions.assertTrue(latest.afterSnapshot().contains("\"plannedCompletionDate\":null"));
    }

    @Test
    void defectRepairDateLocksPlanDateRegardlessOfEditorPermission() {
        jdbc.update("UPDATE dii_replay_issue SET planned_completion_date = ?, defect_repair_date = ? WHERE id = ?",
                LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 26), publicIssueId);
        ReplayIssueOperator authorizedEditor = new ReplayIssueOperator("public-editor", "公共编辑人");

        for (String value : new String[]{"2026-08-27", null}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.update(publicIssueId, value, authorizedEditor));
            assertEquals("问题已有缺陷修复日期，计划验证日期不可修改", error.getMessage());
        }

        ReplayIssueRow unchanged = issueDao.findCurrentByIdForUpdate(publicIssueId);
        assertEquals(LocalDate.of(2026, 8, 25), unchanged.plannedCompletionDate());
        assertEquals(0L, issueDao.countHistory("key-1"));
    }

    private static ReplayIssuePlanDateProperties.EditorGroup editorGroup(String... empNos) {
        ReplayIssuePlanDateProperties.EditorGroup group = new ReplayIssuePlanDateProperties.EditorGroup();
        group.setEmpNos(List.of(empNos));
        return group;
    }
}
