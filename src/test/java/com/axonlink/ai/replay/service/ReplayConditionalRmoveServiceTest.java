package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveCreateRequest;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveRow;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveUpdateRequest;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.persistence.ReplayConditionalRmoveDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayConditionalRmoveServiceTest {

    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("zhangs3", "张三");
    private static final String CODE = "S1&sop";

    private ReplayConditionalRmoveService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        service = new ReplayConditionalRmoveService(
                new ReplayConditionalRmoveDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc));
    }

    @Test
    void allocatesReusesAndReallocatesIndex() {
        ReplayConditionalRmoveRow a = create(CODE, "accounts");
        ReplayConditionalRmoveRow b = create(CODE, "others");
        ReplayConditionalRmoveRow c = create(CODE, "third");
        assertEquals(1, a.fieldFileIndx());
        assertEquals(2, b.fieldFileIndx());
        assertEquals(3, c.fieldFileIndx());

        ReplayConditionalRmoveRow other = create("S2&soap", "name");
        assertEquals(1, other.fieldFileIndx());

        service.delete(c.id(), c.version(), OPERATOR);
        ReplayConditionalRmoveRow d = create(CODE, "fourth");
        assertEquals(3, d.fieldFileIndx());

        service.delete(b.id(), b.version(), OPERATOR);
        ReplayConditionalRmoveRow e = create(CODE, "fifth");
        assertEquals(4, e.fieldFileIndx());

        create("S3&soap", "existing");
        ReplayConditionalRmoveRow moved = service.update(a.id(),
                new ReplayConditionalRmoveUpdateRequest("S3&soap", "accounts", 2, "status == '0'", null, 0),
                OPERATOR);
        assertEquals(2, moved.fieldFileIndx());

        ReplayConfigOperationView update = service.operations(a.id(), 10, 0).items().get(0);
        assertEquals("UPDATE", update.operationType());
        assertTrue(update.changes().stream().anyMatch(change -> "orig_trcd".equals(change.field())));
        assertTrue(update.changes().stream().anyMatch(change -> "field_file_indx".equals(change.field())
                && "1".equals(change.oldValue()) && "2".equals(change.newValue())));
    }

    @Test
    void validatesFlagAndDetectsNoChange() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", 3, null, null), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", null, null, null), OPERATOR));

        ReplayConditionalRmoveRow created = service.create(
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", 2, "  ", "x == 1"), OPERATOR);
        assertEquals(2, created.fieldFileFlag());
        assertEquals(null, created.origFieldCond());
        assertEquals("x == 1", created.destFieldCond());

        ReplayConditionalRmoveRow unchanged = service.update(created.id(),
                new ReplayConditionalRmoveUpdateRequest(CODE, "accounts", 2, null, "x == 1", 0), OPERATOR);
        assertEquals(0, unchanged.version());
        assertEquals(1, service.operations(created.id(), 10, 0).total());
    }

    private ReplayConditionalRmoveRow create(String code, String fieldName) {
        return service.create(new ReplayConditionalRmoveCreateRequest(code, fieldName, 1, null, null), OPERATOR);
    }
}
