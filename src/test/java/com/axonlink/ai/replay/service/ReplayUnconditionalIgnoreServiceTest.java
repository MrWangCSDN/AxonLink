package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreRow;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreUpdateRequest;
import com.axonlink.ai.replay.persistence.ReplayUnconditionalIgnoreDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayUnconditionalIgnoreServiceTest {

    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("zhangs3", "张三");

    private JdbcTemplate jdbc;
    private ReplayUnconditionalIgnoreService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO znzx_service "
                        + "(application_name,esf_service_code,flow_id,tran_code,function_desc,group_name) "
                        + "VALUES (?,?,?,?,?,?)",
                "app", "S1", "flow", "Y444", "描述", "公共组");
        service = new ReplayUnconditionalIgnoreService(
                new ReplayUnconditionalIgnoreDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc));
    }

    @Test
    void createListUpdateAuditAndDelete() {
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo"), OPERATOR);
        assertEquals(0, created.version());
        assertEquals(1, created.enableFlag());
        assertEquals("S1&sop", created.tranCode());

        assertThrows(ReplayConfigConflictException.class, () -> service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayUnconditionalIgnoreCreateRequest("bad-code", "accountNo"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "   "), OPERATOR));

        ReplayConfigPage<ReplayUnconditionalIgnoreRow> contains = service.list(
                10, 0, null, "S1", "account");
        assertEquals(1, contains.total());

        ReplayConfigPage<ReplayUnconditionalIgnoreRow> mapped = service.list(
                10, 0, "Y444", null, null);
        assertEquals(1, mapped.total());

        ReplayConfigPage<ReplayUnconditionalIgnoreRow> unmapped = service.list(
                10, 0, "NOPE", null, null);
        assertEquals(0, unmapped.total());

        ReplayUnconditionalIgnoreRow updated = service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", 0), OPERATOR);
        assertEquals(1, updated.version());
        assertEquals("accountNumber", updated.fieldName());

        ReplayConfigPage<ReplayConfigOperationView> afterUpdate = service.operations(created.id(), 10, 0);
        assertEquals(2, afterUpdate.total());
        assertEquals("UPDATE", afterUpdate.items().get(0).operationType());
        assertEquals(1, afterUpdate.items().get(0).changes().size());
        assertEquals("field_name", afterUpdate.items().get(0).changes().get(0).field());
        assertEquals("accountNo", afterUpdate.items().get(0).changes().get(0).oldValue());
        assertEquals("accountNumber", afterUpdate.items().get(0).changes().get(0).newValue());

        ReplayUnconditionalIgnoreRow unchanged = service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", 1), OPERATOR);
        assertEquals(1, unchanged.version());
        assertEquals(2, service.operations(created.id(), 10, 0).total());

        assertThrows(ReplayConfigConflictException.class, () -> service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", 0), OPERATOR));
        assertThrows(ReplayConfigConflictException.class, () -> service.delete(created.id(), 0, OPERATOR));

        service.delete(created.id(), 1, OPERATOR);
        assertEquals(0, service.list(10, 0, null, null, null).total());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_unconditional_ignore_operation WHERE config_id=?",
                Integer.class, created.id()));
        assertThrows(ReplayConfigNotFoundException.class, () -> service.delete(created.id(), 1, OPERATOR));
        assertThrows(ReplayConfigNotFoundException.class, () -> service.operations(created.id(), 10, 0));
    }

    @Test
    void validatesPaginationAndBatchDeleteRollsBack() {
        assertThrows(IllegalArgumentException.class, () -> service.list(25, 0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.list(10, -1, null, null, null));

        ReplayUnconditionalIgnoreRow first = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "fieldA"), OPERATOR);
        ReplayUnconditionalIgnoreRow second = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "fieldB"), OPERATOR);
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "fieldC"), OPERATOR);

        assertThrows(ReplayConfigConflictException.class, () -> service.batchDelete(
                List.of(new ReplayConfigVersionedId(first.id(), 0),
                        new ReplayConfigVersionedId(second.id(), 99)),
                OPERATOR));
        assertEquals(3, service.list(10, 0, null, null, null).total());

        int deleted = service.batchDelete(
                List.of(new ReplayConfigVersionedId(first.id(), 0),
                        new ReplayConfigVersionedId(second.id(), 0)),
                OPERATOR);
        assertEquals(2, deleted);
        assertEquals(1, service.list(10, 0, null, null, null).total());
    }

    @Test
    void defaultsToTenPerPage() {
        for (int index = 0; index < 12; index++) {
            service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "field" + index), OPERATOR);
        }
        ReplayConfigPage<ReplayUnconditionalIgnoreRow> page = service.list(null, 0, null, null, null);
        assertEquals(12, page.total());
        assertEquals(10, page.items().size());
    }
}
