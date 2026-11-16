package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayMessageSequenceIgnoreCreateRequest;
import com.axonlink.ai.replay.persistence.ReplayMessageSequenceIgnoreDao;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayMessageSequenceIgnoreExportServiceTest {

    @Test
    void exportsOnlyApprovedRowsWithBomAndCrLf() {
        JdbcTemplate jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        ReplayMessageSequenceIgnoreDao dao = new ReplayMessageSequenceIgnoreDao(jdbc);
        ReplayMessageSequenceIgnoreService service = new ReplayMessageSequenceIgnoreService(
                dao, new ReplayConfigPersonResolver(jdbc));
        ReplayConfigOperator operator = new ReplayConfigOperator("user", "用户", "emp");
        service.create(new ReplayMessageSequenceIgnoreCreateRequest("S1&sop", "QUERY", "q1,q2", "原因"), operator)
                .forEach(row -> jdbc.update("UPDATE dii_replay_message_sequence_ignore SET review_status=1 WHERE id=?", row.id()));
        service.create(new ReplayMessageSequenceIgnoreCreateRequest("S1&sop", "ACCOUNTING", "a1", "原因"), operator)
                .forEach(row -> jdbc.update("UPDATE dii_replay_message_sequence_ignore SET review_status=1 WHERE id=?", row.id()));
        service.create(new ReplayMessageSequenceIgnoreCreateRequest("S1&sop", "QUERY", "pending", "原因"), operator);

        byte[] bytes = new ReplayMessageSequenceIgnoreExportService(dao).exportReviewed();
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Arrays.copyOf(bytes, 3));
        assertEquals("查询：q1,q2\r\n账务：a1\r\n",
                new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
    }
}
