package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayIssueHistoryDaoTest {
    @Test
    void historyPageIsBoundedAndSortedNewestFirst() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        ReplayIssueDao dao = new ReplayIssueDao(jdbc);
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "row")), LocalDateTime.of(2026, 8, 5, 9, 0));
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(1, 0, null, null, null, null, null, null)).get(0).get("id")).longValue();
        for (int i = 0; i < 3; i++) {
            dao.insertHistory(id, "key-1", "事件" + i, LocalDateTime.of(2026, 8, 5, 9, i),
                    com.axonlink.ai.replay.dto.ReplayIssueOperator.system(), null, null, null, "before" + i, "after" + i, null);
        }
        assertEquals(3, dao.findHistoryByIssueId(id, 999).size());
        assertEquals("事件2", dao.findHistoryByIssueId(id, 2).get(0).operationType());
        assertEquals(2, dao.findHistoryByIssueId(id, 2).size());
    }
}
