package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonConfigurationHasherTest {

    private final ReplayDatabaseComparisonConfigurationHasher hasher =
            new ReplayDatabaseComparisonConfigurationHasher();

    @Test
    void producesTheSameHashForEquivalentInputIterationOrder() {
        ReplayDbCompareRegistration account = registration(
                1L, "acct_master", "账户主表", 3,
                List.of(field("customer_no", "客户号", 2, false, 2),
                        field("acct_no", "账号", 1, true, 1)));
        ReplayDbCompareRegistration customer = registration(
                2L, "customer_master", "客户主表", 7,
                List.of(field("customer_no", "客户号", 1, true, 1)));

        String first = hasher.hash(List.of(customer, account));
        String second = hasher.hash(List.of(account, customer));

        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void changesWhenAFieldOrderOrSnapshotPropertyChanges() {
        ReplayDbCompareRegistration original = registration(
                1L, "acct_master", "账户主表", 3,
                List.of(field("acct_no", "账号", 1, true, 1),
                        field("customer_no", "客户号", 2, false, 2)));
        ReplayDbCompareRegistration reordered = registration(
                1L, "acct_master", "账户主表", 3,
                List.of(field("acct_no", "账号", 1, true, 2),
                        field("customer_no", "客户号", 2, false, 1)));
        ReplayDbCompareRegistration renamed = registration(
                1L, "acct_master", "账户资料表", 3, original.fields());

        assertNotEquals(hasher.hash(List.of(original)), hasher.hash(List.of(original.withPartitionNum(16))));
        assertNotEquals(hasher.hash(List.of(original)), hasher.hash(List.of(reordered)));
        assertNotEquals(hasher.hash(List.of(original)), hasher.hash(List.of(renamed)));
    }

    @Test
    void usesStableUtf8AndLengthPrefixingForChineseAndEmptyValues() {
        ReplayDbCompareRegistration chinese = registration(
                1L, "acct_master", "账户主表", 3,
                List.of(field("acct_no", "账号", 1, true, 1)));
        ReplayDbCompareRegistration ambiguousDelimiterText = registration(
                1L, "acct_master", "账户|主表", 3,
                List.of(field("acct_no", null, 1, true, 1)));

        assertEquals(hasher.hash(List.of(chinese)), hasher.hash(List.of(chinese)));
        assertNotEquals(hasher.hash(List.of(chinese)), hasher.hash(List.of(ambiguousDelimiterText)));
    }

    @Test
    void changesWhenConditionLimitCompiledSqlOrPrimaryKeyOrderChanges() {
        ReplayDbCompareConditionTree condition = new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.AND,
                        List.of(new ReplayDbCompareCondition(
                                "status", ReplayDbCompareConditionOperator.EQ, List.of("1"))))));
        ReplayDbCompareRegistration base = scopedRegistration(
                condition, 1000L, "(status = '1')",
                List.of(new ReplayDbCompareField("acct_no", "账号", 1, true, 1, 1, null)));
        ReplayDbCompareRegistration changedLimit = scopedRegistration(
                condition, 2000L, "(status = '1')", base.fields());
        ReplayDbCompareRegistration changedSql = scopedRegistration(
                condition, 1000L, "(status = '2')", base.fields());
        ReplayDbCompareRegistration changedPrimaryKeyOrder = scopedRegistration(
                condition, 1000L, "(status = '1')",
                List.of(new ReplayDbCompareField("acct_no", "账号", 1, true, 1, 2, null)));

        assertNotEquals(hasher.hash(List.of(base)), hasher.hash(List.of(changedLimit)));
        assertNotEquals(hasher.hash(List.of(base)), hasher.hash(List.of(changedSql)));
        assertNotEquals(hasher.hash(List.of(base)), hasher.hash(List.of(changedPrimaryKeyOrder)));
    }

    private ReplayDbCompareRegistration scopedRegistration(
            ReplayDbCompareConditionTree condition,
            Long limit,
            String whereSql,
            List<ReplayDbCompareField> fields) {
        LocalDateTime time = LocalDateTime.of(2026, 9, 14, 10, 0);
        return new ReplayDbCompareRegistration(
                1L, "base_schema", "acct_master", "账户主表", "存款组",
                "100", "zhangsan", "张三", "200", "李经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, 3,
                "100", "张三", time, "100", "张三", time, fields,
                condition, limit, whereSql, null);
    }

    private ReplayDbCompareRegistration registration(
            long id,
            String tableName,
            String tableComment,
            long version,
            List<ReplayDbCompareField> fields) {
        LocalDateTime time = LocalDateTime.of(2026, 9, 14, 10, 0);
        return new ReplayDbCompareRegistration(
                id, "base_schema", tableName, tableComment, "存款组",
                "100", "zhangsan", "张三", "200", "李经理",
                LocalDate.of(2026, 9, 14), false, null, null, null, version,
                "100", "张三", time, "100", "张三", time, fields);
    }

    private ReplayDbCompareField field(
            String name,
            String comment,
            int ordinal,
            boolean primaryKey,
            int comparisonOrder) {
        return new ReplayDbCompareField(name, comment, ordinal, primaryKey, comparisonOrder);
    }
}
