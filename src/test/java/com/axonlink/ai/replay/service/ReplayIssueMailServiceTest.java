package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import com.axonlink.ai.replay.dto.ReplayTransactionPersonRow;
import com.axonlink.ai.replay.persistence.ReplayIssueMailDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.notification.service.MailService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ReplayIssueMailServiceTest {

    @Test
    void bodyIncludesLoggedInSenderAndDynamicTransactionOwners() {
        ReplayIssueMailDao mailDao = mock(ReplayIssueMailDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        ReplayTransactionPersonDao transactionPersonDao = mock(ReplayTransactionPersonDao.class);
        MailService mailService = mock(MailService.class);
        ReplayIssueMailService service = new ReplayIssueMailService(mailDao, userDao, transactionPersonDao, mailService);
        when(transactionPersonDao.findByTransactionCode("6208")).thenReturn(people("开发甲、开发乙", "科技甲"));

        String body = service.body(openIssue(), new ReplayIssueOperator("zhangs3", "张三"));

        assertTrue(body.contains("发件人：张三(zhangs3)\n"));
        assertTrue(body.contains("交易负责人：开发甲、开发乙\n"));
        assertTrue(body.contains("科技负责人：科技甲\n"));
    }

    @Test
    void bodyKeepsBlankActorAndOwnerValuesEmpty() {
        ReplayIssueMailService service = new ReplayIssueMailService(
                mock(ReplayIssueMailDao.class), mock(SysUserDao.class), mock(ReplayTransactionPersonDao.class), mock(MailService.class));

        String body = service.body(openIssue(), new ReplayIssueOperator(null, null));

        assertTrue(body.contains("发件人：\n"));
        assertTrue(body.contains("交易负责人：\n"));
        assertTrue(body.contains("科技负责人：\n"));
    }

    @Test
    void contentHashChangesWhenSenderOrDynamicOwnersChange() {
        ReplayIssueMailDao mailDao = mock(ReplayIssueMailDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        ReplayTransactionPersonDao transactionPersonDao = mock(ReplayTransactionPersonDao.class);
        ReplayIssueMailService service = new ReplayIssueMailService(mailDao, userDao, transactionPersonDao, mock(MailService.class));
        when(transactionPersonDao.findByTransactionCode("6208"))
                .thenReturn(people("开发甲", "科技甲"))
                .thenReturn(people("开发乙", "科技甲"))
                .thenReturn(people("开发乙", "科技甲"));

        String first = service.contentHash(openIssue(), new ReplayIssueOperator("zhangs3", "张三"));
        String ownerChanged = service.contentHash(openIssue(), new ReplayIssueOperator("zhangs3", "张三"));
        String senderChanged = service.contentHash(openIssue(), new ReplayIssueOperator("lisi", "李四"));

        assertNotEquals(first, ownerChanged);
        assertNotEquals(ownerChanged, senderChanged);
    }

    @Test
    void submitsSmtpWorkToInjectedManagedExecutor() {
        ReplayIssueMailDao mailDao = mock(ReplayIssueMailDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        ReplayTransactionPersonDao transactionPersonDao = mock(ReplayTransactionPersonDao.class);
        MailService mailService = mock(MailService.class);
        CapturingExecutor executor = new CapturingExecutor();
        ReplayIssueMailService service = new ReplayIssueMailService(
                mailDao, userDao, transactionPersonDao, mailService, executor);

        SysUser collaborator = new SysUser();
        collaborator.setUsername("c-chenjw3");
        collaborator.setRealName("陈经理");
        collaborator.setEmail("c-chenjw3@spdbdev.com");
        when(userDao.findByUsername("c-chenjw3")).thenReturn(collaborator);
        when(transactionPersonDao.findByTransactionCode("6208")).thenReturn(null);
        when(mailService.configuredFrom()).thenReturn("system@spdbdev.com");
        when(mailDao.findStatusForRecipient(eq(1L), eq("c-chenjw3@spdbdev.com"),
                eq("system@spdbdev.com"), anyString()))
                .thenReturn(new ReplayIssueMailStatus("UNSENT", null, "c-chenjw3@spdbdev.com", null))
                .thenReturn(new ReplayIssueMailStatus("SENDING", null, "c-chenjw3@spdbdev.com", null));

        ReplayIssueOperator operator = new ReplayIssueOperator("zhangs3", "张三");
        service.requestSend(openIssue(), List.of("c-chenjw3@spdbdev.com"), operator);

        verify(mailDao).insertPending(eq(1L), eq("key-1"), eq("c-chenjw3"),
                eq("c-chenjw3@spdbdev.com"), eq("system@spdbdev.com"), anyString());
        assertEquals(1, executor.tasks.size());
        verify(mailService, never()).sendTextSync(
                List.of("c-chenjw3@spdbdev.com"), List.of(), null,
                "issue_id 是 issue-1 的问题协同处理", service.body(openIssue(), operator));

        executor.runOnlyTask();

        verify(mailService).sendTextSync(
                List.of("c-chenjw3@spdbdev.com"), List.of(), null,
                "issue_id 是 issue-1 的问题协同处理", service.body(openIssue(), operator));
        verify(mailDao).markSent(eq(1L), eq("c-chenjw3@spdbdev.com"),
                eq("system@spdbdev.com"), anyString());
    }

    @Test
    void keepsFailedStatusPersistenceOnManagedExecutor() {
        ReplayIssueMailDao mailDao = mock(ReplayIssueMailDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        ReplayTransactionPersonDao transactionPersonDao = mock(ReplayTransactionPersonDao.class);
        MailService mailService = mock(MailService.class);
        CapturingExecutor executor = new CapturingExecutor();
        ReplayIssueMailService service = new ReplayIssueMailService(
                mailDao, userDao, transactionPersonDao, mailService, executor);

        SysUser collaborator = new SysUser();
        collaborator.setUsername("c-chenjw3");
        collaborator.setRealName("陈经理");
        collaborator.setEmail("c-chenjw3@spdbdev.com");
        when(userDao.findByUsername("c-chenjw3")).thenReturn(collaborator);
        when(transactionPersonDao.findByTransactionCode("6208")).thenReturn(null);
        when(mailService.configuredFrom()).thenReturn("system@spdbdev.com");
        when(mailDao.findStatusForRecipient(eq(1L), eq("c-chenjw3@spdbdev.com"),
                eq("system@spdbdev.com"), anyString()))
                .thenReturn(new ReplayIssueMailStatus("UNSENT", null, "c-chenjw3@spdbdev.com", null))
                .thenReturn(new ReplayIssueMailStatus("SENDING", null, "c-chenjw3@spdbdev.com", null));
        doThrow(new IllegalStateException("SMTP 连接失败")).when(mailService).sendTextSync(
                eq(List.of("c-chenjw3@spdbdev.com")), eq(List.of()), eq(null),
                eq("issue_id 是 issue-1 的问题协同处理"), anyString());

        service.requestSend(openIssue(), List.of("c-chenjw3@spdbdev.com"), new ReplayIssueOperator("zhangs3", "张三"));
        executor.runOnlyTask();

        verify(mailDao).markFailed(eq(1L), eq("c-chenjw3@spdbdev.com"),
                eq("system@spdbdev.com"), anyString(), eq("SMTP 连接失败"));
    }

    private ReplayIssueRow openIssue() {
        ReplayIssueRow row = ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "返回码不一致");
        return new ReplayIssueRow(1L, row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(),
                row.domain(), row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(),
                row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(),
                row.transactionOwner(), row.issueType(), row.initialAnalysis(), row.finalSolution(),
                row.resolvedDate(), row.cooperationGroup(), row.resolver(), row.serialNo(),
                row.dataRepairDate(), row.remark(), row.affectedTransactionCount(), row.issueId(), row.issueKey(),
                row.historicalOccurrenceCount(), row.firstOccurrenceDate(), row.lastOccurrenceDate(),
                row.importedAt(), ReplayIssueStatus.OPEN, row.importDate(), row.defectRepairDate(),
                "c-chenjw3", "陈经理", row.globalSerialNo());
    }

    private ReplayTransactionPersonRow people(String developer, String bankOwner) {
        return new ReplayTransactionPersonRow(1L, "公共组", "6208", "测试交易", developer,
                "dev1、dev2", bankOwner, "200001", null);
    }

    private static final class CapturingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runOnlyTask() {
            assertEquals(1, tasks.size());
            tasks.remove(0).run();
        }
    }
}
