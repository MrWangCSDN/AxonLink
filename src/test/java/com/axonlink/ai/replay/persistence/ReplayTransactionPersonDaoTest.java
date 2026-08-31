package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayTransactionPersonRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayTransactionPersonDaoTest {
    private ReplayTransactionPersonDao dao;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayTransactionPersonDao(jdbc);
        LocalDateTime importedAt = LocalDateTime.of(2026, 8, 31, 10, 0);
        dao.replaceAll(List.of(
                row("6208", "c-zhangs、c-lisi", importedAt),
                row("6210", " c-zhangs, c-wang1；c-zhangs ", importedAt),
                row("6211", "c-wang1", importedAt),
                row("6212", null, importedAt)), importedAt);
    }

    @Test
    void developerUsernameLookupSplitsExactlyAndReturnsDistinctCodes() {
        assertEquals(List.of("6208", "6210"),
                dao.findTransactionCodesByDeveloperUsername(" c-zhangs "));
        assertEquals(List.of("6210", "6211"),
                dao.findTransactionCodesByDeveloperUsername("c-wang1"));
        assertTrue(dao.findTransactionCodesByDeveloperUsername("c-wang").isEmpty());
        assertTrue(dao.findTransactionCodesByDeveloperUsername(" ").isEmpty());
    }

    private static ReplayTransactionPersonRow row(String code, String usernames, LocalDateTime importedAt) {
        return new ReplayTransactionPersonRow(null, "公共组", code, "交易" + code,
                usernames, usernames, null, null, importedAt);
    }
}
