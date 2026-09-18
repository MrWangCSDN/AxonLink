package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ReplayDatabaseComparisonConditionLabeler {

    public String label(ReplayDbCompareConditionTree tree) {
        if (tree == null || tree.groups().isEmpty()) {
            return "全表";
        }
        return String.join(" " + connector(tree.connector()) + " ",
                tree.groups().stream().map(this::groupLabel).toList());
    }

    private String groupLabel(ReplayDbCompareConditionGroup group) {
        return "(" + String.join(" " + connector(group.connector()) + " ",
                group.conditions().stream().map(this::conditionLabel).toList()) + ")";
    }

    private String conditionLabel(ReplayDbCompareCondition condition) {
        List<String> values = condition.values();
        String columnName = condition.columnName();
        return switch (condition.operator()) {
            case EQ -> columnName + " = " + quoted(values.get(0));
            case NE -> columnName + " <> " + quoted(values.get(0));
            case GT -> columnName + " > " + quoted(values.get(0));
            case GE -> columnName + " >= " + quoted(values.get(0));
            case LT -> columnName + " < " + quoted(values.get(0));
            case LE -> columnName + " <= " + quoted(values.get(0));
            case LIKE -> columnName + " like " + quoted(values.get(0));
            case IN -> columnName + " in (" + String.join(", ", values.stream().map(this::quoted).toList()) + ")";
            case BETWEEN -> columnName + " between " + quoted(values.get(0)) + " and " + quoted(values.get(1));
            case IS_NULL -> columnName + " is null";
            case IS_NOT_NULL -> columnName + " is not null";
        };
    }

    private String connector(Enum<?> connector) {
        return connector.name().toLowerCase(Locale.ROOT);
    }

    private String quoted(String value) {
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }
}
