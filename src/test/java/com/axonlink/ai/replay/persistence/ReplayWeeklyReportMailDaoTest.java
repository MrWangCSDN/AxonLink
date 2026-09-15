package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailStatus;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentMetadata;
import com.axonlink.ai.replay.dto.ReplayMailAttachmentSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayWeeklyReportMailDaoTest {

    private JdbcTemplate jdbc;
    private ReplayWeeklyReportMailDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V57__dii_replay_daily_report_snapshot.sql"),
                new ClassPathResource("db/daoindex/V58__dii_replay_daily_report_mail.sql"),
                new ClassPathResource("db/daoindex/V60__dii_replay_weekly_report.sql"),
                new ClassPathResource("db/daoindex/V67__replay_report_mail_attachment_manifest.sql"))
                .execute(jdbc.getDataSource());
        ReplayWeeklyReportDao reportDao = new ReplayWeeklyReportDao(jdbc);
        reportDao.saveSnapshot(new ReplayWeeklyReportSnapshot(
                "RPT20260901-01", "RPT20260908-01", "RPT20260908-01周报.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3}, 3, LocalDateTime.of(2026, 9, 8, 9, 0)));
        dao = new ReplayWeeklyReportMailDao(jdbc);
    }

    @Test
    void storesLatestStatusAndCascadesWithWeeklySnapshotDeletion() {
        assertTrue(dao.find("RPT20260901-01", "RPT20260908-01").isEmpty());

        dao.markSending("RPT20260901-01", "RPT20260908-01", "周报标题", "周报正文",
                "sender@example.com", List.of("to@example.com"), List.of("cc@example.com"));
        ReplayWeeklyReportMailStatus sending = dao.find(
                "RPT20260901-01", "RPT20260908-01").orElseThrow();
        assertEquals("SENDING", sending.status());
        assertNull(sending.sentAt());

        dao.markSent("RPT20260901-01", "RPT20260908-01");
        ReplayWeeklyReportMailStatus sent = dao.find(
                "RPT20260901-01", "RPT20260908-01").orElseThrow();
        assertEquals("SENT", sent.status());
        assertEquals("周报标题", sent.subject());
        assertEquals(List.of("to@example.com"), sent.toEmails());

        dao.markSending("RPT20260901-01", "RPT20260908-01", "新标题", "新正文",
                "sender@example.com", List.of("next@example.com"), List.of());
        dao.markFailed("RPT20260901-01", "RPT20260908-01", "x".repeat(1200));
        ReplayWeeklyReportMailStatus failed = dao.find(
                "RPT20260901-01", "RPT20260908-01").orElseThrow();
        assertEquals("FAILED", failed.status());
        assertEquals(1000, failed.failureMessage().length());
        assertNull(failed.sentAt());

        jdbc.update("DELETE FROM dii_replay_weekly_report_snapshot WHERE start_batch_no=? AND end_batch_no=?",
                "RPT20260901-01", "RPT20260908-01");
        assertTrue(dao.find("RPT20260901-01", "RPT20260908-01").isEmpty());
    }

    @Test
    void deletesOnlyTheExactRangeStatus() {
        dao.markSending("RPT20260901-01", "RPT20260908-01", "标题", "正文",
                "sender@example.com", List.of("to@example.com"), List.of());

        assertEquals(0, dao.delete("RPT20260902-01", "RPT20260908-01"));
        assertEquals(1, dao.delete("RPT20260901-01", "RPT20260908-01"));
        assertTrue(dao.find("RPT20260901-01", "RPT20260908-01").isEmpty());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_weekly_report_snapshot", Long.class));
    }

    @Test
    void storesAttachmentManifestInOrder() {
        List<ReplayMailAttachmentMetadata> attachments = List.of(
                new ReplayMailAttachmentMetadata("weekly.xlsx", 3, ReplayMailAttachmentSource.CURRENT_REPORT, "RPT20260908-01"),
                new ReplayMailAttachmentMetadata("extra.xlsx", 5, ReplayMailAttachmentSource.GENERATED_DAILY, "DZ20260907-01"));

        dao.markSending("RPT20260901-01", "RPT20260908-01", "标题", "正文",
                "sender@example.com", List.of("to@example.com"), List.of(), attachments);

        assertEquals(attachments, dao.find("RPT20260901-01", "RPT20260908-01").orElseThrow().attachments());
    }
}
