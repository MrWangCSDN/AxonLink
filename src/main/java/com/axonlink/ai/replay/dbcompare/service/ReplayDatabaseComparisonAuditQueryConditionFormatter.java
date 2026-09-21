package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ReplayDatabaseComparisonAuditQueryConditionFormatter {

    private final ReplayDatabaseComparisonConditionLabeler conditionLabeler;

    public ReplayDatabaseComparisonAuditQueryConditionFormatter(
            ReplayDatabaseComparisonConditionLabeler conditionLabeler) {
        this.conditionLabeler = conditionLabeler;
    }

    public String format(
            ReplayDbCompareConditionTree condition,
            List<String> orderingPrimaryKeyNames,
            Long compareLimit) {
        List<String> lines = new ArrayList<>();
        if (condition != null && !condition.groups().isEmpty()) {
            lines.add("where " + readableCondition(condition));
        }
        if (compareLimit != null && orderingPrimaryKeyNames != null
                && !orderingPrimaryKeyNames.isEmpty()) {
            lines.add("order by " + String.join(",", orderingPrimaryKeyNames));
        }
        if (compareLimit != null) {
            lines.add("limit " + compareLimit);
        }
        return lines.isEmpty() ? "全表" : String.join("\n", lines);
    }

    private String readableCondition(ReplayDbCompareConditionTree condition) {
        String label = conditionLabeler.label(condition);
        boolean singleCondition = condition.groups().size() == 1
                && condition.groups().get(0).conditions().size() == 1;
        return singleCondition && label.startsWith("(") && label.endsWith(")")
                ? label.substring(1, label.length() - 1)
                : label;
    }
}
