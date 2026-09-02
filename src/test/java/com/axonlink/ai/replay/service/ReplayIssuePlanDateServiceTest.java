package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssuePlanDatePermissions;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.dto.ReplayTransactionPersonRow;
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
                new Object[]{"developer-owner", "开发负责人", "300001", 1},
                new Object[]{"advanced-editor", "高级编辑人", "400001", 1},
                new Object[]{"inactive", "停用人员", "100002", 0}));

        ReplayIssuePlanDateProperties properties = new ReplayIssuePlanDateProperties();
        LinkedHashMap<String, ReplayIssuePlanDateProperties.EditorGroup> editors = new LinkedHashMap<>();
        editors.put("公共组", editorGroup("100001", "100002", "username-editor"));
        editors.put("贷款组", editorGroup("200001", "public-editor"));
        properties.setEditors(editors);
        properties.setAdvancedEditors(new LinkedHashMap<>(
                java.util.Map.of("公共组", editorGroup("400001", "100001"))));

        issueDao = new ReplayIssueDao(jdbc);
        issueDao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", true, 1, "6208", "公共沙箱问题"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "6209", "贷款问题")),
                java.time.LocalDateTime.of(2026, 8, 26, 9, 0));
        publicIssueId = issueDao.findCurrentByIssueKeyForUpdate("key-1").id();
        loanIssueId = issueDao.findCurrentByIssueKeyForUpdate("key-2").id();
        jdbc.update("UPDATE dii_replay_issue SET first_occurrence_date='2026-08-20'");
        ReplayTransactionPersonDao personDao = new ReplayTransactionPersonDao(jdbc);
        java.time.LocalDateTime importedAt = java.time.LocalDateTime.of(2026, 8, 26, 9, 0);
        personDao.replaceAll(List.of(
                new ReplayTransactionPersonRow(null, "公共组", "6208", "交易6208", "开发负责人",
                        "developer-owner、inactive", null, null, importedAt),
                new ReplayTransactionPersonRow(null, "贷款组", "6209", "交易6209", "无工号编辑人",
                        "username-editor", null, null, importedAt)), importedAt);
        service = new ReplayIssuePlanDateService(issueDao, new SysUserDao(jdbc), personDao, properties,
                Clock.fixed(Instant.parse("2026-08-26T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void permissionsAreBasedOnActiveEmployeeNumberAndGroup() {
        ReplayIssuePlanDatePermissions permissions = service.permissions(
                new ReplayIssueOperator("public-editor", "公共编辑人"));

        assertEquals(List.of("公共组"), permissions.editableGroups());
        assertEquals(List.of("公共组"), permissions.dateLimitBypassGroups());
        assertEquals(List.of(), permissions.editableTransactionCodes());
        assertThrows(ReplayIssuePlanDateForbiddenException.class, () -> service.update(loanIssueId,
                "2026-08-26", new ReplayIssueOperator("public-editor", "公共编辑人")));
        assertThrows(ReplayIssuePlanDateForbiddenException.class, () -> service.update(publicIssueId,
                "2026-08-26", new ReplayIssueOperator("inactive", "停用人员")));
    }

    @Test
    void advancedEditorAutomaticallyGetsGroupEditPermissionAndBypassesOccurrenceBoundary() {
        ReplayIssueOperator advanced = new ReplayIssueOperator("advanced-editor", "高级编辑人");

        ReplayIssuePlanDatePermissions permissions = service.permissions(advanced);
        assertEquals(List.of("公共组"), permissions.editableGroups());
        assertEquals(List.of("公共组"), permissions.dateLimitBypassGroups());
        assertEquals(List.of(), permissions.editableTransactionCodes());

        assertEquals(LocalDate.of(2026, 9, 30),
                service.update(publicIssueId, "2026-09-30", advanced).plannedCompletionDate());
        jdbc.update("UPDATE dii_replay_issue SET first_occurrence_date=NULL WHERE id=?", publicIssueId);
        assertEquals(LocalDate.of(2026, 10, 1),
                service.update(publicIssueId, "2026-10-01", advanced).plannedCompletionDate());
    }

    @Test
    void ordinaryEditorAndDeveloperOwnerStillCannotBypassSevenDayBoundary() {
        ReplayIssueOperator ordinary = new ReplayIssueOperator("loan-editor", "贷款编辑人");
        assertEquals(List.of(), service.permissions(ordinary).dateLimitBypassGroups());
        IllegalArgumentException ordinaryError = assertThrows(IllegalArgumentException.class,
                () -> service.update(loanIssueId, "2026-08-28", ordinary));
        assertEquals("计划验证日期不能超过首次出现日期后 7 个自然日", ordinaryError.getMessage());

        ReplayIssueOperator developer = new ReplayIssueOperator("developer-owner", "开发负责人");
        IllegalArgumentException developerError = assertThrows(IllegalArgumentException.class,
                () -> service.update(publicIssueId, "2026-08-28", developer));
        assertEquals("计划验证日期不能超过首次出现日期后 7 个自然日", developerError.getMessage());
    }

    @Test
    void enabledBankEmployeeDeveloperCanEditOnlyOwnedTransactions() {
        ReplayIssueOperator owner = new ReplayIssueOperator("developer-owner", "开发负责人");

        ReplayIssuePlanDatePermissions permissions = service.permissions(owner);
        assertEquals(List.of(), permissions.editableGroups());
        assertEquals(List.of("6208"), permissions.editableTransactionCodes());
        assertEquals(LocalDate.of(2026, 8, 26),
                service.update(publicIssueId, "2026-08-26", owner).plannedCompletionDate());
        assertThrows(ReplayIssuePlanDateForbiddenException.class,
                () -> service.update(loanIssueId, "2026-08-26", owner));

        ReplayIssueOperator noEmpNo = new ReplayIssueOperator("username-editor", "无工号编辑人");
        assertEquals(List.of(), service.permissions(noEmpNo).editableTransactionCodes());
        assertEquals(List.of(), service.permissions(new ReplayIssueOperator("inactive", "停用人员"))
                .editableTransactionCodes());
    }

    @Test
    void fallsBackToUsernameOnlyWhenActiveUserHasNoEmployeeNumber() {
        ReplayIssueOperator usernameOnly = new ReplayIssueOperator("username-editor", "无工号编辑人");

        assertEquals(List.of("公共组"), service.permissions(usernameOnly).editableGroups());
        var updated = service.update(publicIssueId, "2026-08-26", usernameOnly);
        assertEquals(LocalDate.of(2026, 8, 26), updated.plannedCompletionDate());

        ReplayIssueOperator employee = new ReplayIssueOperator("public-editor", "公共编辑人");
        assertEquals(List.of("公共组"), service.permissions(employee).editableGroups());
        assertThrows(ReplayIssuePlanDateForbiddenException.class,
                () -> service.update(loanIssueId, "2026-08-26", employee));
    }

    @Test
    void acceptsRealIsoDateAndSandboxUsesItsDomainPermission() {
        var updated = service.update(publicIssueId, "2026-08-26",
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

        var blank = service.update(publicIssueId, "  ", operator);
        assertNull(blank.plannedCompletionDate());
        assertEquals(0L, issueDao.countHistory("key-1"));

        service.update(publicIssueId, "2026-08-26", operator);
        var unchanged = service.update(publicIssueId, "2026-08-26", operator);
        assertEquals(LocalDate.of(2026, 8, 26), unchanged.plannedCompletionDate());
        assertEquals(1L, issueDao.countHistory("key-1"));

        var cleared = service.update(publicIssueId, null, operator);
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
        for (ReplayIssueOperator authorizedEditor : List.of(
                new ReplayIssueOperator("public-editor", "公共编辑人"),
                new ReplayIssueOperator("advanced-editor", "高级编辑人"))) {
            for (String value : new String[]{"2026-08-27", null}) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> service.update(publicIssueId, value, authorizedEditor));
                assertEquals("问题已有缺陷修复日期，计划验证日期不可修改", error.getMessage());
            }
        }

        var unchanged = issueDao.findCurrentByIdForUpdate(publicIssueId);
        assertEquals(LocalDate.of(2026, 8, 25), unchanged.plannedCompletionDate());
        assertEquals(0L, issueDao.countHistory("key-1"));
    }

    @Test
    void plannedDateAllowsExactlySevenNaturalDaysAndRejectsTheEighthDay() {
        ReplayIssueOperator operator = new ReplayIssueOperator("loan-editor", "贷款编辑人");

        assertEquals(LocalDate.of(2026, 8, 27),
                service.update(loanIssueId, "2026-08-27", operator).plannedCompletionDate());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update(loanIssueId, "2026-08-28", operator));
        assertEquals("计划验证日期不能超过首次出现日期后 7 个自然日", error.getMessage());
    }

    @Test
    void nonEmptyPlanDateRequiresAValidFirstOccurrenceDateButCanStillBeCleared() {
        ReplayIssueOperator operator = new ReplayIssueOperator("loan-editor", "贷款编辑人");
        jdbc.update("UPDATE dii_replay_issue SET first_occurrence_date='not-a-date' WHERE id=?", loanIssueId);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update(loanIssueId, "2026-08-26", operator));
        assertEquals("首次出现日期无效，无法填写计划验证日期", error.getMessage());

        jdbc.update("UPDATE dii_replay_issue SET planned_completion_date='2026-08-26' WHERE id=?", loanIssueId);
        assertNull(service.update(loanIssueId, null, operator).plannedCompletionDate());
    }

    @Test
    void eachRealChangeAddsOneResultingValueHistoryAndSameValueIsIdempotent() {
        ReplayIssueOperator operator = new ReplayIssueOperator("public-editor", "公共编辑人");

        assertEquals(1L, service.update(publicIssueId, "2026-08-25", operator).changeCount());
        assertEquals(2L, service.update(publicIssueId, "2026-08-26", operator).changeCount());
        assertEquals(3L, service.update(publicIssueId, null, operator).changeCount());
        assertEquals(4L, service.update(publicIssueId, "2026-08-27", operator).changeCount());
        assertEquals(4L, service.update(publicIssueId, "2026-08-27", operator).changeCount());

        var changes = service.changes(publicIssueId);
        assertEquals(4L, changes.changeCount());
        assertEquals(4, changes.items().size());
        assertEquals(LocalDate.of(2026, 8, 27), changes.items().get(0).plannedCompletionDate());
        assertNull(changes.items().get(1).plannedCompletionDate());
        assertEquals(LocalDate.of(2026, 8, 25), changes.items().get(3).plannedCompletionDate());
        assertEquals(4L, issueDao.countHistory("key-1"));
    }

    private static ReplayIssuePlanDateProperties.EditorGroup editorGroup(String... empNos) {
        ReplayIssuePlanDateProperties.EditorGroup group = new ReplayIssuePlanDateProperties.EditorGroup();
        group.setEmpNos(List.of(empNos));
        return group;
    }
}
