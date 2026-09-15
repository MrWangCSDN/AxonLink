package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
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
