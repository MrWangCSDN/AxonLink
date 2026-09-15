package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetail;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditEvent;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditOperation;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareChangeType;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListItem;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareMetadataStatus;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonDaoTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 12, 9, 0);

    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V63__dii_replay_database_comparison_versions.sql"),
                new ClassPathResource("db/daoindex/V66__replay_db_compare_person_username_snapshots.sql"))
                .execute(jdbc.getDataSource());
        dao = new ReplayDatabaseComparisonDao(jdbc);
    }

    @Test
    void searchesActiveRowsWithReviserAndGroupOwnerFiltersAndOrderedFieldPreview() {
        long accountId = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false,
                field("acct_no", "账号", 1, 2), field("customer_no", "客户号", 2, 1),
                field("status", "状态", 3, 3), field("balance", "余额", 4, 4));
        insert("loan_contract", "贷款合同", "贷款组", "002", "李四", "102", "钱经理",
                LocalDate.of(2026, 9, 11), false, field("contract_no", "合同号", 1, 1));
        insert("acct_history", "账户历史", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 8, 31), true, field("acct_no", "账号", 1, 1));

        ReplayDbCompareQuery query = new ReplayDbCompareQuery(
                "账户", "客户", List.of("存款组"), List.of("001"), List.of("101"),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 12), 0, 20);

        ReplayDbCompareListPage page = dao.search(query);

        assertEquals(1, page.total());
        assertEquals(accountId, page.items().get(0).id());
        assertEquals("张三", page.items().get(0).reviserName());
        assertEquals("赵经理", page.items().get(0).groupOwnerName());
        assertEquals(4, page.items().get(0).fieldCount());
        assertEquals(List.of("customer_no(客户号)", "acct_no(账号)", "status(状态)", "balance(余额)"),
                page.items().get(0).fieldPreview());
    }

    @Test
    void roundTripsNullableActorsAndReviserUsernameSnapshot() {
        ReplayDbCompareRegistration blankActors = new ReplayDbCompareRegistration(
                null, "base", "blank_actor_table", "空人员登记", "存款组",
                null, null, null, null, null, LocalDate.of(2026, 9, 12), false,
                null, null, null, 0, "SYSTEM", "系统", CREATED_AT,
                "SYSTEM", "系统", CREATED_AT, List.of(field("id", "主键", 1, 1)));
        long blankId = dao.insertRegistration(blankActors);

        ReplayDbCompareRegistration imported = new ReplayDbCompareRegistration(
                null, "base", "named_actor_table", "有修订人登记", "贷款组",
                "10001", "c-zhangs", "张三", null, null, LocalDate.of(2026, 9, 12), false,
                null, null, null, 0, "SYSTEM", "系统", CREATED_AT,
                "SYSTEM", "系统", CREATED_AT, List.of(field("id", "主键", 1, 1)));
        long importedId = dao.insertRegistration(imported);

        ReplayDbCompareRegistration blankResult = dao.findByIdIncludingDeleted(blankId);
        assertNull(blankResult.reviserEmpNo());
        assertNull(blankResult.reviserUsername());
        assertNull(blankResult.reviserName());
        assertNull(blankResult.groupOwnerEmpNo());
        assertNull(blankResult.groupOwnerName());

        ReplayDbCompareRegistration importedResult = dao.findByIdIncludingDeleted(importedId);
        assertEquals("10001", importedResult.reviserEmpNo());
        assertEquals("c-zhangs", importedResult.reviserUsername());
        assertEquals("张三", importedResult.reviserName());
    }

    @Test
    void preservesPermanentBusinessKeyAfterDeleteAndUsesOptimisticLock() {
        long id = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));

        assertTrue(dao.markDeleted(id, 0, "停止比对", "002", "李四", CREATED_AT.plusHours(1)));
        dao.clearFields(id);
        assertFalse(dao.markDeleted(id, 0, "重复删除", "003", "王五", CREATED_AT.plusHours(2)));
        assertNull(dao.findByIdIncludingDeleted(9999));

        ReplayDbCompareRegistration deleted = dao.findBySchemaAndTable("BASE", "ACCT_MASTER");
        assertNotNull(deleted);
        assertTrue(deleted.deleted());
        assertEquals(1, deleted.version());
        assertEquals("李四", deleted.reviserName());
        assertTrue(deleted.fields().isEmpty());

        assertThrows(DuplicateKeyException.class, () -> insert(
                "acct_master", null, "公共组", "003", "王五", "103", "孙经理",
                LocalDate.of(2026, 9, 12), false, field("id", null, 1, 1)));
    }

    @Test
    void replacesFieldsInComparisonOrderAndRejectsDuplicateOrder() {
        long id = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));

        dao.replaceFields(id, List.of(
                field("customer_no", "客户号", 2, 2),
                field("acct_no", "账号", 1, 1)), CREATED_AT.plusHours(1));

        assertEquals(List.of("acct_no", "customer_no"), dao.findFields(id).stream()
                .map(ReplayDbCompareField::columnName).toList());
        assertThrows(DuplicateKeyException.class, () -> dao.replaceFields(id, List.of(
                field("acct_no", "账号", 1, 1), field("customer_no", "客户号", 2, 1)),
                CREATED_AT.plusHours(2)));
    }

    @Test
    void roundTripsRegistrationTimePrimaryKeySnapshot() {
        long id = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false,
                new ReplayDbCompareField("acct_no", "账号", 1, true, 1, null),
                new ReplayDbCompareField("status", "状态", 2, false, 2, null));

        List<ReplayDbCompareField> fields = dao.findFields(id);

        assertTrue(fields.get(0).primaryKey());
        assertFalse(fields.get(1).primaryKey());
    }

    @Test
    void findsOrderedFieldsForMultipleRegistrationsInOneSnapshot() {
        long accountId = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false,
                field("status", "状态", 2, 2), field("acct_no", "账号", 1, 1));
        long loanId = insert("loan_contract", "贷款合同", "贷款组", "002", "李四", "102", "钱经理",
                LocalDate.of(2026, 9, 11), false, field("contract_no", "合同号", 1, 1));

        Map<Long, List<ReplayDbCompareField>> result =
                dao.findFieldsByRegistrationIds(List.of(loanId, accountId, accountId));

        assertEquals(List.of(accountId, loanId), result.keySet().stream().toList());
        assertEquals(List.of("acct_no", "status"), result.get(accountId).stream()
                .map(ReplayDbCompareField::columnName).toList());
        assertEquals(List.of("contract_no"), result.get(loanId).stream()
                .map(ReplayDbCompareField::columnName).toList());
    }

    @Test
    void keepsAuditEventsAfterDeleteAndSearchesGloballyNewestFirst() {
        long id = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));
        long createEventId = dao.insertAuditEvent(event(id, ReplayDbCompareAuditOperation.CREATE,
                0, 1, "001", "张三", CREATED_AT));
        long deleteEventId = dao.insertAuditEvent(event(id, ReplayDbCompareAuditOperation.DELETE,
                1, 2, "002", "李四", CREATED_AT));
        dao.insertAuditDetails(deleteEventId, List.of(
                detail(1, ReplayDbCompareChangeType.MODIFY, "deleted", "删除状态", "false", "true"),
                detail(2, ReplayDbCompareChangeType.DELETE, "comparisonFields.acct_no",
                        "比对字段 acct_no", "账号", null)), CREATED_AT);
        assertTrue(dao.markDeleted(id, 0, "停止比对", "002", "李四", CREATED_AT));
        dao.clearFields(id);

        ReplayDbCompareAuditPage page = dao.searchAuditEvents(new ReplayDbCompareAuditQuery(
                "acct", List.of("002"), List.of(ReplayDbCompareAuditOperation.DELETE),
                CREATED_AT.minusMinutes(1), CREATED_AT.plusMinutes(1), 0, 50));

        assertEquals(1, page.total());
        assertEquals(deleteEventId, page.items().get(0).id());
        assertEquals(List.of(deleteEventId, createEventId), dao.searchAuditEvents(
                        ReplayDbCompareAuditQuery.empty(0, 50)).items().stream()
                .map(ReplayDbCompareAuditEvent::id).toList());
        assertEquals(List.of("deleted", "comparisonFields.acct_no"), dao.findAuditDetails(deleteEventId).stream()
                .map(ReplayDbCompareAuditDetail::fieldCode).toList());
    }

    @Test
    void searchesAuditEventsByRegistrationAndOperatorKeyword() {
        long accountId = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));
        long loanId = insert("loan_contract", "贷款合同", "贷款组", "002", "李四", "102", "钱经理",
                LocalDate.of(2026, 9, 11), false, field("contract_no", "合同号", 1, 1));
        dao.insertAuditEvent(event(accountId, ReplayDbCompareAuditOperation.UPDATE,
                1, 1, "001", "张三", CREATED_AT));
        dao.insertAuditEvent(new ReplayDbCompareAuditEvent(null, loanId, "base", "loan_contract",
                ReplayDbCompareAuditOperation.UPDATE, 1, 1, null,
                "002", "user-002", "李四", CREATED_AT.plusSeconds(1)));

        ReplayDbCompareAuditPage page = dao.searchAuditEvents(new ReplayDbCompareAuditQuery(
                accountId, null, "张", List.of(), List.of(), null, null, 0, 50));

        assertEquals(1, page.total());
        assertEquals(accountId, page.items().get(0).registrationId());
        assertEquals("user-001", page.items().get(0).operatorUsername());
        assertEquals("张三", page.items().get(0).operatorName());
    }

    @Test
    void groupsFilteredAuditEventsByBusinessTableBeforePaging() {
        long accountId = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));
        long loanId = insert("loan_contract", "贷款合同", "贷款组", "002", "李四", "102", "钱经理",
                LocalDate.of(2026, 9, 11), false, field("contract_no", "合同号", 1, 1));
        dao.insertAuditEvent(event(accountId, ReplayDbCompareAuditOperation.CREATE,
                0, 1, "001", "张三", CREATED_AT));
        dao.insertAuditEvent(event(accountId, ReplayDbCompareAuditOperation.UPDATE,
                1, 1, "002", "李四", CREATED_AT.plusSeconds(2)));
        dao.insertAuditEvent(event(loanId, ReplayDbCompareAuditOperation.UPDATE,
                1, 1, "001", "张三", CREATED_AT.plusSeconds(1)));

        ReplayDbCompareAuditQuery query = new ReplayDbCompareAuditQuery(
                null, "账户", null, List.of(), List.of(ReplayDbCompareAuditOperation.UPDATE),
                null, null, 0, 20);

        assertEquals(1, dao.countAuditGroups(query));
        List<ReplayDbCompareAuditGroup> groups = dao.findAuditGroupPage(query);
        assertEquals(List.of("acct_master"), groups.stream().map(ReplayDbCompareAuditGroup::tableName).toList());
        assertEquals(1, groups.get(0).matchedEventCount());
        assertEquals(List.of(ReplayDbCompareAuditOperation.UPDATE),
                dao.findAuditEventsForGroups(query, groups).stream()
                        .map(ReplayDbCompareAuditEvent::operation).toList());
    }

    @Test
    void returnsAllowListedHeaderOptionsUsingOtherActiveFilters() {
        insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));
        insert("acct_detail", "账户明细", "存款组", "002", "李四", "102", "钱经理",
                LocalDate.of(2026, 9, 11), false, field("acct_no", "账号", 1, 1));
        insert("loan_contract", "贷款合同", "贷款组", "003", "王五", "103", "孙经理",
                LocalDate.of(2026, 9, 12), false, field("contract_no", "合同号", 1, 1));

        ReplayDbCompareHeaderFilterResult result = dao.headerFilterOptions(
                new ReplayDbCompareHeaderFilterRequest(
                        "reviser", "张", 20, "acct", null, List.of(), List.of(), List.of(), null, null));

        assertEquals(1, result.candidateCount());
        assertEquals(2, result.matchedRegistrationCount());
        assertEquals("001", result.options().get(0).value());
        assertEquals("张三（001）", result.options().get(0).label());
        assertThrows(IllegalArgumentException.class, () -> dao.headerFilterOptions(
                new ReplayDbCompareHeaderFilterRequest(
                        "deleted", null, 20, null, null, List.of(), List.of(), List.of(), null, null)));
    }

    @Test
    void returnsCombinedTableFieldAndReviserLabelsAndSearchesEveryDisplayedPart() {
        long registrationId = insert("acct_master", "账户主表", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));
        jdbc.update("UPDATE dii_replay_db_compare_registration SET reviser_username=? WHERE id=?",
                "c-zhangs", registrationId);

        ReplayDbCompareHeaderFilterResult tableResult = dao.headerFilterOptions(
                new ReplayDbCompareHeaderFilterRequest(
                        "tableName", "账户主", 20, null, null, List.of(), List.of(), List.of(), null, null));
        ReplayDbCompareHeaderFilterResult fieldResult = dao.headerFilterOptions(
                new ReplayDbCompareHeaderFilterRequest(
                        "fieldName", "账号", 20, null, null, List.of(), List.of(), List.of(), null, null));
        ReplayDbCompareHeaderFilterResult reviserResult = dao.headerFilterOptions(
                new ReplayDbCompareHeaderFilterRequest(
                        "reviser", "c-zhangs", 20, null, null, List.of(), List.of(), List.of(), null, null));

        assertEquals("acct_master", tableResult.options().get(0).value());
        assertEquals("acct_master（账户主表）", tableResult.options().get(0).label());
        assertEquals("acct_no", fieldResult.options().get(0).value());
        assertEquals("acct_no（账号）", fieldResult.options().get(0).label());
        assertEquals("001", reviserResult.options().get(0).value());
        assertEquals("张三（c-zhangs）", reviserResult.options().get(0).label());
    }

    @Test
    void findsAllMetadataCandidatesWithoutPaginationUsingNonMetadataFilters() {
        insert("acct_a", "账户甲", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));
        insert("acct_b", "账户乙", "存款组", "002", "李四", "102", "钱经理",
                LocalDate.of(2026, 9, 11), false, field("acct_no", "账号", 1, 1));
        insert("loan_a", "贷款甲", "贷款组", "003", "王五", "103", "孙经理",
                LocalDate.of(2026, 9, 12), false, field("contract_no", "合同号", 1, 1));
        ReplayDbCompareQuery query = new ReplayDbCompareQuery(
                "acct", null, List.of("存款组"), List.of(), List.of(), null, null,
                0, 1, List.of(ReplayDbCompareMetadataStatus.MISSING_FIELDS));

        List<ReplayDbCompareListItem> candidates = dao.findMetadataCandidates(query);

        assertEquals(List.of("acct_a", "acct_b"), candidates.stream()
                .map(ReplayDbCompareListItem::tableName).toList());
        assertTrue(candidates.stream().allMatch(item -> item.fieldCount() == 1));
    }

    @Test
    void loadsEveryActiveRegistrationWithCompleteOrderedFieldsForVersioning() {
        insert("acct_b", "账户乙", "存款组", "002", "李四", "102", "钱经理",
                LocalDate.of(2026, 9, 11), false,
                field("status", "状态", 3, 2), field("acct_no", "账号", 1, 1));
        insert("acct_a", "账户甲", "存款组", "001", "张三", "101", "赵经理",
                LocalDate.of(2026, 9, 10), false, field("acct_no", "账号", 1, 1));
        insert("removed", "已删除", "公共组", "003", "王五", "103", "孙经理",
                LocalDate.of(2026, 9, 9), true, field("id", "主键", 1, 1));

        List<ReplayDbCompareRegistration> registrations = dao.findAllActiveWithFields();

        assertEquals(List.of("acct_a", "acct_b"), registrations.stream()
                .map(ReplayDbCompareRegistration::tableName).toList());
        assertEquals(List.of("acct_no", "status"), registrations.get(1).fields().stream()
                .map(ReplayDbCompareField::columnName).toList());
    }

    private long insert(String tableName, String tableComment, String domain, String reviserEmpNo,
                        String reviserName, String groupOwnerEmpNo, String groupOwnerName,
                        LocalDate registeredDate, boolean deleted, ReplayDbCompareField... fields) {
        ReplayDbCompareRegistration registration = new ReplayDbCompareRegistration(
                null, "base", tableName, tableComment, domain, reviserEmpNo, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, deleted,
                deleted ? "历史删除" : null, deleted ? "900" : null, deleted ? CREATED_AT : null,
                0, "001", "张三", CREATED_AT, reviserEmpNo, reviserName, CREATED_AT, List.of(fields));
        return dao.insertRegistration(registration);
    }

    private ReplayDbCompareAuditEvent event(long registrationId, ReplayDbCompareAuditOperation operation,
                                             long version, int changeCount, String operatorEmpNo,
                                             String operatorName, LocalDateTime operatedAt) {
        return new ReplayDbCompareAuditEvent(null, registrationId, "base", "acct_master", operation,
                version, changeCount, operation == ReplayDbCompareAuditOperation.DELETE ? "停止比对" : null,
                operatorEmpNo, "user-" + operatorEmpNo, operatorName, operatedAt);
    }

    private ReplayDbCompareAuditDetail detail(int order, ReplayDbCompareChangeType type, String code,
                                               String label, String before, String after) {
        return new ReplayDbCompareAuditDetail(null, 0, order, type, code, label, before, after, CREATED_AT);
    }

    private static ReplayDbCompareField field(String name, String comment, int ordinal, int comparisonOrder) {
        return new ReplayDbCompareField(name, comment, ordinal, comparisonOrder);
    }
}
