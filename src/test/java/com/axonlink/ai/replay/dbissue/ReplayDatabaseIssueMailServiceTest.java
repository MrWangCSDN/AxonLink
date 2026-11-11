package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.notification.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplayDatabaseIssueMailServiceTest {
    private static final ReplayIssueOperator ACTOR = new ReplayIssueOperator("editor", "编辑甲");
    private static final String HELPER = "helper@spdbdev.com";
    private static final String DEVELOPER = "developer@spdbdev.com";
    private static final String TECHNOLOGY = "technology@spdbdev.com";
    private ReplayDatabaseIssueDao issues;
    private SysUserDao users;
    private MailService smtp;
    private ReplayDatabaseIssueMailDao mailDao;
    private ReplayDatabaseIssueMailService service;
    private CapturingExecutor executor;
    private JdbcTemplate jdbc;
    private Map<String, Object> issue;

    @BeforeEach
    void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V92__replay_database_issue_mail.sql"))
                .execute(jdbc.getDataSource());
        mailDao = new ReplayDatabaseIssueMailDao(jdbc);
        issues = mock(ReplayDatabaseIssueDao.class);
        users = mock(SysUserDao.class);
        smtp = mock(MailService.class);
        executor = new CapturingExecutor();
        service = new ReplayDatabaseIssueMailService(issues, users, mailDao, smtp, executor);
        issue = issue();
        when(issues.find(1L)).thenReturn(issue);
        when(issues.technologyOwners(anyCollection())).thenReturn(List.of(personnel("technology", "developer、helper")));
        when(users.findByUsername("helper")).thenReturn(user("helper", "协同甲", HELPER, "H01"));
        when(users.findByUsername("developer")).thenReturn(user("developer", "开发甲", DEVELOPER, "D01"));
        when(users.findByUsernamesOrEmpNos(anyCollection())).thenReturn(List.of(user("technology", "科技甲", TECHNOLOGY, "T01")));
        when(smtp.configuredFrom()).thenReturn("system@spdbdev.com");
    }

    @Test
    void queuesDatabaseIssueBodyWithCollaboratorAndDeveloperToAndTechnologyCc() {
        var queued = service.requestSend(1L, null, ACTOR);
        assertEquals("SENDING", queued.status());
        assertEquals(List.of("协同人", "开发负责人", "科技负责人"), queued.recipients().stream().map(item -> item.role()).toList());
        verify(smtp, never()).sendTextSync(anyList(), anyList(), isNull(), anyString(), anyString());
        executor.runOnlyTask();

        var body = ArgumentCaptor.forClass(String.class);
        verify(smtp).sendTextSync(eq(List.of(HELPER, DEVELOPER)), eq(List.of(TECHNOLOGY)), isNull(),
                eq("issue_id 是 db-issue-1 的数据库问题协同处理"), body.capture());
        assertTrue(body.getValue().contains("发件人：编辑甲(editor)\n"));
        assertTrue(body.getValue().contains("表英文名：sample_table\n"));
        assertTrue(body.getValue().contains("表中文名：样例表\n"));
        assertTrue(body.getValue().contains("字段英文名：total_amt\n"));
        assertTrue(body.getValue().contains("开发负责人：开发甲(developer)\n"));
        assertTrue(body.getValue().contains("科技负责人：科技甲(technology)\n"));
        assertTrue(body.getValue().contains("差异笔数：3\n"));
        assertTrue(body.getValue().contains("初步问题分析：待协同分析\n"));
        assertTrue(body.getValue().contains("最终处理方案：调整处理逻辑\n"));
        assertEquals("SENT", service.status(1L, ACTOR).status());
        verify(issues, atLeastOnce()).technologyOwners(List.of(new ReplayDatabaseIssueDao.TableIdentity("sample_table", "DB20261111-01")));
    }

    @Test
    void repeatedRequestsDoNotQueueSendingOrSentRecipients() {
        service.requestSend(1L, List.of(HELPER), ACTOR);
        service.requestSend(1L, List.of(HELPER), ACTOR);
        assertEquals(1, executor.tasks.size());
        executor.runOnlyTask();
        service.requestSend(1L, List.of(HELPER), ACTOR);
        assertTrue(executor.tasks.isEmpty());
        verify(smtp, times(1)).sendTextSync(anyList(), anyList(), isNull(), anyString(), anyString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_mail", Integer.class));
    }

    @Test
    void retriesFailuresAndPersistsTheirSuccessfulResult() {
        doThrow(new IllegalStateException("SMTP disconnected"))
                .doNothing().when(smtp).sendTextSync(anyList(), anyList(), isNull(), anyString(), anyString());
        service.requestSend(1L, List.of(HELPER), ACTOR);
        executor.runOnlyTask();
        var failed = recipient(service.status(1L, ACTOR), HELPER);
        assertEquals("FAILED", failed.status());
        assertEquals("SMTP disconnected", failed.failureMessage());

        service.requestSend(1L, List.of(HELPER), ACTOR);
        executor.runOnlyTask();
        var sent = recipient(service.status(1L, ACTOR), HELPER);
        assertEquals("SENT", sent.status());
        assertNull(sent.failureMessage());
        assertNotNull(sent.sentAt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_mail", Integer.class));
    }

    @Test
    void changedBodyOrBusinessSenderMakesPreviousSuccessfulContentPending() {
        service.requestSend(1L, List.of(HELPER), ACTOR);
        executor.runOnlyTask();
        issue.put("differenceCount", 4L);
        assertEquals("PENDING", recipient(service.status(1L, ACTOR), HELPER).status());
        assertNotNull(recipient(service.status(1L, ACTOR), HELPER).sentAt());
        service.requestSend(1L, List.of(HELPER), ACTOR);
        executor.runOnlyTask();
        assertEquals("SENT", recipient(service.status(1L, ACTOR), HELPER).status());
        assertEquals("PENDING", recipient(service.status(1L, new ReplayIssueOperator("other", "编辑乙")), HELPER).status());
    }

    @Test
    void sendsCcOnlySelectionThroughANonemptyToList() {
        service.requestSend(1L, List.of(TECHNOLOGY), ACTOR);
        executor.runOnlyTask();
        verify(smtp).sendTextSync(eq(List.of(TECHNOLOGY)), eq(List.of()), isNull(), anyString(), anyString());
        assertEquals("SENT", recipient(service.status(1L, ACTOR), TECHNOLOGY).status());
    }

    @Test
    void executorRejectionBecomesRetryableFailure() {
        var rejected = new ReplayDatabaseIssueMailService(issues, users, mailDao, smtp,
                task -> { throw new RejectedExecutionException("mail queue full"); });
        var status = rejected.requestSend(1L, List.of(HELPER), ACTOR);
        assertEquals("FAILED", recipient(status, HELPER).status());
        assertTrue(recipient(status, HELPER).failureMessage().contains("mail queue full"));
        service.requestSend(1L, List.of(HELPER), ACTOR);
        executor.runOnlyTask();
        assertEquals("SENT", recipient(service.status(1L, ACTOR), HELPER).status());
    }

    @Test
    void failedClaimRollsBackRecipientsBeforeScheduling() {
        var failingDao = spy(mailDao);
        doThrow(new DataAccessResourceFailureException("claim interrupted")).when(failingDao)
                .claim(eq(1L), anyString(), eq("developer"), eq(DEVELOPER), anyString(), anyString());
        var interrupted = new ReplayDatabaseIssueMailService(issues, users, failingDao, smtp, executor);

        assertThrows(DataAccessResourceFailureException.class, () -> interrupted.requestSend(1L, null, ACTOR));

        assertEquals("UNSENT", recipient(service.status(1L, ACTOR), HELPER).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_mail", Integer.class));
        assertTrue(executor.tasks.isEmpty());
        service.requestSend(1L, List.of(HELPER), ACTOR);
        executor.runOnlyTask();
        assertEquals("SENT", recipient(service.status(1L, ACTOR), HELPER).status());
    }

    @Test
    void skipsClosedForMailStatusesAndUnknownOrEmptySelections() {
        for (String status : List.of("修复待验证", "无需处理")) {
            issue.put("issueStatus", status);
            service.requestSend(1L, null, ACTOR);
        }
        issue.put("issueStatus", "打开");
        service.requestSend(1L, List.of(), ACTOR);
        service.requestSend(1L, List.of("outside@spdbdev.com"), ACTOR);
        assertTrue(executor.tasks.isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_mail", Integer.class));
    }

    @Test
    void deduplicatesByEmailAndRetainsTheCollaboratorRole() {
        when(users.findByUsername("developer")).thenReturn(user("developer", "开发甲", HELPER.toUpperCase(), "D01"));
        when(users.findByUsernamesOrEmpNos(anyCollection())).thenReturn(List.of(user("technology", "科技甲", HELPER, "T01")));
        var status = service.requestSend(1L, null, ACTOR);
        assertEquals(1, status.recipients().size());
        assertEquals("协同人", status.recipients().get(0).role());
        executor.runOnlyTask();
        verify(smtp).sendTextSync(eq(List.of(HELPER)), eq(List.of()), isNull(), anyString(), anyString());
    }

    @Test
    void usesAccountEmailFallbackAndUniqueUsernameOrEmployeeNumberResolution() {
        when(issues.technologyOwners(anyCollection())).thenReturn(List.of(personnel("T01", "developer")));
        when(users.findByUsername("developer")).thenReturn(user("developer", "开发甲", "", "D01"));
        when(users.findByUsernamesOrEmpNos(anyCollection())).thenReturn(List.of(user("technology", "科技甲", "", "T01")));
        var status = service.status(1L, ACTOR);
        assertEquals(List.of(HELPER, "developer@spdbdev.com", "technology@spdbdev.com"), status.recipients().stream().map(item -> item.email()).toList());

        when(users.findByUsernamesOrEmpNos(anyCollection())).thenReturn(List.of(
                user("technology", "科技甲", TECHNOLOGY, "T01"), user("T01", "同号用户", "other@spdbdev.com", "X01")));
        assertEquals(List.of("协同人", "开发负责人"), service.status(1L, ACTOR).recipients().stream().map(item -> item.role()).toList());
    }

    @Test
    void missingOrAmbiguousRegistrationKeepsOnlyTheSelectedCollaborator() {
        when(issues.technologyOwners(anyCollection())).thenReturn(List.of());
        assertEquals(List.of(HELPER), service.status(1L, ACTOR).recipients().stream().map(item -> item.email()).toList());
        when(issues.technologyOwners(anyCollection())).thenReturn(List.of(personnel("technology", "developer"), personnel("other", "other")));
        assertEquals(List.of(HELPER), service.status(1L, ACTOR).recipients().stream().map(item -> item.email()).toList());
    }

    @Test
    void requiresLoginAndReturnsNotFoundForAMissingIssue() {
        assertThrows(SecurityException.class, () -> service.status(1L, null));
        assertThrows(SecurityException.class, () -> service.requestSend(1L, null, new ReplayIssueOperator(" ", "匿名")));
        when(issues.find(99L)).thenReturn(null);
        var missing = assertThrows(ResponseStatusException.class, () -> service.status(99L, ACTOR));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test
    void unconfiguredSenderIsVisibleAndDoesNotQueueMail() {
        when(smtp.configuredFrom()).thenReturn("");
        assertEquals("系统发件邮箱未配置", recipient(service.status(1L, ACTOR), HELPER).failureMessage());
        assertThrows(IllegalArgumentException.class, () -> service.requestSend(1L, null, ACTOR));
        assertTrue(executor.tasks.isEmpty());
    }

    private static com.axonlink.ai.replay.dto.ReplayIssueMailRecipientStatus recipient(ReplayIssueMailStatus status, String email) {
        return status.recipients().stream().filter(item -> item.email().equalsIgnoreCase(email)).findFirst().orElseThrow();
    }

    private static ReplayDatabaseIssueDao.TechnologyOwnerRegistration personnel(String identity, String developers) {
        return new ReplayDatabaseIssueDao.TechnologyOwnerRegistration(
                new ReplayDatabaseIssueDao.TableIdentity("sample_table", "DB20261111-01"), identity,
                "科技甲(technology)", "贷款组", "开发甲(developer)", "样例表", developers);
    }

    private static SysUser user(String username, String name, String email, String employeeNumber) {
        var user = new SysUser();
        user.setUsername(username);
        user.setRealName(name);
        user.setEmail(email);
        user.setEmpNo(employeeNumber);
        user.setStatus(1);
        return user;
    }

    private static Map<String, Object> issue() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 1L);
        row.put("issueId", "db-issue-1");
        row.put("tableName", "sample_table");
        row.put("tableComment", "过期表说明");
        row.put("domain", "过期领域");
        row.put("owner", "过期开发");
        row.put("batchNo", "DB20261111-01");
        row.put("issueLevel", "字段级");
        row.put("fieldName", "total_amt");
        row.put("differenceCount", 3L);
        row.put("total528", 9L);
        row.put("totalCcbs", 9L);
        row.put("only528Count", 0L);
        row.put("onlyCcbsCount", 0L);
        row.put("issueStatus", "打开");
        row.put("issueType", "修改代码");
        row.put("cooperationPersonUsername", "helper");
        row.put("cooperationPersonName", "协同甲");
        row.put("cooperationPerson", "协同甲(helper)");
        row.put("initialAnalysis", "待协同分析");
        row.put("finalSolution", "调整处理逻辑");
        row.put("remark", "处理备注");
        return row;
    }

    private static final class CapturingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runOnlyTask() {
            assertEquals(1, tasks.size());
            tasks.remove(0).run();
        }
    }
}
