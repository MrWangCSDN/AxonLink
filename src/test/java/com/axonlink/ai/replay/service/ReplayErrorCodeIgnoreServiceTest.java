package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayErrorCodeIgnoreDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayErrorCodeIgnoreServiceTest {

    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("zhangs3", "张三");
    private static final String CODE = "S1&sop";

    private ReplayErrorCodeIgnoreService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        service = new ReplayErrorCodeIgnoreService(
                new ReplayErrorCodeIgnoreDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc),
                new ReplayConfigPersonResolver(jdbc));
    }

    @Test
    void keepsNullSemanticsAndRejectsBothEmpty() {
        ReplayErrorCodeIgnoreRow oldOnly = service.create(
                new ReplayErrorCodeIgnoreCreateRequest(CODE, "E001", null, "测试原因"), OPERATOR);
        assertEquals("E001", oldOnly.oldRespCode());
        assertNull(oldOnly.newRespCode());

        ReplayErrorCodeIgnoreRow newOnly = service.create(
                new ReplayErrorCodeIgnoreCreateRequest(CODE, "  ", "N001", "测试原因"), OPERATOR);
        assertNull(newOnly.oldRespCode());
        assertEquals("N001", newOnly.newRespCode());
        assertEquals("测试原因", newOnly.ignoreReason());

        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayErrorCodeIgnoreCreateRequest(CODE, null, null, "测试原因"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayErrorCodeIgnoreCreateRequest("bad", "E1", null, "测试原因"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayErrorCodeIgnoreCreateRequest(CODE, "E1", null, "   "), OPERATOR));

        // (service_code, NULL, N001) 允许重复：保留 MySQL 对 NULL 的唯一索引语义
        service.create(new ReplayErrorCodeIgnoreCreateRequest(CODE, null, "N001", "测试原因"), OPERATOR);
        assertEquals(3, service.list(10, 0, null, null, null, null).total());

        ReplayErrorCodeIgnoreRow updated = service.update(newOnly.id(),
                new ReplayErrorCodeIgnoreUpdateRequest(CODE, null, "N002", "测试原因", newOnly.version()),
                OPERATOR);
        assertEquals("N002", updated.newRespCode());
        assertEquals(1, updated.version());
    }
}
