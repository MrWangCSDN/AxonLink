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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo"), OPERATOR);
        assertEquals(0, created.reviewStatus());
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
    void approvedRowOnlyEditableByReviewerAndKeepsApproval() {
        ReplayConfigOperator reviewer = new ReplayConfigOperator("lisi", "李四", "c-lisi");
        ReplayConfigOperator stranger = new ReplayConfigOperator("wangwu", "王五", "c-wangwu");
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo"), OPERATOR);
        ReplayUnconditionalIgnoreRow reviewed = service.review(created.id(), created.version(), reviewer);

        assertThrows(ReplayConfigReviewForbiddenException.class, () -> service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "hacked", reviewed.version()), stranger));

        ReplayUnconditionalIgnoreRow updated = service.update(created.id(),
                new ReplayUnconditionalIgnoreUpdateRequest("S1&sop", "accountNumber", reviewed.version()),
                reviewer);
        assertEquals("accountNumber", updated.fieldName());
        assertEquals(1, updated.reviewStatus(), "审核人修改后应保留已审核");

        // 删除/批量删除不受审核限制
        service.delete(updated.id(), updated.version(), stranger);
        assertEquals(0, service.list(10, 0, null, null, null).total());
    }

    @Test
    void reviewRejectedWhenNoMappedBankOwner() {
        ReplayUnconditionalIgnoreRow created = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S9&sop", "accountNo"), OPERATOR);
        assertEquals("无审核人", created.reviewDisabledReason());
        ReplayConfigReviewForbiddenException forbidden = assertThrows(ReplayConfigReviewForbiddenException.class,
                () -> service.review(created.id(), created.version(),
                        new ReplayConfigOperator("lisi", "李四", "c-lisi")));
        assertEquals("无审核人", forbidden.getMessage());
    }

    @Test
    void filtersByReviewStatus() {
        ReplayConfigOperator reviewer = new ReplayConfigOperator("lisi", "李四", "c-lisi");
        ReplayUnconditionalIgnoreRow first = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accA"), OPERATOR);
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accB"), OPERATOR);
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
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "mineA"), OPERATOR);
        ReplayUnconditionalIgnoreRow noOwner = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S9&sop", "otherB"), OPERATOR);
        ReplayUnconditionalIgnoreRow alreadyApproved = service.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "mineC"), OPERATOR);
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
        assertEquals(2, service.list(10, 0, null, null, null, 1, null, null).total());
    }

    @Test
    void batchCreateInsertsAllOrNothing() {
        List<ReplayUnconditionalIgnoreRow> created = service.createBatch(List.of(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "b1"),
                new ReplayUnconditionalIgnoreCreateRequest("S1&soap", "b1"),
                new ReplayUnconditionalIgnoreCreateRequest("S1&bzjson", "b1")), OPERATOR);
        assertEquals(3, created.size());
        assertEquals(3, service.list(10, 0, null, null, null).total());

        assertThrows(ReplayConfigConflictException.class, () -> service.createBatch(List.of(
                new ReplayUnconditionalIgnoreCreateRequest("S2&sop", "x"),
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "b1")), OPERATOR));
        assertEquals(3, service.list(10, 0, null, null, null).total());

        assertThrows(IllegalArgumentException.class, () -> service.createBatch(List.of(), OPERATOR));
    }

    @Test
    void filtersByReviewableByMe() {
        ReplayConfigOperator reviewer = new ReplayConfigOperator("lisi", "李四", "c-lisi");
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "mineA"), OPERATOR);
        service.create(new ReplayUnconditionalIgnoreCreateRequest("S9&sop", "otherB"), OPERATOR);

        ReplayConfigPage<ReplayUnconditionalIgnoreRow> mine = service.list(
                10, 0, null, null, null, null, true, reviewer);
        assertEquals(1, mine.total());
        assertEquals("mineA", mine.items().get(0).fieldName());

        assertEquals(0, service.list(10, 0, null, null, null, null, true, OPERATOR).total());
        assertEquals(1, service.list(10, 0, null, null, null, 0, true, reviewer).total());
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
