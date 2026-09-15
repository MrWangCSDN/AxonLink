package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.config.ReplayDailyReportMailProperties;
import com.axonlink.ai.replay.dto.ReplayDailyReportMailSendRequest;
import com.axonlink.ai.replay.dto.ReplayDailyReportMailView;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayDailyReportMailDao;
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

class ReplayDailyReportMailServiceTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ReplayDailyDataDao dailyDataDao;
    private ReplayDailyReportMailDao mailDao;
    private ReplayDailyReportMailProperties properties;
    private MailService mailService;
    private ReplayDailyReportMailService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dailyDataDao = new ReplayDailyDataDao(jdbc);
        mailDao = new ReplayDailyReportMailDao(jdbc);
        properties = new ReplayDailyReportMailProperties();
        properties.setTo(List.of(" first@example.com ", "FIRST@example.com", "second@example.com"));
        properties.setCc(List.of("cc@example.com"));
        properties.setBody("默认日报正文");
        mailService = mock(MailService.class);
        when(mailService.configuredFrom()).thenReturn("sender@example.com");
        service = new ReplayDailyReportMailService(dailyDataDao, mailDao, properties, mailService,
                new ReplayReportMailAttachmentService(dailyDataDao));
    }

    @Test
    void exposesFixedSubjectAndConfiguredRecipientsForCurrentSnapshot() {
        saveSnapshot("RPT20260908-01", new byte[]{1, 2, 3});

        ReplayDailyReportMailView view = service.configuration("RPT20260908-01");

        assertEquals("对公分布式核心回放问题日报-20260908", view.subject());
        assertEquals(List.of("first@example.com", "second@example.com"), view.toEmails());
        assertEquals(List.of("cc@example.com"), view.ccEmails());
        assertEquals("默认日报正文", view.body());
        assertEquals("UNSENT", view.status());
    }

    @Test
    void flattensYamlListEntriesContainingEnvironmentStyleRecipientSeparators() {
        properties.setTo(List.of("first@example.com, SECOND@example.com", "second@example.com；third@example.com"));
        properties.setCc(List.of("cc-one@example.com\ncc-two@example.com", "CC-ONE@example.com"));
        saveSnapshot("RPT20260908-02", new byte[]{1});

        ReplayDailyReportMailView view = service.configuration("RPT20260908-02");

        assertEquals(List.of("first@example.com", "second@example.com", "third@example.com"), view.toEmails());
        assertEquals(List.of("cc-one@example.com", "cc-two@example.com"), view.ccEmails());
    }

    @Test
    void sendsCurrentSnapshotAsAttachmentAndPersistsSentStatus() {
        byte[] content = new byte[]{9, 8, 7};
        saveSnapshot("DZ20260909-02", content);

        ReplayDailyReportMailView view = service.send(new ReplayDailyReportMailSendRequest(
                "DZ20260909-02", "自定义日报标题", List.of(" User@Example.com ", "user@example.com"),
                List.of(" Copy@Example.com "), "请查收日报"));

        verify(mailService).sendTextWithAttachmentsSync(
                org.mockito.ArgumentMatchers.eq(List.of("user@example.com")),
                org.mockito.ArgumentMatchers.eq(List.of("copy@example.com")),
                org.mockito.ArgumentMatchers.eq("自定义日报标题"),
                org.mockito.ArgumentMatchers.eq("请查收日报"),
                org.mockito.ArgumentMatchers.anyList());
        assertEquals("SENT", view.status());
        assertTrue(view.sentAt() != null);
        assertEquals("SENT", mailDao.find("DZ20260909-02").orElseThrow().status());
        assertEquals("自定义日报标题", mailDao.find("DZ20260909-02").orElseThrow().subject());
        assertEquals(List.of("user@example.com"), mailDao.find("DZ20260909-02").orElseThrow().toEmails());
    }

    @Test
    void persistsFailedStatusWhenSmtpFails() {
        byte[] content = new byte[]{5};
        saveSnapshot("RPT20260910-01", content);
        doThrow(new IllegalStateException("SMTP不可用")).when(mailService).sendTextWithAttachmentsSync(
                org.mockito.ArgumentMatchers.eq(List.of("first@example.com", "second@example.com")),
                org.mockito.ArgumentMatchers.eq(List.of("cc@example.com")),
                org.mockito.ArgumentMatchers.eq("对公分布式核心回放问题日报-20260910"),
                org.mockito.ArgumentMatchers.eq("正文"), org.mockito.ArgumentMatchers.anyList());

        assertThrows(ReplayDailyReportMailService.MailSendException.class,
                () -> service.send(new ReplayDailyReportMailSendRequest(
                        "RPT20260910-01", "对公分布式核心回放问题日报-20260910",
                        List.of("first@example.com", "second@example.com"), List.of("cc@example.com"), "正文")));

        assertEquals("FAILED", mailDao.find("RPT20260910-01").orElseThrow().status());
        assertTrue(mailDao.find("RPT20260910-01").orElseThrow().failureMessage().contains("SMTP不可用"));
    }

    @Test
    void validatesSnapshotBodyBatchAndConfiguration() {
        saveSnapshot("RPT20260911-01", new byte[]{1});

        assertThrows(IllegalArgumentException.class, () -> service.send(request("RPT20260911-01", " ", List.of("to@example.com"), "正文")));
        assertThrows(IllegalArgumentException.class, () -> service.send(request("RPT20260911-01", "标题", List.of(), "正文")));
        assertThrows(IllegalArgumentException.class, () -> service.send(request("RPT20260911-01", "标题", List.of("bad-address"), "正文")));
        assertThrows(IllegalArgumentException.class, () -> service.send(request("RPT20260911-01", "标题", List.of("to@example.com"), " ")));
        assertThrows(IllegalArgumentException.class, () -> service.send(request("RPT20260911-01", "标题", List.of("to@example.com"), "x".repeat(10001))));
        assertThrows(ReplayDailyReportMailService.MalformedBatchException.class,
                () -> service.configuration("RPT-BAD"));
        assertThrows(ReplayDailyReportMailService.SnapshotNotFoundException.class,
                () -> service.configuration("RPT20260912-01"));

        when(mailService.configuredFrom()).thenReturn(" ");
        assertThrows(ReplayDailyReportMailService.ConfigurationException.class,
                () -> service.configuration("RPT20260911-01"));
    }

    @Test
    void refusesToSendWhenCurrentSnapshotAttachmentIsEmpty() {
        saveSnapshot("RPT20260912-01", new byte[0]);

        assertThrows(ReplayDailyReportMailService.SnapshotNotFoundException.class,
                () -> service.send(request("RPT20260912-01", "标题", List.of("to@example.com"), "正文")));

        verify(mailService, never()).sendTextWithAttachmentsSync(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
        assertTrue(mailDao.find("RPT20260912-01").isEmpty());
    }

    private ReplayDailyReportMailSendRequest request(String batchNo, String subject,
                                                       List<String> toEmails, String body) {
        return new ReplayDailyReportMailSendRequest(batchNo, subject, toEmails, List.of(), body);
    }

    private void saveSnapshot(String batchNo, byte[] content) {
        dailyDataDao.saveReportSnapshot(new ReplayDailyReportSnapshot(batchNo, batchNo + "日报.xlsx",
                XLSX, content, content.length, LocalDateTime.of(2026, 9, 8, 9, 0)));
    }
}
