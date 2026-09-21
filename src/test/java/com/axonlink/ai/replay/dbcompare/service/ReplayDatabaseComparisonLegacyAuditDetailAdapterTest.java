package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetail;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareChangeType;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReplayDatabaseComparisonLegacyAuditDetailAdapterTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 18, 9, 14, 58);
    private final ReplayDatabaseComparisonLegacyAuditDetailAdapter adapter =
            new ReplayDatabaseComparisonLegacyAuditDetailAdapter();
    private final ReplayDatabaseComparisonConditionCodec codec =
            new ReplayDatabaseComparisonConditionCodec();

    @Test
    void mergesLegacyConditionAndLimitWithoutReorderingOtherDetails() {
        ReplayDbCompareAuditDetail domain = detail(
                1L, 1, "domainName", "领域", "存款组", "公共组");
        ReplayDbCompareAuditDetail condition = detail(
                2L, 2, "whereCondition", "WHERE 条件", "未配置", codec.encode(singleEq()));
        ReplayDbCompareAuditDetail limit = detail(
                3L, 3, "compareLimit", "比对条数", "全表", "1000");
        ReplayDbCompareAuditDetail field = detail(
                4L, 4, "comparisonFields.acct_no", "比对字段 acct_no", "1", "2");

        List<ReplayDbCompareAuditDetail> adapted = adapter.adapt(
                List.of(domain, condition, limit, field));

        assertEquals(List.of("domainName", "queryCondition", "comparisonFields.acct_no"),
                adapted.stream().map(ReplayDbCompareAuditDetail::fieldCode).toList());
        ReplayDbCompareAuditDetail queryCondition = adapted.get(1);
        assertEquals(2L, queryCondition.id());
        assertEquals("查询条件", queryCondition.fieldLabel());
        assertEquals("全表", queryCondition.beforeValue());
        assertEquals("where cst_id = '22'\nlimit 1000", queryCondition.afterValue());
    }

    @Test
    void leavesCurrentQueryConditionDetailUnchanged() {
        ReplayDbCompareAuditDetail current = detail(
                5L, 1, "queryCondition", "查询条件", "全表", "limit 100");

        List<ReplayDbCompareAuditDetail> adapted = adapter.adapt(List.of(current));

        assertEquals(1, adapted.size());
        assertSame(current, adapted.get(0));
    }

    @Test
    void keepsMalformedLegacyConditionReadableInsteadOfFailingRequest() {
        ReplayDbCompareAuditDetail condition = detail(
                6L, 1, "whereCondition", "WHERE 条件", "未配置", "{broken-json");

        ReplayDbCompareAuditDetail adapted = adapter.adapt(List.of(condition)).get(0);

        assertEquals("queryCondition", adapted.fieldCode());
        assertEquals("全表", adapted.beforeValue());
        assertEquals("where {broken-json", adapted.afterValue());
    }

    private ReplayDbCompareAuditDetail detail(
            Long id,
            int order,
            String code,
            String label,
            String before,
            String after) {
        return new ReplayDbCompareAuditDetail(
                id, 99L, order, ReplayDbCompareChangeType.MODIFY,
                code, label, before, after, CREATED_AT);
    }

    private ReplayDbCompareConditionTree singleEq() {
        return new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.AND,
                        List.of(new ReplayDbCompareCondition(
                                "cst_id", ReplayDbCompareConditionOperator.EQ, List.of("22"))))));
    }
}
