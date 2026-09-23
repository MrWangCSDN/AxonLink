package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConfigBatchReviewResult;
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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayUnconditionalIgnoreServiceTest {

    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("zhangs3", "张三");
    private static final String REASON = "测试原因";

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
        jdbc.update("INSERT INTO dii_replay_transaction_person "
                        + "(domain,old_transaction_code,old_transaction_name,developer,developer_usernames,"
                        + "bank_owner,bank_owner_emp_nos,imported_at) VALUES (?,?,?,?,?,?,?,?)",
                "公共组", "Y444", "客户信息查询", "张三", "c-zhangs", "李四", "c-lisi", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
        service = new ReplayUnconditionalIgnoreService(
                new ReplayUnconditionalIgnoreDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc),
                new ReplayConfigPersonResolver(jdbc));
    }

    @Test
    void reviewFlowEnforcesBankOwnerAndResetsOnUpdate() {
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", REASON), OPERATOR);
        assertEquals(0, created.reviewStatus());
        assertEquals(REASON, created.ignoreReason());
        assertEquals("Y444", created.oldTransactionCode());
        assertEquals("张三", created.developer());
        assertEquals("李四", created.bankOwner());
        assertFalse(created.canReview());
        assertEquals("没有权限，请联系李四进行审核", created.reviewDisabledReason());

        ReplayConfigOperator reviewer = new ReplayConfigOperator("lisi", "李四", "c-lisi");
        ReplayConfigOperator stranger = new ReplayConfigOperator("wangwu", "王五", "c-wangwu");
        ReplayConfigReviewForbiddenException forbidden = assertThrows(
                ReplayConfigReviewForbiddenException.class,
                () -> service.review(created.id(), created.version(), stranger));
        assertEquals("没有权限，请联系李四进行审核", forbidden.getMessage());

        ReplayUnconditionalIgnoreRow reviewed = service.review(created.id(), created.version(), reviewer);
        assertEquals(1, reviewed.reviewStatus());
        assertFalse(reviewed.canReview());
        assertEquals("已审核", reviewed.reviewDisabledReason());
        assertEquals("REVIEW", service.operations(created.id(), 10, 0).items().get(0).operationType());

        // 幂等：再次审核直接返回，不报错、不新增审计与版本
        ReplayUnconditionalIgnoreRow again = service.review(created.id(), reviewed.version(), reviewer);
        assertEquals(1, again.reviewStatus());
        assertEquals(reviewed.version(), again.version());
        assertEquals(2, service.operations(created.id(), 10, 0).total());
    }

    @Test
    void approvedRowEditableByAnyoneAndResetsToPending() {
        ReplayConfigOperator reviewer = new ReplayConfigOperator("lisi", "李四", "c-lisi");
        ReplayConfigOperator stranger = new ReplayConfigOperator("wangwu", "王五", "c-wangwu");
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", REASON), OPERATOR);
        ReplayUnconditionalIgnoreRow reviewed = service.review(created.id(), created.version(), reviewer);
        assertEquals(1, reviewed.reviewStatus());

        // 任何人都可以修改已审核的数据，修改后回到未审核
        ReplayUnconditionalIgnoreRow updated = service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", REASON, reviewed.version()),
                stranger);
        assertEquals("accountNumber", updated.fieldName());
        assertEquals(0, updated.reviewStatus(), "修改后应回到未审核");
        assertTrue(service.operations(created.id(), 10, 0).items().get(0).changes().stream()
                .anyMatch(change -> "review_status".equals(change.field())
                        && "1".equals(change.oldValue()) && "0".equals(change.newValue())));

        // 修改后仍需行方负责人重新审核
        assertThrows(ReplayConfigReviewForbiddenException.class,
                () -> service.review(updated.id(), updated.version(), stranger));
        assertEquals(1, service.review(updated.id(), updated.version(), reviewer).reviewStatus());
    }

    @Test
    void reviewRejectedWhenNoMappedBankOwner() {
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S9&sop", "accountNo", REASON), OPERATOR);
        assertEquals("无审核人", created.reviewDisabledReason());
        ReplayConfigReviewForbiddenException forbidden = assertThrows(ReplayConfigReviewForbiddenException.class,
                () -> service.review(created.id(), created.version(),
                        new ReplayConfigOperator("lisi", "李四", "c-lisi")));
        assertEquals("无审核人", forbidden.getMessage());
    }

    @Test
    void updateRequiresIgnoreReasonSoLegacyRowsMustBackfill() {
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", REASON), OPERATOR);

        // 修改时不填原因（模拟存量记录补登记场景）→ 400，且不产生修改
        assertThrows(IllegalArgumentException.class, () -> service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", "   ", 0), OPERATOR));
        assertEquals(0, service.list(10, 0, null, null, null).items().get(0).version());

        // 补上原因才能修改成功
        ReplayUnconditionalIgnoreRow updated = service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", "补登记原因", 0), OPERATOR);
        assertEquals(1, updated.version());
        assertEquals("补登记原因", updated.ignoreReason());
    }

    @Test
    void filtersByReviewStatus() {
        ReplayConfigOperator reviewer = new ReplayConfigOperator("lisi", "李四", "c-lisi");
        ReplayUnconditionalIgnoreRow first = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accA", REASON), OPERATOR);
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accB", REASON), OPERATOR);
        service.review(first.id(), first.version(), reviewer);

        assertEquals(1, service.list(10, 0, null, null, null, 0, null).total());
        assertEquals(1, service.list(10, 0, null, null, null, 1, null).total());
        assertEquals(2, service.list(10, 0, null, null, null, null, null).total());
        assertThrows(IllegalArgumentException.class, () -> service.list(10, 0, null, null, null, 9, null));
    }

    @Test
    void batchReviewApprovesOwnPendingRowsAndSkipsOthers() {
        ReplayConfigOperator reviewer = new ReplayConfigOperator("lisi", "李四", "c-lisi");
        ReplayConfigOperator stranger = new ReplayConfigOperator("wangwu", "王五", "c-wangwu");
        ReplayUnconditionalIgnoreRow mine = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "mineA", REASON), OPERATOR);
        ReplayUnconditionalIgnoreRow noOwner = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S9&sop", "otherB", REASON), OPERATOR);
        ReplayUnconditionalIgnoreRow alreadyApproved = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "mineC", REASON), OPERATOR);
        service.review(alreadyApproved.id(), alreadyApproved.version(), reviewer);

        ReplayConfigBatchReviewResult byStranger = service.batchReview(
                List.of(new ReplayConfigVersionedId(mine.id(), mine.version())), stranger);
        assertEquals(0, byStranger.approvedCount());
        assertEquals(1, byStranger.skippedCount());

        ReplayConfigBatchReviewResult result = service.batchReview(List.of(
                new ReplayConfigVersionedId(mine.id(), mine.version()),
                new ReplayConfigVersionedId(noOwner.id(), noOwner.version()),
                new ReplayConfigVersionedId(alreadyApproved.id(), alreadyApproved.version())), reviewer);
        assertEquals(1, result.approvedCount());
        assertEquals(2, result.skippedCount());
        assertEquals(2, service.list(10, 0, null, null, null, null, 1, null, null).total());
    }

    @Test
    void batchCreateInsertsAllOrNothing() {
        List<ReplayUnconditionalIgnoreRow> created = service.createBatch(List.of(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "b1", REASON),
                new ReplayUnconditionalIgnoreCreateRequest("S1&soap", "b1", REASON),
                new ReplayUnconditionalIgnoreCreateRequest("S1&bzjson", "b1", REASON)), OPERATOR);
        assertEquals(3, created.size());
        created.forEach(row -> assertEquals(REASON, row.ignoreReason()));
        assertEquals(3, service.list(10, 0, null, null, null).total());

        assertThrows(ReplayConfigConflictException.class, () -> service.createBatch(List.of(
                new ReplayUnconditionalIgnoreCreateRequest("S2&sop", "x", REASON),
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "b1", REASON)), OPERATOR));
        assertEquals(3, service.list(10, 0, null, null, null).total());

        // 忽略原因必填
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "x", "   "), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.createBatch(List.of(), OPERATOR));
    }

    @Test
    void filtersByReviewableByMe() {
        ReplayConfigOperator reviewer = new ReplayConfigOperator("lisi", "李四", "c-lisi");
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "mineA", REASON), OPERATOR);
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S9&sop", "otherB", REASON), OPERATOR);

        ReplayConfigPage<ReplayUnconditionalIgnoreRow> mine = service.list(
                10, 0, null, null, null, null, null, true, reviewer);
        assertEquals(1, mine.total());
        assertEquals("mineA", mine.items().get(0).fieldName());

        assertEquals(0, service.list(10, 0, null, null, null, null, null, true, OPERATOR).total());
        assertEquals(1, service.list(10, 0, null, null, null, null, 0, true, reviewer).total());
    }

    @Test
    void createListUpdateAuditAndDelete() {
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", REASON), OPERATOR);
        assertEquals(0, created.version());
        assertEquals(1, created.enableFlag());
        assertEquals("S1&sop", created.tranCode());
        assertEquals(REASON, created.ignoreReason());

        assertThrows(ReplayConfigConflictException.class, () -> service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", REASON), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayUnconditionalIgnoreCreateRequest("bad-code", "accountNo", REASON), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "   ", REASON), OPERATOR));

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
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", REASON, 0), OPERATOR);
        assertEquals(1, updated.version());
        assertEquals("accountNumber", updated.fieldName());

        ReplayConfigPage<ReplayConfigOperationView> afterUpdate = service.operations(created.id(), 10, 0);
        assertEquals(2, afterUpdate.total());
        assertEquals("UPDATE", afterUpdate.items().get(0).operationType());
        assertEquals(1, afterUpdate.items().get(0).changes().size());
        assertEquals("field_name", afterUpdate.items().get(0).changes().get(0).field());
        assertEquals("accountNo", afterUpdate.items().get(0).changes().get(0).oldValue());
        assertEquals("accountNumber", afterUpdate.items().get(0).changes().get(0).newValue());

        // 只修改忽略原因也算有效修改，并写审计
        ReplayUnconditionalIgnoreRow reasonOnly = service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", "补充原因", 1), OPERATOR);
        assertEquals(2, reasonOnly.version());
        assertEquals("补充原因", reasonOnly.ignoreReason());
        assertEquals("ignore_reason", service.operations(created.id(), 10, 0).items().get(0)
                .changes().get(0).field());

        ReplayUnconditionalIgnoreRow unchanged = service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", "补充原因", 2), OPERATOR);
        assertEquals(2, unchanged.version());

        assertThrows(ReplayConfigConflictException.class, () -> service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", REASON, 0), OPERATOR));
        assertThrows(ReplayConfigConflictException.class, () -> service.delete(created.id(), 0, OPERATOR));

        service.delete(created.id(), 2, OPERATOR);
        assertEquals(0, service.list(10, 0, null, null, null).total());
        assertEquals(4, jdbc.queryForObject(
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
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "fieldA", REASON), OPERATOR);
        ReplayUnconditionalIgnoreRow second = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "fieldB", REASON), OPERATOR);
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "fieldC", REASON), OPERATOR);

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
            service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "field" + index, REASON), OPERATOR);
        }
        ReplayConfigPage<ReplayUnconditionalIgnoreRow> page = service.list(null, 0, null, null, null);
        assertEquals(12, page.total());
        assertEquals(10, page.items().size());
    }

    @Test
    void exposesDomainResolvedFromTransactionPerson() {
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", REASON), OPERATOR);

        assertEquals("公共组", created.domain());
        assertEquals("公共组", service.list(10, 0, null, null, null).items().get(0).domain());
    }

    @Test
    void filtersByDomainAndIntersectsWithInternalTransactionCode() {
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "domainA", REASON), OPERATOR);
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S9&sop", "domainB", REASON), OPERATOR);

        ReplayConfigPage<ReplayUnconditionalIgnoreRow> byDomain = service.list(
                10, 0, null, "公共组", null, null, null, null, null);
        assertEquals(1, byDomain.total());
        assertEquals("domainA", byDomain.items().get(0).fieldName());
        assertEquals("公共组", byDomain.items().get(0).domain());

        // 领域与内部核心交易码同指一条链路 → 交集命中；两者矛盾或领域未知 → 空
        assertEquals(1, service.list(10, 0, "Y444", "公共组", null, null, null, null, null).total());
        assertEquals(0, service.list(10, 0, "Y444", "其他领域", null, null, null, null, null).total());
        assertEquals(0, service.list(10, 0, null, "无此领域", null, null, null, null, null).total());
    }

    @Test
    void listsDistinctDomainsFromPersonList() {
        seedServiceAndPerson("S2", "Z999", "贷款组", "贷款查询", "c-zhaoliu");

        assertEquals(Set.of("公共组", "贷款组"),
                new HashSet<>(new ReplayConfigPersonResolver(jdbc).listDomains()));
    }

    private void seedServiceAndPerson(String esfServiceCode, String tranCode, String domain, String name,
                                      String bankOwnerEmpNos) {
        jdbc.update("INSERT INTO znzx_service "
                        + "(application_name,esf_service_code,flow_id,tran_code,function_desc,group_name) "
                        + "VALUES (?,?,?,?,?,?)",
                "app-" + tranCode, esfServiceCode, "flow", tranCode, "描述", domain);
        jdbc.update("INSERT INTO dii_replay_transaction_person "
                        + "(domain,old_transaction_code,old_transaction_name,developer,developer_usernames,"
                        + "bank_owner,bank_owner_emp_nos,imported_at) VALUES (?,?,?,?,?,?,?,?)",
                domain, tranCode, name, "开发", "c-dev", "行方", bankOwnerEmpNos,
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
    }
}
