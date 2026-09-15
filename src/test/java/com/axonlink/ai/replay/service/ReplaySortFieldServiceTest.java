package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplaySortFieldCreateRequest;
import com.axonlink.ai.replay.dto.ReplaySortFieldRow;
import com.axonlink.ai.replay.dto.ReplaySortFieldUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplaySortFieldDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplaySortFieldServiceTest {

    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("zhangs3", "张三");

    private ReplaySortFieldService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        service = new ReplaySortFieldService(
                new ReplaySortFieldDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc));
    }

    @Test
    void createListUpdateDeleteAndConflict() {
        ReplaySortFieldRow created = service.create(
                new ReplaySortFieldCreateRequest("S1&bzjson", "accounts", "accountNo"), OPERATOR);
        assertEquals(1, created.tranMode());
        assertEquals(0, created.version());

        assertThrows(ReplayConfigConflictException.class, () -> service.create(
                new ReplaySortFieldCreateRequest("S1&bzjson", "accounts", "accountNo"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplaySortFieldCreateRequest("S1&bzjson", "  ", "accountNo"), OPERATOR));

        assertEquals(1, service.list(10, 0, null, "S1", "accounts", "account").total());

        ReplaySortFieldRow updated = service.update(created.id(),
                new ReplaySortFieldUpdateRequest("S1&bzjson", "accountItems", "accountNo", 0), OPERATOR);
        assertEquals("accountItems", updated.origArryName());
        assertEquals(1, updated.version());
        assertEquals(2, service.operations(created.id(), 10, 0).total());
        assertEquals("UPDATE", service.operations(created.id(), 10, 0).items().get(0).operationType());

        assertThrows(ReplayConfigConflictException.class, () -> service.update(created.id(),
                new ReplaySortFieldUpdateRequest("S1&bzjson", "accountItems", "accountNo", 0), OPERATOR));

        service.delete(created.id(), 1, OPERATOR);
        assertEquals(0, service.list(10, 0, null, null, null, null).total());
    }
}
