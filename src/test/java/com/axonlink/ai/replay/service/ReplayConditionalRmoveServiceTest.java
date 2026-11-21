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
    private static final String CHINESE_NAME = "账号";

    private JdbcTemplate jdbc;
    private ReplayConditionalRmoveService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        service = new ReplayConditionalRmoveService(
                new ReplayConditionalRmoveDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc),
                new ReplayConfigPersonResolver(jdbc));
    }

    @Test
    void filtersBlankHistoricalIgnoreReason() {
        ReplayConditionalRmoveRow blank = create(CODE, "blankReason");
        create(CODE, "filledReason");
        jdbc.update("UPDATE dii_replay_conditional_rmove SET ignore_reason=NULL WHERE id=?", blank.id());

        assertEquals(1, service.list(10, 0, null, null, null, null, null,
                null, null, true, OPERATOR).total());
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
                new ReplayConditionalRmoveUpdateRequest("S3&soap", "accounts", 2, "status == '0'", null,
                        "测试原因", 0),
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
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", " ", 2, null, null, "测试原因"),
                OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", "中".repeat(257), 2,
                        null, null, "测试原因"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", 3, null, null, "测试原因"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", null, null, null, "测试原因"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", 2, null, null, "  "), OPERATOR));

        ReplayConditionalRmoveRow created = service.create(
                new ReplayConditionalRmoveCreateRequest(CODE, "accounts", CHINESE_NAME, 2,
                        "  ", "x == 1", "测试原因"),
                OPERATOR);
        assertEquals(CHINESE_NAME, created.fieldChineseName());
        assertEquals(2, created.fieldFileFlag());
        assertEquals(null, created.origFieldCond());
        assertEquals("x == 1", created.destFieldCond());
        assertEquals("测试原因", created.ignoreReason());

        ReplayConditionalRmoveRow unchanged = service.update(created.id(),
                new ReplayConditionalRmoveUpdateRequest(CODE, "accounts", CHINESE_NAME, 2,
                        null, "x == 1", "测试原因", 0),
                OPERATOR);
        assertEquals(0, unchanged.version());
        assertEquals(CHINESE_NAME, unchanged.fieldChineseName());
        assertEquals(1, service.operations(created.id(), 10, 0).total());
    }

    private ReplayConditionalRmoveRow create(String code, String fieldName) {
        return service.create(new ReplayConditionalRmoveCreateRequest(
                code, fieldName, CHINESE_NAME, 1, null, null, "测试原因"), OPERATOR);
    }

    @Test
    void reasonAndChineseNameChangesKeepApprovalButConditionChangeResetsIt() {
        ReplayConditionalRmoveRow created = create(CODE, "accounts");
        jdbc.update("UPDATE dii_replay_conditional_rmove SET review_status=1 WHERE id=?", created.id());

        ReplayConditionalRmoveRow reasonUpdated = service.update(created.id(),
                new ReplayConditionalRmoveUpdateRequest(CODE, "accounts", CHINESE_NAME, 1,
                        null, null, "新原因", 0), OPERATOR);
        assertEquals(1, reasonUpdated.reviewStatus());

        ReplayConditionalRmoveRow chineseNameUpdated = service.update(created.id(),
                new ReplayConditionalRmoveUpdateRequest(CODE, "accounts", "客户账号", 1,
                        null, null, "新原因", 1), OPERATOR);
        assertEquals(1, chineseNameUpdated.reviewStatus());
        assertTrue(service.operations(created.id(), 10, 0).items().get(0).changes().stream()
                .anyMatch(change -> "field_chinese_name".equals(change.field())));

        ReplayConditionalRmoveRow conditionUpdated = service.update(created.id(),
                new ReplayConditionalRmoveUpdateRequest(CODE, "accounts", "客户账号", 1,
                        "status == 1", null, "新原因", chineseNameUpdated.version()), OPERATOR);
        assertEquals(0, conditionUpdated.reviewStatus());
    }
}
