package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueReviewPermissions;
import com.axonlink.ai.replay.dto.ReplayIssueReviewStatus;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueReviewServiceTest {

    private ReplayIssueReviewService service;
    private ReplayIssueDao issueDao;

    @Test
    void springContextSelectsTheProductionConstructor() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "username VARCHAR(50), real_name VARCHAR(50), emp_no VARCHAR(50), email VARCHAR(100), " +
                "phone VARCHAR(50), department VARCHAR(100), status INT, remark VARCHAR(255), " +
                "creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReplayIssueDao.class, () -> new ReplayIssueDao(jdbc));
            context.registerBean(SysUserDao.class, () -> new SysUserDao(jdbc));
            context.registerBean(ReplayTransactionPersonDao.class, () -> new ReplayTransactionPersonDao(jdbc));
            context.registerBean(ReplayIssueReviewProperties.class, ReplayIssueReviewProperties::new);
            context.register(ReplayIssueReviewService.class);

            context.refresh();

            assertNotNull(context.getBean(ReplayIssueReviewService.class));
        }
    }

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50), real_name VARCHAR(50), emp_no VARCHAR(50), email VARCHAR(100), phone VARCHAR(50), department VARCHAR(100), status INT, remark VARCHAR(255), creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.batchUpdate("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES (?,?,?,?)", List.of(
                new Object[] {"zhangsan", "张三", "100001", 1},
                new Object[] {"lisi", "李四", "100002", 1},
                new Object[] {"wangwu", "王五", "100003", 1},
                new Object[] {"tech", "科技负责人", "200001", 1},
                new Object[] {"inactive", "停用人员", "100004", 0}));
        jdbc.update("INSERT INTO dii_replay_transaction_person " +
                        "(domain,old_transaction_code,old_transaction_name,bank_owner,bank_owner_emp_nos,imported_at) " +
                        "VALUES (?,?,?,?,?,?)",
                "公共组", "6208", "测试交易", "科技负责人、张三", "200001、100001",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 8, 21, 8, 0)));

        ReplayIssueReviewProperties properties = new ReplayIssueReviewProperties();
        properties.setReviewers(reviewers(
                Map.entry("公共组", List.of("100002", "100001", "999999")),
                Map.entry("存款组", List.of("100003")),
                Map.entry("贷款组", List.of("100004"))));
        issueDao = new ReplayIssueDao(jdbc);
        issueDao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "review")),
                java.time.LocalDateTime.of(2026, 8, 21, 9, 0));
        service = new ReplayIssueReviewService(issueDao, new SysUserDao(jdbc),
                new ReplayTransactionPersonDao(jdbc), properties,
                Clock.fixed(Instant.parse("2026-08-21T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void authorizesActiveEmployeeNumberOnlyForConfiguredGroup() {
        ReplayIssueOperator operator = new ReplayIssueOperator("zhangsan", "张三");

        assertTrue(service.isReviewer("公共组", operator));
        assertFalse(service.isReviewer("存款组", operator));
        assertFalse(service.isReviewer("不存在组", operator));
        assertFalse(service.isReviewer("公共组", new ReplayIssueOperator("unknown", "未知")));
        assertFalse(service.isReviewer("贷款组", new ReplayIssueOperator("inactive", "停用人员")));
    }

    @Test
    void permissionsKeepConfiguredGroupAndReviewerOrderAndSkipInvalidUsers() {
        ReplayIssueReviewPermissions permissions = service.permissions(new ReplayIssueOperator("zhangsan", "张三"));

        assertEquals(List.of("公共组"), permissions.reviewableGroups());
        assertEquals(List.of("李四", "张三"), permissions.reviewersByGroup().get("公共组"));
        assertEquals(List.of("王五"), permissions.reviewersByGroup().get("存款组"));
        assertEquals(List.of(), permissions.reviewersByGroup().get("贷款组"));
        assertEquals(List.of("6208"), service.permissions(
                new ReplayIssueOperator("tech", "科技负责人")).reviewableTransactionCodes());
    }

    @Test
    void technologyOwnerCanApproveOnlyItsOwnTransaction() {
        long issueId = issueDao.findCurrentByIssueKeyForUpdate("key-1").id();
        issueDao.jdbc().update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异',review_status='PENDING' WHERE id=?", issueId);

        var approved = service.approve(issueId, new ReplayIssueOperator("tech", "科技负责人"));

        assertEquals(ReplayIssueReviewStatus.APPROVED, approved.reviewStatus());
        assertEquals("tech", approved.reviewerUsername());
    }

    @Test
    void forbiddenMessageMergesTechnologyOwnersAndGroupReviewersByRealNameWithoutDuplicates() {
        long issueId = issueDao.findCurrentByIssueKeyForUpdate("key-1").id();
        issueDao.jdbc().update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异',review_status='PENDING' WHERE id=?", issueId);

        ReplayIssueReviewForbiddenException error = org.junit.jupiter.api.Assertions.assertThrows(
                ReplayIssueReviewForbiddenException.class,
                () -> service.approve(issueId, new ReplayIssueOperator("wangwu", "王五")));

        assertEquals("没有权限，请联系科技负责人、张三、李四进行审核", error.getMessage());
    }

    @Test
    void configuredReviewerApprovesPendingNoActionAndWritesAudit() throws Exception {
        long issueId = issueDao.findCurrentByIssueKeyForUpdate("key-1").id();
        issueDao.jdbc().update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异',review_status='PENDING' WHERE id=?", issueId);

        var approved = service.approve(issueId, new ReplayIssueOperator("zhangsan", "张三"));

        assertEquals(ReplayIssueStatus.NO_ACTION, approved.issueStatus());
        assertEquals(ReplayIssueReviewStatus.APPROVED, approved.reviewStatus());
        assertEquals("zhangsan", approved.reviewerUsername());
        assertEquals(LocalDate.of(2026, 8, 21), approved.defectRepairDate());
        assertEquals(1L, issueDao.countHistory("key-1"));
        var history = issueDao.findHistoryByIssueId(issueId, 10).get(0);
        assertEquals("审核通过", history.operationType());
        var snapshot = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .readValue(history.afterSnapshot(), com.axonlink.ai.replay.dto.ReplayIssueRow.class);
        assertEquals(LocalDate.of(2026, 8, 21), snapshot.defectRepairDate());
        assertEquals(ReplayIssueReviewStatus.APPROVED, snapshot.reviewStatus());
    }

    @Test
    void approvingAnAlreadyApprovedNoActionIssueIsIdempotent() {
        long issueId = issueDao.findCurrentByIssueKeyForUpdate("key-1").id();
        issueDao.jdbc().update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异'," +
                        "review_status='APPROVED',reviewer_username='lisi',reviewer_real_name='李四'," +
                        "reviewed_at='2026-08-20 09:30:00',defect_repair_date='2026-08-20' WHERE id=?", issueId);

        var approved = service.approve(issueId, new ReplayIssueOperator("zhangsan", "张三"));

        assertEquals(ReplayIssueReviewStatus.APPROVED, approved.reviewStatus());
        assertEquals("lisi", approved.reviewerUsername());
        assertEquals(java.time.LocalDateTime.of(2026, 8, 20, 9, 30), approved.reviewedAt());
        assertEquals(LocalDate.of(2026, 8, 20), approved.defectRepairDate());
        assertEquals(0L, issueDao.countHistory("key-1"));
    }

    @Test
    void rejectsCrossGroupApproval() {
        long issueId = issueDao.findCurrentByIssueKeyForUpdate("key-1").id();
        issueDao.jdbc().update("UPDATE dii_replay_issue SET issue_status='无需处理',review_status='PENDING' WHERE id=?", issueId);

        ReplayIssueReviewForbiddenException error = org.junit.jupiter.api.Assertions.assertThrows(
                ReplayIssueReviewForbiddenException.class,
                () -> service.approve(issueId, new ReplayIssueOperator("wangwu", "王五")));

        assertEquals("没有权限，请联系科技负责人、张三、李四进行审核", error.getMessage());
    }

    @SafeVarargs
    private static Map<String, ReplayIssueReviewProperties.ReviewerGroup> reviewers(
            Map.Entry<String, List<String>>... entries) {
        Map<String, ReplayIssueReviewProperties.ReviewerGroup> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : entries) {
            ReplayIssueReviewProperties.ReviewerGroup group = new ReplayIssueReviewProperties.ReviewerGroup();
            group.setEmpNos(entry.getValue());
            result.put(entry.getKey(), group);
        }
        return result;
    }
}
