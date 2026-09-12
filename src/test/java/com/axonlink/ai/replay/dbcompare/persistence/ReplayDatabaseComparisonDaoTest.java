package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHistoryEntry;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonDaoTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 12, 9, 0);

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V62__dii_replay_database_comparison_fields.sql"))
                .execute(jdbc.getDataSource());
        dao = new ReplayDatabaseComparisonDao(jdbc);
    }

    @Test
    void searchesWithCombinedFiltersAndReturnsOrderedFieldPreview() {
        long accountId = insert("acct_master", "账户主表", "存款", "001", "张三", "存款组",
                LocalDate.of(2026, 9, 10), false,
                field("acct_no", "账号", 1), field("customer_no", "客户号", 2),
                field("status", "状态", 3), field("balance", "余额", 4));
        insert("loan_contract", "贷款合同", "贷款", "002", "李四", "贷款组",
                LocalDate.of(2026, 9, 11), false, field("contract_no", "合同号", 1));
        insert("acct_history", "账户历史", "存款", "001", "张三", "存款组",
                LocalDate.of(2026, 8, 31), true, field("acct_no", "账号", 1));

        ReplayDbCompareQuery query = new ReplayDbCompareQuery(
                "账户", "客户", List.of("存款"), List.of("001"), List.of("存款组"),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 12), false, 0, 20);

        ReplayDbCompareListPage page = dao.search(query);

        assertEquals(1, page.total());
        assertEquals(0, page.page());
        assertEquals(20, page.size());
        assertEquals(accountId, page.items().get(0).id());
        assertEquals(4, page.items().get(0).fieldCount());
        assertEquals(List.of("acct_no(账号)", "customer_no(客户号)", "status(状态)"),
                page.items().get(0).fieldPreview());
    }

    @Test
    void paginatesAndCapsPageSizeAtTwoHundred() {
        for (int index = 1; index <= 205; index++) {
            insert("table_" + String.format("%03d", index), null, "公共", "001", "张三", "公共组",
                    LocalDate.of(2026, 9, 12), false, field("id", null, 1));
        }

        ReplayDbCompareListPage firstPage = dao.search(ReplayDbCompareQuery.empty(0, 500));
        ReplayDbCompareListPage secondPage = dao.search(ReplayDbCompareQuery.empty(1, 200));

        assertEquals(205, firstPage.total());
        assertEquals(200, firstPage.size());
        assertEquals(200, firstPage.items().size());
        assertEquals(5, secondPage.items().size());
        assertEquals("table_201", secondPage.items().get(0).tableName());
    }

    @Test
    void loadsCompleteRegistrationAndKeepsDeletedRowsOutOfActiveLookup() {
        long id = insert("acct_master", "账户主表", "存款", "001", "张三", "存款组",
                LocalDate.of(2026, 9, 10), false,
                field("second_col", null, 2), field("first_col", "第一列", 1));

        ReplayDbCompareRegistration registration = dao.findById(id);

        assertNotNull(registration);
        assertEquals(List.of("first_col", "second_col"),
                registration.fields().stream().map(ReplayDbCompareField::columnName).toList());
        assertNotNull(dao.findActiveBySchemaAndTable("BASE", "ACCT_MASTER"));

        assertTrue(dao.markDeleted(id, 0, "暂不参与比对", "900", "管理员", CREATED_AT.plusHours(1)));
        assertNull(dao.findActiveBySchemaAndTable("base", "acct_master"));
        assertTrue(dao.findById(id).deleted());
        assertEquals("管理员", dao.findById(id).updatedName());
        assertFalse(dao.markDeleted(id, 0, "重复删除", "900", "管理员", CREATED_AT.plusHours(2)));
    }

    @Test
    void usesOptimisticLockForUpdatesAndRestore() {
        long id = insert("acct_master", "账户主表", "存款", "001", "张三", "存款组",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1));

        assertTrue(dao.updateRegistration(id, 0, "账户信息主表", "存款", "002", "李四", "存款组",
                LocalDate.of(2026, 9, 12), "调整负责人", "002", "李四", CREATED_AT.plusHours(1)));
        assertFalse(dao.updateRegistration(id, 0, null, "贷款", "003", "王五", "贷款组",
                LocalDate.of(2026, 9, 13), null, "003", "王五", CREATED_AT.plusHours(2)));
        assertEquals(1, dao.findById(id).version());

        assertTrue(dao.markDeleted(id, 1, "暂停", "900", "管理员", CREATED_AT.plusHours(2)));
        assertFalse(dao.restore(id, 1, "900", "管理员", CREATED_AT.plusHours(3)));
        assertTrue(dao.restore(id, 2, "900", "管理员", CREATED_AT.plusHours(3)));
        ReplayDbCompareRegistration restored = dao.findById(id);
        assertFalse(restored.deleted());
        assertNull(restored.deletedReason());
        assertEquals(3, restored.version());
    }

    @Test
    void replacesFieldsAndReturnsHistoryNewestFirst() {
        long id = insert("acct_master", "账户主表", "存款", "001", "张三", "存款组",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1));

        dao.replaceFields(id, List.of(field("customer_no", "客户号", 2), field("acct_no", "账号", 1)),
                CREATED_AT.plusHours(1));
        dao.insertHistory(id, "CREATE", null, "{\"version\":0}", null,
                "001", "张三", CREATED_AT);
        dao.insertHistory(id, "UPDATE", "{\"version\":0}", "{\"version\":1}", "字段调整",
                "002", "李四", CREATED_AT.plusHours(1));

        assertEquals(List.of("acct_no", "customer_no"), dao.findById(id).fields().stream()
                .map(ReplayDbCompareField::columnName).toList());
        List<ReplayDbCompareHistoryEntry> history = dao.listHistory(id, 10, 0);
        assertEquals(List.of("UPDATE", "CREATE"), history.stream()
                .map(ReplayDbCompareHistoryEntry::operation).toList());
        assertEquals("字段调整", history.get(0).reason());
    }

    private long insert(String tableName, String tableComment, String domain, String ownerEmpNo,
                        String ownerName, String groupName, LocalDate registeredDate, boolean deleted,
                        ReplayDbCompareField... fields) {
        ReplayDbCompareRegistration registration = new ReplayDbCompareRegistration(
                null, "base", tableName, tableComment, domain, ownerEmpNo, ownerName, groupName,
                registeredDate, null, deleted, deleted ? "历史删除" : null, deleted ? "900" : null,
                deleted ? CREATED_AT : null, 0, "001", "张三", CREATED_AT,
                "001", "张三", CREATED_AT, List.of(fields));
        return dao.insert(registration);
    }

    private static ReplayDbCompareField field(String name, String comment, int position) {
        return new ReplayDbCompareField(name, comment, position);
    }
}
