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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySortFieldServiceTest {

    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("zhangs3", "张三");
    private static final String BASE = "S120034071CorpInfoQryTrdCrclr";

    private JdbcTemplate jdbc;
    private ReplaySortFieldService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO znzx_service "
                        + "(application_name,esf_service_code,flow_id,tran_code,function_desc,group_name) "
                        + "VALUES (?,?,?,?,?,?)",
                "app", "S120034071CorpInfo.QryTrdCrclr", "flow", "6208", "描述", "公共组");
        service = new ReplaySortFieldService(
                new ReplaySortFieldDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc));
    }

    @Test
    void createExpandsThreeRowsAndParsesFieldFormats() {
        List<ReplaySortFieldRow> created = service.create(
                new ReplaySortFieldCreateRequest("6208", "accounts.accountNo", "loans(loanNo,loanType)"),
                OPERATOR);

        assertEquals(3, created.size());
        assertTrue(created.stream().anyMatch(row -> row.origTrcd().equals(BASE + "&sop")
                && row.origArryName().equals("accounts") && row.origFieldName().equals("accountNo")));
        assertTrue(created.stream().anyMatch(row -> row.origTrcd().equals(BASE + "&soap")
                && row.origArryName().equals("loans") && row.origFieldName().equals("loanNo,loanType")));
        assertTrue(created.stream().anyMatch(row -> row.origTrcd().equals(BASE + "&bzjson")
                && row.origArryName().equals("loans") && row.origFieldName().equals("loanNo,loanType")));
        created.forEach(row -> assertEquals(1, row.tranMode()));

        assertEquals(3, service.list(10, 0, null, null, null, null).total());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_sort_field_operation", Integer.class));
    }

    @Test
    void rejectsUnknownTranCodeAndBadFieldFormat() {
        IllegalArgumentException unmapped = assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplaySortFieldCreateRequest("9999", "a.b", "c.d"), OPERATOR));
        assertEquals("交易码无映射：9999", unmapped.getMessage());

        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplaySortFieldCreateRequest("6208", "accounts", "c.d"), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplaySortFieldCreateRequest("6208", "accounts.accountNo", "loans()"), OPERATOR));

        assertEquals(0, service.list(10, 0, null, null, null, null).total());
    }

    @Test
    void listsByUpdatedAtDescThenSingleRowUpdateAndDelete() {
        service.create(new ReplaySortFieldCreateRequest("6208", "a.a1", "b.b1"), OPERATOR);
        List<ReplaySortFieldRow> second = service.create(
                new ReplaySortFieldCreateRequest("6208", "c.c1", "d.d1"), OPERATOR);

        // updated_at DESC：后写入的排在前面
        List<ReplaySortFieldRow> rows = service.list(10, 0, null, null, null, null).items();
        assertEquals(second.get(second.size() - 1).id(), rows.get(0).id());

        ReplaySortFieldRow target = rows.get(0);
        ReplaySortFieldRow updated = service.update(target.id(),
                new ReplaySortFieldUpdateRequest(target.origTrcd(), "accountItems", "accountNo", target.version()),
                OPERATOR);
        assertEquals(1, updated.version());
        assertEquals("accountItems", updated.origArryName());
        assertEquals("UPDATE", service.operations(target.id(), 10, 0).items().get(0).operationType());

        assertThrows(ReplayConfigConflictException.class, () -> service.update(target.id(),
                new ReplaySortFieldUpdateRequest(target.origTrcd(), "x", "y", 0), OPERATOR));

        service.delete(target.id(), 1, OPERATOR);
        assertEquals(5, service.list(10, 0, null, null, null, null).total());
    }
}
