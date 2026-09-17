package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayDatabaseComparisonConditionLabelerTest {

    private final ReplayDatabaseComparisonConditionLabeler labeler =
            new ReplayDatabaseComparisonConditionLabeler();

    @Test
    void formatsFullTableAndNestedConditionLabelsLikeTheListPreview() {
        ReplayDbCompareConditionTree tree = new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.OR,
                List.of(
                        new ReplayDbCompareConditionGroup(
                                ReplayDbCompareConditionConnector.AND,
                                List.of(
                                        condition("status_cd", ReplayDbCompareConditionOperator.EQ, "1"),
                                        condition("amount", ReplayDbCompareConditionOperator.BETWEEN, "10", "20"))),
                        new ReplayDbCompareConditionGroup(
                                ReplayDbCompareConditionConnector.OR,
                                List.of(
                                        condition("customer_name", ReplayDbCompareConditionOperator.LIKE, "O'Brien%"),
                                        condition("closed_at", ReplayDbCompareConditionOperator.IS_NULL)))));

        assertThat(labeler.label(null)).isEqualTo("全表");
        assertThat(labeler.label(tree)).isEqualTo(
                "(status_cd = '1' and amount between '10' and '20')"
                        + " or (customer_name like 'O''Brien%' or closed_at is null)");
    }

    private ReplayDbCompareCondition condition(
            String columnName,
            ReplayDbCompareConditionOperator operator,
            String... values) {
        return new ReplayDbCompareCondition(columnName, operator, List.of(values));
    }
}
