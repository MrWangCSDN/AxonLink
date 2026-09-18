package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCompiledScope;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScopeValidationError;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReplayDatabaseComparisonScopeCompiler {

    private static final long MAX_COMPARE_LIMIT = 10_000_000L;
    private static final EnumSet<ReplayDbCompareConditionOperator> TEXT_OPERATORS = EnumSet.of(
            ReplayDbCompareConditionOperator.EQ, ReplayDbCompareConditionOperator.NE,
            ReplayDbCompareConditionOperator.LIKE, ReplayDbCompareConditionOperator.IN,
            ReplayDbCompareConditionOperator.IS_NULL, ReplayDbCompareConditionOperator.IS_NOT_NULL);
    private static final EnumSet<ReplayDbCompareConditionOperator> ORDERED_OPERATORS = EnumSet.of(
            ReplayDbCompareConditionOperator.EQ, ReplayDbCompareConditionOperator.NE,
            ReplayDbCompareConditionOperator.GT, ReplayDbCompareConditionOperator.GE,
            ReplayDbCompareConditionOperator.LT, ReplayDbCompareConditionOperator.LE,
            ReplayDbCompareConditionOperator.IN, ReplayDbCompareConditionOperator.BETWEEN,
            ReplayDbCompareConditionOperator.IS_NULL, ReplayDbCompareConditionOperator.IS_NOT_NULL);
    private static final EnumSet<ReplayDbCompareConditionOperator> BOOLEAN_OPERATORS = EnumSet.of(
            ReplayDbCompareConditionOperator.EQ, ReplayDbCompareConditionOperator.NE,
            ReplayDbCompareConditionOperator.IN,
            ReplayDbCompareConditionOperator.IS_NULL, ReplayDbCompareConditionOperator.IS_NOT_NULL);

    private final ReplayDatabaseComparisonConditionCodec codec;

    public ReplayDatabaseComparisonScopeCompiler(ReplayDatabaseComparisonConditionCodec codec) {
        this.codec = codec;
    }

    public ReplayDbCompareCompiledScope compile(
            ReplayDbCompareConditionTree tree,
            Long compareLimit,
            Collection<ReplayBaseColumnOption> columns) {
        ReplayDbCompareConditionTree normalized = codec.normalize(tree);
        Map<String, ReplayBaseColumnOption> columnsByName = new LinkedHashMap<>();
        if (columns != null) {
            for (ReplayBaseColumnOption column : columns) {
                columnsByName.put(column.columnName().toLowerCase(Locale.ROOT), column);
            }
        }

        List<ReplayDbCompareScopeValidationError> errors = new ArrayList<>();
        List<String> compiledGroups = new ArrayList<>();
        if (normalized != null) {
            boolean singleCondition = normalized.groups().size() == 1
                    && normalized.groups().get(0).conditions().size() == 1;
            validateConnector(normalized.connector(), "whereCondition.connector", errors);
            for (int groupIndex = 0; groupIndex < normalized.groups().size(); groupIndex++) {
                ReplayDbCompareConditionGroup group = normalized.groups().get(groupIndex);
                String groupPath = "whereCondition.groups[" + groupIndex + "]";
                validateConnector(group.connector(), groupPath + ".connector", errors);
                if (group.conditions().isEmpty()) {
                    errors.add(error(groupPath, "条件组至少包含一个条件"));
                    continue;
                }
                List<String> compiledConditions = new ArrayList<>();
                for (int conditionIndex = 0; conditionIndex < group.conditions().size(); conditionIndex++) {
                    ReplayDbCompareCondition condition = group.conditions().get(conditionIndex);
                    String path = groupPath + ".conditions[" + conditionIndex + "]";
                    String compiled = compileCondition(condition, path, columnsByName, errors);
                    if (compiled != null) {
                        compiledConditions.add(compiled);
                    }
                }
                if (compiledConditions.size() == group.conditions().size()) {
                    String compiledGroup = String.join(
                            " " + group.connector().name() + " ", compiledConditions);
                    compiledGroups.add(singleCondition
                            ? compiledGroup
                            : "(" + compiledGroup + ")");
                }
            }
        }
        if (compareLimit != null && (compareLimit < 1 || compareLimit > MAX_COMPARE_LIMIT)) {
            errors.add(error("compareLimit", "比对条数必须在 1～10000000 之间"));
        }
        if (!errors.isEmpty()) {
            throw new ReplayDatabaseComparisonScopeException(errors);
        }

        String whereSql = normalized == null
                ? null
                : String.join(" " + normalized.connector().name() + " ", compiledGroups);
        return new ReplayDbCompareCompiledScope(normalized, whereSql, compareLimit);
    }

    private String compileCondition(
            ReplayDbCompareCondition condition,
            String path,
            Map<String, ReplayBaseColumnOption> columnsByName,
            List<ReplayDbCompareScopeValidationError> errors) {
        if (condition.columnName() == null || condition.columnName().isBlank()) {
            errors.add(error(path, "条件字段不能为空"));
            return null;
        }
        ReplayBaseColumnOption column = columnsByName.get(condition.columnName());
        if (column == null) {
            errors.add(error(path, "条件字段 " + condition.columnName() + " 在 BASE 母库中不存在"));
            return null;
        }
        if (condition.operator() == null) {
            errors.add(error(path, "条件运算符不能为空"));
            return null;
        }

        ValueType valueType = valueType(column.dataType());
        boolean operatorValid = operators(valueType).contains(condition.operator());
        if (!operatorValid) {
            errors.add(error(path, "运算符 " + condition.operator() + " 不适用于字段类型 " + column.dataType()));
            return null;
        }

        int expected = expectedValueCount(condition.operator());
        boolean arityValid = expected < 0
                ? !condition.values().isEmpty()
                : condition.values().size() == expected;
        if (!arityValid) {
            errors.add(error(path, valueCountReason(condition.operator())));
        }

        List<String> literals = new ArrayList<>();
        for (int index = 0; index < condition.values().size(); index++) {
            String literal = compileLiteral(
                    condition.values().get(index), valueType,
                    path + ".values[" + index + "]", errors);
            if (literal != null) {
                literals.add(literal);
            }
        }
        if (!arityValid || literals.size() != condition.values().size()) {
            return null;
        }

        String identifier = column.columnName();
        return switch (condition.operator()) {
            case EQ -> identifier + " = " + literals.get(0);
            case NE -> identifier + " <> " + literals.get(0);
            case GT -> identifier + " > " + literals.get(0);
            case GE -> identifier + " >= " + literals.get(0);
            case LT -> identifier + " < " + literals.get(0);
            case LE -> identifier + " <= " + literals.get(0);
            case LIKE -> identifier + " LIKE " + literals.get(0);
            case IN -> identifier + " IN (" + String.join(", ", literals) + ")";
            case BETWEEN -> identifier + " BETWEEN " + literals.get(0) + " AND " + literals.get(1);
            case IS_NULL -> identifier + " IS NULL";
            case IS_NOT_NULL -> identifier + " IS NOT NULL";
        };
    }

    private String compileLiteral(
            String value,
            ValueType valueType,
            String path,
            List<ReplayDbCompareScopeValidationError> errors) {
        if (value == null) {
            errors.add(error(path, "条件值不能为空"));
            return null;
        }
        String trimmed = value.trim();
        try {
            return switch (valueType) {
                case TEXT -> "'" + value.replace("'", "''") + "'";
                case NUMBER -> new BigDecimal(trimmed).toPlainString();
                case DATE -> "'" + LocalDate.parse(trimmed) + "'";
                case TIMESTAMP -> "'" + LocalDateTime.parse(trimmed.replace(' ', 'T'))
                        .toString().replace('T', ' ') + "'";
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
                        throw new IllegalArgumentException();
                    }
                    yield trimmed.toUpperCase(Locale.ROOT);
                }
            };
        } catch (RuntimeException exception) {
            errors.add(error(path, "条件值与字段类型不匹配"));
            return null;
        }
    }

    private EnumSet<ReplayDbCompareConditionOperator> operators(ValueType valueType) {
        return switch (valueType) {
            case TEXT -> TEXT_OPERATORS;
            case NUMBER, DATE, TIMESTAMP -> ORDERED_OPERATORS;
            case BOOLEAN -> BOOLEAN_OPERATORS;
        };
    }

    private int expectedValueCount(ReplayDbCompareConditionOperator operator) {
        return switch (operator) {
            case IS_NULL, IS_NOT_NULL -> 0;
            case BETWEEN -> 2;
            case IN -> -1;
            default -> 1;
        };
    }

    private String valueCountReason(ReplayDbCompareConditionOperator operator) {
        return switch (operator) {
            case IS_NULL, IS_NOT_NULL -> "空值运算符不能填写条件值";
            case BETWEEN -> "BETWEEN 必须填写两个条件值";
            case IN -> "IN 至少填写一个条件值";
            default -> "该运算符必须填写一个条件值";
        };
    }

    private void validateConnector(
            ReplayDbCompareConditionConnector connector,
            String path,
            List<ReplayDbCompareScopeValidationError> errors) {
        if (connector == null) {
            errors.add(error(path, "条件连接符不能为空"));
        }
    }

    private ValueType valueType(String dataType) {
        String normalized = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        if (normalized.contains("bool")) {
            return ValueType.BOOLEAN;
        }
        if (normalized.contains("timestamp") || normalized.contains("datetime")) {
            return ValueType.TIMESTAMP;
        }
        if (normalized.equals("date") || normalized.startsWith("date ")) {
            return ValueType.DATE;
        }
        if (normalized.contains("int") || normalized.contains("numeric")
                || normalized.contains("decimal") || normalized.contains("number")
                || normalized.contains("real") || normalized.contains("double")
                || normalized.contains("float") || normalized.contains("money")) {
            return ValueType.NUMBER;
        }
        return ValueType.TEXT;
    }

    private ReplayDbCompareScopeValidationError error(String path, String reason) {
        return new ReplayDbCompareScopeValidationError(path, reason);
    }

    private enum ValueType {
        TEXT,
        NUMBER,
        DATE,
        TIMESTAMP,
        BOOLEAN
    }
}
