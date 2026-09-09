package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayDailyReportMailStatus;
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

class ReplayDailyReportMailDaoTest {

    private JdbcTemplate jdbc;
    private ReplayDailyReportMailDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V57__dii_replay_daily_report_snapshot.sql"),
                new ClassPathResource("db/daoindex/V58__dii_replay_daily_report_mail.sql"))
                .execute(jdbc.getDataSource());
        dao = new ReplayDailyReportMailDao(jdbc);
        jdbc.update("INSERT INTO dii_replay_daily_report_snapshot "
                        + "(batch_no,file_name,content_type,file_content,file_size,generated_at) VALUES (?,?,?,?,?,?)",
                "RPT20260908-01", "RPT20260908-01日报.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3}, 3L, LocalDateTime.of(2026, 9, 8, 9, 0));
    }

    @Test
    void storesLatestStatusAndCascadesWithSnapshotDeletion() {
        assertTrue(dao.find("RPT20260908-01").isEmpty());

        dao.markSending("RPT20260908-01", "标题", "正文", "sender@example.com",
                List.of("to@example.com"), List.of("cc@example.com"));
        ReplayDailyReportMailStatus sending = dao.find("RPT20260908-01").orElseThrow();
        assertEquals("SENDING", sending.status());
        assertNull(sending.sentAt());

        dao.markSent("RPT20260908-01");
        ReplayDailyReportMailStatus sent = dao.find("RPT20260908-01").orElseThrow();
        assertEquals("SENT", sent.status());
        assertEquals("标题", sent.subject());
        assertEquals("正文", sent.body());
        assertEquals(List.of("to@example.com"), sent.toEmails());
        assertEquals(List.of("cc@example.com"), sent.ccEmails());

        dao.markSending("RPT20260908-01", "新标题", "新正文", "sender@example.com",
                List.of("next@example.com"), List.of());
        dao.markFailed("RPT20260908-01", "x".repeat(1200));
        ReplayDailyReportMailStatus failed = dao.find("RPT20260908-01").orElseThrow();
        assertEquals("FAILED", failed.status());
        assertEquals("新正文", failed.body());
        assertEquals(1000, failed.failureMessage().length());
        assertNull(failed.sentAt());

        jdbc.update("DELETE FROM dii_replay_daily_report_snapshot WHERE batch_no=?", "RPT20260908-01");
        assertTrue(dao.find("RPT20260908-01").isEmpty());
    }
}
