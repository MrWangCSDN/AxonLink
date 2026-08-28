package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueSummaryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueSummaryDaoTest {

    private JdbcTemplate jdbc;
    private ReplayIssueSummaryDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueSummaryDao(jdbc);
    }

    @Test
    void insertAllAndFindByRoundRoundTrips() {
        LocalDateTime importedAt = LocalDateTime.of(2026, 8, 15, 10, 30);
        dao.insertAll("20260815-103000-001", List.of(
                new ReplayIssueSummaryRow("20260815-01", "存款组", 528L, 12345L,
                        100L, 200L, 300L, 400L, 500L, 600L, 95.5, 98.2, "{\"R0C0\":\"批次\"}")),
                importedAt);

        List<ReplayIssueSummaryRow> rows = dao.findByRound("20260815-103000-001");

        assertEquals(1, rows.size());
        ReplayIssueSummaryRow row = rows.get(0);
        assertEquals("20260815-01", row.batchNo());
        assertEquals("存款组", row.domain());
        assertEquals(Long.valueOf(528L), row.coveredInterfaceCount());
        assertEquals(Long.valueOf(12345L), row.sentTransactionCount());
        assertEquals(Long.valueOf(100L), row.c528SuccessCcbsFail());
        assertEquals(Long.valueOf(600L), row.codeIgnored());
        assertEquals(Double.valueOf(95.5), row.successRate());
        assertEquals(Double.valueOf(98.2), row.matchPassRate());
        assertTrue(row.rawJson().contains("批次"));
    }

    @Test
    void insertAllEmptyIsNoOp() {
        dao.insertAll("round-1", List.of(), LocalDateTime.now());
        assertTrue(dao.findByRound("round-1").isEmpty());
    }
}
