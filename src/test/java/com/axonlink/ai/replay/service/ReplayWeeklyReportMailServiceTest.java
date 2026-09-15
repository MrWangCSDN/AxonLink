package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.config.ReplayDailyReportMailProperties;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailSendRequest;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailView;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportMailDao;
import com.axonlink.notification.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayWeeklyReportMailServiceTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ReplayWeeklyReportDao weeklyReportDao;
    private ReplayWeeklyReportMailDao mailDao;
    private ReplayDailyReportMailProperties properties;
    private MailService mailService;
    private ReplayWeeklyReportMailService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        weeklyReportDao = new ReplayWeeklyReportDao(jdbc);
        mailDao = new ReplayWeeklyReportMailDao(jdbc);
        properties = new ReplayDailyReportMailProperties();
        properties.setTo(List.of(" first@example.com ", "FIRST@example.com", "second@example.com"));
        properties.setCc(List.of("cc@example.com"));
        properties.setWeeklyBody("默认周报正文");
        mailService = mock(MailService.class);
        when(mailService.configuredFrom()).thenReturn("sender@example.com");
        service = new ReplayWeeklyReportMailService(weeklyReportDao, mailDao, properties, mailService,
                new ReplayReportMailAttachmentService(new com.axonlink.ai.replay.persistence.ReplayDailyDataDao(jdbc)));
    }

    @Test
    void exposesWeeklyDefaultsAndStatusUsingEndBatchDate() {
        saveSnapshot("RPT20260901-01", "RPT20260908-01", new byte[]{1, 2, 3});

        ReplayWeeklyReportMailView view = service.configuration("RPT20260901-01", "RPT20260908-01");

        assertEquals("RPT20260901-01", view.startBatchNo());
        assertEquals("RPT20260908-01", view.endBatchNo());
        assertEquals("对公分布式核心回放问题周报-20260908", view.subject());
        assertEquals(List.of("first@example.com", "second@example.com"), view.toEmails());
        assertEquals(List.of("cc@example.com"), view.ccEmails());
        assertEquals("默认周报正文", view.body());
        assertEquals("UNSENT", view.status());
    }

    @Test
    void sendsEditableMailWithMandatoryWeeklySnapshotAttachment() {
        byte[] content = new byte[]{9, 8, 7};
        saveSnapshot("DZ20260901-01", "DZ20260908-01", content);

        ReplayWeeklyReportMailView view = service.send(new ReplayWeeklyReportMailSendRequest(
                "DZ20260901-01", "DZ20260908-01", "自定义周报标题",
                List.of(" User@Example.com ", "user@example.com"),
                List.of(" Copy@Example.com "), "请查收周报"));

        verify(mailService).sendTextWithAttachmentsSync(
                org.mockito.ArgumentMatchers.eq(List.of("user@example.com")),
                org.mockito.ArgumentMatchers.eq(List.of("copy@example.com")),
                org.mockito.ArgumentMatchers.eq("自定义周报标题"),
                org.mockito.ArgumentMatchers.eq("请查收周报"),
                org.mockito.ArgumentMatchers.anyList());
        assertEquals("SENT", view.status());
        assertTrue(view.sentAt() != null);
        assertEquals("SENT", mailDao.find("DZ20260901-01", "DZ20260908-01").orElseThrow().status());
    }

    @Test
    void persistsFailedStatusWhenSmtpFails() {
        byte[] content = new byte[]{5};
        saveSnapshot("RPT20260901-01", "RPT20260910-01", content);
        doThrow(new IllegalStateException("SMTP不可用")).when(mailService).sendTextWithAttachmentsSync(
                org.mockito.ArgumentMatchers.eq(List.of("first@example.com", "second@example.com")),
                org.mockito.ArgumentMatchers.eq(List.of("cc@example.com")),
                org.mockito.ArgumentMatchers.eq("对公分布式核心回放问题周报-20260910"),
                org.mockito.ArgumentMatchers.eq("正文"), org.mockito.ArgumentMatchers.anyList());

        assertThrows(ReplayWeeklyReportMailService.MailSendException.class,
                () -> service.send(new ReplayWeeklyReportMailSendRequest(
                        "RPT20260901-01", "RPT20260910-01",
                        "对公分布式核心回放问题周报-20260910",
                        List.of("first@example.com", "second@example.com"),
                        List.of("cc@example.com"), "正文")));

        var status = mailDao.find("RPT20260901-01", "RPT20260910-01").orElseThrow();
        assertEquals("FAILED", status.status());
        assertTrue(status.failureMessage().contains("SMTP不可用"));
    }

    @Test
    void validatesRequestSnapshotBatchAndConfiguration() {
        saveSnapshot("RPT20260901-01", "RPT20260911-01", new byte[]{1});

        assertThrows(IllegalArgumentException.class, () -> service.send(request(" ", List.of("to@example.com"), "正文")));
        assertThrows(IllegalArgumentException.class, () -> service.send(request("标题", List.of(), "正文")));
        assertThrows(IllegalArgumentException.class, () -> service.send(request("标题", List.of("bad-address"), "正文")));
        assertThrows(IllegalArgumentException.class, () -> service.send(request("标题", List.of("to@example.com"), " ")));
        assertThrows(ReplayWeeklyReportMailService.MalformedBatchException.class,
                () -> service.configuration("RPT-BAD", "RPT20260911-01"));
        assertThrows(ReplayWeeklyReportMailService.SnapshotNotFoundException.class,
                () -> service.configuration("RPT20260901-01", "RPT20260912-01"));

        when(mailService.configuredFrom()).thenReturn(" ");
        assertThrows(ReplayWeeklyReportMailService.ConfigurationException.class,
                () -> service.configuration("RPT20260901-01", "RPT20260911-01"));
    }

    @Test
    void refusesToSendWhenWeeklyAttachmentIsEmpty() {
        saveSnapshot("RPT20260901-01", "RPT20260912-01", new byte[0]);

        assertThrows(ReplayWeeklyReportMailService.SnapshotNotFoundException.class,
                () -> service.send(new ReplayWeeklyReportMailSendRequest(
                        "RPT20260901-01", "RPT20260912-01", "标题",
                        List.of("to@example.com"), List.of(), "正文")));

        verify(mailService, never()).sendTextWithAttachmentsSync(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
        assertTrue(mailDao.find("RPT20260901-01", "RPT20260912-01").isEmpty());
    }

    private ReplayWeeklyReportMailSendRequest request(String subject, List<String> toEmails, String body) {
        return new ReplayWeeklyReportMailSendRequest(
                "RPT20260901-01", "RPT20260911-01", subject, toEmails, List.of(), body);
    }

    private void saveSnapshot(String startBatchNo, String endBatchNo, byte[] content) {
        weeklyReportDao.saveSnapshot(new ReplayWeeklyReportSnapshot(
                startBatchNo, endBatchNo, endBatchNo + "周报.xlsx", XLSX,
                content, content.length, LocalDateTime.of(2026, 9, 8, 9, 0)));
    }
}
