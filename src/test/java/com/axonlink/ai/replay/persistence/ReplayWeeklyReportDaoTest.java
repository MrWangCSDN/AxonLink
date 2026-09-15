package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportOption;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayWeeklyReportDaoTest {

    private ReplayWeeklyReportDao dao;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V60__dii_replay_weekly_report.sql"),
                new ClassPathResource("db/daoindex/V61__unique_replay_weekly_report_end_batch.sql"))
                .execute(jdbc.getDataSource());
        dao = new ReplayWeeklyReportDao(jdbc);
    }

    @Test
    void storesReportsWithUniqueEndingBatchesAndReturnsGeneratedOptions() {
        dao.saveSnapshot(snapshot("RPT20260901-01", "RPT20260908-01", new byte[]{1, 2}, 9));
        dao.saveSnapshot(snapshot("DZ20260901-01", "DZ20260908-01", new byte[]{4}, 11));

        ReplayWeeklyReportSnapshot first = dao.findSnapshot(
                "RPT20260901-01", "RPT20260908-01").orElseThrow();
        assertArrayEquals(new byte[]{1, 2}, first.content());
        assertEquals("RPT20260908-01周报.xlsx", first.fileName());
        assertEquals("RPT20260901-01", dao.findSnapshotByEndBatchNo(
                "RPT20260908-01").orElseThrow().startBatchNo());

        List<ReplayWeeklyReportOption> options = dao.findGeneratedReports();
        assertEquals(List.of("DZ20260901-01~DZ20260908-01", "RPT20260901-01~RPT20260908-01"),
                options.stream().map(option -> option.startBatchNo() + "~" + option.endBatchNo()).toList());
        assertEquals("UNSENT", options.get(0).mailStatus());
        assertTrue(dao.findSnapshot("RPT20260901-01", "RPT20260909-01").isEmpty());
    }

    @Test
    void rejectsASecondRangeUsingTheSameEndingBatch() {
        dao.saveSnapshot(snapshot("RPT20260901-01", "RPT20260908-01", new byte[]{1}, 9));

        assertThrows(DataIntegrityViolationException.class,
                () -> dao.saveSnapshot(snapshot(
                        "RPT20260902-01", "RPT20260908-01", new byte[]{2}, 10)));
        assertArrayEquals(new byte[]{1}, dao.findSnapshotByEndBatchNo(
                "RPT20260908-01").orElseThrow().content());
        assertEquals(1, dao.findGeneratedReports().size());
    }

    @Test
    void replacesOnlyTheExactExistingRange() {
        dao.saveSnapshot(snapshot("RPT20260901-01", "RPT20260908-01", new byte[]{1}, 9));

        assertEquals(1, dao.replaceSnapshot(snapshot(
                "RPT20260901-01", "RPT20260908-01", new byte[]{7, 8}, 12)));
        assertEquals(0, dao.replaceSnapshot(snapshot(
                "RPT20260902-01", "RPT20260909-01", new byte[]{9}, 13)));

        ReplayWeeklyReportSnapshot replaced = dao.findSnapshot(
                "RPT20260901-01", "RPT20260908-01").orElseThrow();
        assertArrayEquals(new byte[]{7, 8}, replaced.content());
        assertEquals(2L, replaced.fileSize());
        assertEquals(LocalDateTime.of(2026, 9, 8, 12, 0), replaced.generatedAt());
    }

    private static ReplayWeeklyReportSnapshot snapshot(String startBatchNo, String endBatchNo,
                                                        byte[] content, int hour) {
        return new ReplayWeeklyReportSnapshot(startBatchNo, endBatchNo, endBatchNo + "周报.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content, content.length, LocalDateTime.of(2026, 9, 8, hour, 0));
    }
}
