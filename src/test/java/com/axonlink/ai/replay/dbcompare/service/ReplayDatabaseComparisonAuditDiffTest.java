package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetailDraft;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditOperation;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareChangeType;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonAuditDiffTest {

    private final ReplayDatabaseComparisonAuditDiff diff = new ReplayDatabaseComparisonAuditDiff();

    @Test
    void comparesNormalizedTableAttributesInStableOrder() {
        ReplayDbCompareState before = state("账户主表", "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 11), false, List.of());
        ReplayDbCompareState after = state("账户信息主表", "公共组", "102", "钱经理",
                LocalDate.of(2026, 9, 12), false, List.of());

        List<ReplayDbCompareAuditDetailDraft> details =
                diff.compare(before, after, ReplayDbCompareAuditOperation.UPDATE);

        assertEquals(List.of("tableComment", "domainName", "groupOwner", "registeredDate"),
                details.stream().map(ReplayDbCompareAuditDetailDraft::fieldCode).toList());
        assertEquals(ReplayDbCompareChangeType.MODIFY, details.get(1).changeType());
        assertEquals("存款组", details.get(1).beforeValue());
        assertEquals("公共组", details.get(1).afterValue());
        assertEquals("101/赵经理", details.get(2).beforeValue());
        assertEquals("102/钱经理", details.get(2).afterValue());
    }

    @Test
    void ignoresWhitespaceAndCaseOnlyIdentifierDifferences() {
        ReplayDbCompareState before = state(" 账户主表 ", "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 12), false,
                List.of(field("ACCT_NO", "账号", 1, 1)));
        ReplayDbCompareState after = state("账户主表", "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 12), false,
                List.of(field(" acct_no ", "账号", 1, 1)));

        assertTrue(diff.compare(before, after, ReplayDbCompareAuditOperation.UPDATE).isEmpty());
    }

    @Test
    void comparesFieldAddDeleteCommentAndOrderDeterministically() {
        ReplayDbCompareState before = state(null, "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 12), false,
                List.of(field("acct_no", "账号", 1, 1),
                        field("customer_no", "客户号", 2, 2),
                        field("status", "旧状态", 3, 3)));
        ReplayDbCompareState after = state(null, "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 12), false,
                List.of(field("status", "状态", 3, 1),
                        field("acct_no", "账号", 1, 2),
                        field("balance", "余额", 4, 3)));

        List<ReplayDbCompareAuditDetailDraft> details =
                diff.compare(before, after, ReplayDbCompareAuditOperation.UPDATE);

        assertEquals(List.of(
                        "comparisonFields.acct_no",
                        "comparisonFields.balance",
                        "comparisonFields.customer_no",
                        "comparisonFields.status",
                        "comparisonFields.status"),
                details.stream().map(ReplayDbCompareAuditDetailDraft::fieldCode).toList());
        assertEquals(List.of(
                        ReplayDbCompareChangeType.REORDER,
                        ReplayDbCompareChangeType.ADD,
                        ReplayDbCompareChangeType.DELETE,
                        ReplayDbCompareChangeType.MODIFY,
                        ReplayDbCompareChangeType.REORDER),
                details.stream().map(ReplayDbCompareAuditDetailDraft::changeType).toList());
        assertEquals("1", details.get(0).beforeValue());
        assertEquals("2", details.get(0).afterValue());
    }

    @Test
    void createAndReregisterEmitOneAddPerCurrentField() {
        ReplayDbCompareState after = state("账户主表", "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 12), false,
                List.of(field("customer_no", "客户号", 2, 2), field("acct_no", "账号", 1, 1)));

        List<ReplayDbCompareAuditDetailDraft> created =
                diff.compare(null, after, ReplayDbCompareAuditOperation.CREATE);
        List<ReplayDbCompareAuditDetailDraft> reregistered =
                diff.compare(null, after, ReplayDbCompareAuditOperation.REREGISTER);

        assertEquals(List.of("tableComment", "domainName", "groupOwner", "registeredDate", "deleted",
                        "queryCondition",
                        "comparisonFields.acct_no", "comparisonFields.customer_no"),
                created.stream().map(ReplayDbCompareAuditDetailDraft::fieldCode).toList());
        assertEquals(2, reregistered.stream()
                .filter(detail -> detail.changeType() == ReplayDbCompareChangeType.ADD)
                .filter(detail -> detail.fieldCode().startsWith("comparisonFields."))
                .count());
    }

    @Test
    void deleteEmitsDeletedFlagAndEveryCurrentField() {
        ReplayDbCompareState before = state("账户主表", "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 12), false,
                List.of(field("acct_no", "账号", 1, 1), field("customer_no", "客户号", 2, 2)));

        List<ReplayDbCompareAuditDetailDraft> details =
                diff.compare(before, null, ReplayDbCompareAuditOperation.DELETE);

        assertEquals(List.of("deleted", "comparisonFields.acct_no", "comparisonFields.customer_no"),
                details.stream().map(ReplayDbCompareAuditDetailDraft::fieldCode).toList());
        assertEquals(List.of(ReplayDbCompareChangeType.MODIFY, ReplayDbCompareChangeType.DELETE,
                        ReplayDbCompareChangeType.DELETE),
                details.stream().map(ReplayDbCompareAuditDetailDraft::changeType).toList());
    }

    @Test
    void auditsAddedPrimaryKeyAndExistingFieldPrimaryKeyChange() {
        ReplayDbCompareState before = state("账户主表", "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 12), false,
                List.of(field("c", "联合主键C", 3, true, 1)));
        ReplayDbCompareState after = state("账户主表", "存款组", "101", "赵经理",
                LocalDate.of(2026, 9, 12), false,
                List.of(
                        field("c", "联合主键C", 3, false, 1),
                        field("f", "新联合主键", 6, true, 2)));

        List<ReplayDbCompareAuditDetailDraft> details =
                diff.compare(before, after, ReplayDbCompareAuditOperation.UPDATE);

        assertEquals(List.of("comparisonFields.c.primaryKey", "comparisonFields.f"),
                details.stream().map(ReplayDbCompareAuditDetailDraft::fieldCode).toList());
        assertEquals("比对字段 c 主键属性", details.get(0).fieldLabel());
        assertEquals("true", details.get(0).beforeValue());
        assertEquals("false", details.get(0).afterValue());
        assertEquals("比对字段 f（母库新增主键）", details.get(1).fieldLabel());
    }

    @Test
    void auditsConditionOrderingAndLimitAsOneReadableQueryCondition() {
        ReplayDbCompareState before = new ReplayDbCompareState(
                "账户主表", "存款组", "101", "赵经理", LocalDate.of(2026, 9, 12),
                false, List.of(), null, null, List.of());
        ReplayDbCompareState after = new ReplayDbCompareState(
                "账户主表", "存款组", "101", "赵经理", LocalDate.of(2026, 9, 12),
                false, List.of(), singleEq("cst_id", "22"), 1000L,
                List.of("acct_no", "cust_no"));

        List<ReplayDbCompareAuditDetailDraft> details =
                diff.compare(before, after, ReplayDbCompareAuditOperation.UPDATE);

        assertEquals(List.of("queryCondition"),
                details.stream().map(ReplayDbCompareAuditDetailDraft::fieldCode).toList());
        assertEquals("查询条件", details.get(0).fieldLabel());
        assertEquals("全表", details.get(0).beforeValue());
        assertEquals("where cst_id = '22'\norder by acct_no,cust_no\nlimit 1000",
                details.get(0).afterValue());
    }

    private static ReplayDbCompareState state(String tableComment, String domain, String ownerEmpNo,
                                              String ownerName, LocalDate registeredDate, boolean deleted,
                                              List<ReplayDbCompareField> fields) {
        return new ReplayDbCompareState(tableComment, domain, ownerEmpNo, ownerName,
                registeredDate, deleted, fields);
    }

    private static ReplayDbCompareField field(String name, String comment, int ordinal, int order) {
        return new ReplayDbCompareField(name, comment, ordinal, order);
    }

    private static ReplayDbCompareField field(
            String name, String comment, int ordinal, boolean primaryKey, int order) {
        return new ReplayDbCompareField(name, comment, ordinal, primaryKey, order);
    }

    private static ReplayDbCompareConditionTree singleEq(String columnName, String value) {
        return new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.AND,
                        List.of(new ReplayDbCompareCondition(
                                columnName, ReplayDbCompareConditionOperator.EQ, List.of(value))))));
    }
}
