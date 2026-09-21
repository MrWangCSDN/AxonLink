package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayDatabaseComparisonAuditQueryConditionFormatterTest {

    private final ReplayDatabaseComparisonAuditQueryConditionFormatter formatter =
            new ReplayDatabaseComparisonAuditQueryConditionFormatter(
                    new ReplayDatabaseComparisonConditionLabeler());

    @Test
    void formatsFullTableWhenScopeIsEmpty() {
        assertEquals("全表", formatter.format(null, List.of(), null));
    }

    @Test
    void formatsSingleConditionWithoutRedundantParentheses() {
        assertEquals("where cst_id = '22'", formatter.format(singleEq("cst_id", "22"), List.of(), null));
    }

    @Test
    void formatsWhereOrderingAndLimitInExecutionOrder() {
        assertEquals("where cst_id = '22'\norder by dbcard_cardnum,cst_acnum\nlimit 100",
                formatter.format(singleEq("cst_id", "22"),
                        List.of("dbcard_cardnum", "cst_acnum"), 100L));
    }

    private ReplayDbCompareConditionTree singleEq(String columnName, String value) {
        return new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND,
                List.of(new ReplayDbCompareConditionGroup(
                        ReplayDbCompareConditionConnector.AND,
                        List.of(new ReplayDbCompareCondition(
                                columnName, ReplayDbCompareConditionOperator.EQ, List.of(value))))));
    }
}
