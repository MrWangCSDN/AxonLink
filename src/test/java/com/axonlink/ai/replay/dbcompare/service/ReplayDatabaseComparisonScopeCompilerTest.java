package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCompiledScope;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareCondition;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionConnector;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionOperator;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayDatabaseComparisonScopeCompilerTest {

    private final ReplayDatabaseComparisonScopeCompiler compiler =
            new ReplayDatabaseComparisonScopeCompiler(new ReplayDatabaseComparisonConditionCodec());

    @Test
    void compilesTwoLevelGroupsTypedValuesAndEscapedText() {
        ReplayDbCompareConditionTree tree = tree(
                group(ReplayDbCompareConditionConnector.OR,
                        condition("status", ReplayDbCompareConditionOperator.EQ, "1"),
                        condition("status", ReplayDbCompareConditionOperator.EQ, "2")),
                group(ReplayDbCompareConditionConnector.AND,
                        condition("amount", ReplayDbCompareConditionOperator.GE, "100.50"),
                        condition("name", ReplayDbCompareConditionOperator.EQ, "O'Brien")));

        ReplayDbCompareCompiledScope result = compiler.compile(tree, 1000L, columns());

        assertThat(result.whereSql()).isEqualTo(
                "(status = '1' OR status = '2') AND (amount >= 100.50 AND name = 'O''Brien')");
        assertThat(result.compareLimit()).isEqualTo(1000L);
        assertThat(result.conditionTree().groups().get(0).conditions().get(0).columnName())
                .isEqualTo("status");
    }

    @Test
    void omitsRedundantParenthesesForSingleCondition() {
        ReplayDbCompareConditionTree tree = tree(
                group(ReplayDbCompareConditionConnector.OR,
                        condition("status", ReplayDbCompareConditionOperator.EQ, "1")));

        assertThat(compiler.compile(tree, null, columns()).whereSql())
                .isEqualTo("status = '1'");
    }

    @Test
    void compilesInBetweenNullDateTimestampAndBooleanLiterals() {
        ReplayDbCompareConditionTree tree = tree(
                group(ReplayDbCompareConditionConnector.AND,
                        condition("amount", ReplayDbCompareConditionOperator.BETWEEN, "1", "9"),
                        condition("biz_date", ReplayDbCompareConditionOperator.GE, "2026-09-17"),
                        condition("created_at", ReplayDbCompareConditionOperator.LT, "2026-09-18 10:11:12"),
                        condition("enabled", ReplayDbCompareConditionOperator.IN, "true", "false"),
                        condition("name", ReplayDbCompareConditionOperator.IS_NOT_NULL)));

        assertThat(compiler.compile(tree, null, columns()).whereSql()).isEqualTo(
                "(amount BETWEEN 1 AND 9 AND biz_date >= '2026-09-17'"
                        + " AND created_at < '2026-09-18 10:11:12'"
                        + " AND enabled IN (TRUE, FALSE) AND name IS NOT NULL)");
    }

    @Test
    void returnsEveryValidationErrorInStablePathOrder() {
        ReplayDbCompareConditionTree tree = tree(
                group(ReplayDbCompareConditionConnector.AND,
                        condition("missing", ReplayDbCompareConditionOperator.EQ, "1"),
                        condition("amount", ReplayDbCompareConditionOperator.BETWEEN, "bad"),
                        condition("enabled", ReplayDbCompareConditionOperator.LIKE, "yes")),
                new ReplayDbCompareConditionGroup(ReplayDbCompareConditionConnector.OR, List.of()));

        assertThatThrownBy(() -> compiler.compile(tree, 10_000_001L, columns()))
                .isInstanceOfSatisfying(ReplayDatabaseComparisonScopeException.class, exception -> {
                    assertThat(exception.errors()).extracting("path").containsExactly(
                            "whereCondition.groups[0].conditions[0]",
                            "whereCondition.groups[0].conditions[1]",
                            "whereCondition.groups[0].conditions[1].values[0]",
                            "whereCondition.groups[0].conditions[2]",
                            "whereCondition.groups[1]",
                            "compareLimit");
                    assertThat(exception.getMessage()).isEqualTo("比对范围配置存在 6 个问题");
                });
    }

    @Test
    void rejectsWrongValueArityWithoutCompilingUncheckedSql() {
        ReplayDbCompareConditionTree tree = tree(group(ReplayDbCompareConditionConnector.AND,
                condition("name", ReplayDbCompareConditionOperator.EQ, "a", "b"),
                condition("name", ReplayDbCompareConditionOperator.IS_NULL, "unexpected"),
                condition("name", ReplayDbCompareConditionOperator.IN)));

        assertThatThrownBy(() -> compiler.compile(tree, null, columns()))
                .isInstanceOfSatisfying(ReplayDatabaseComparisonScopeException.class,
                        exception -> assertThat(exception.errors()).hasSize(3));
    }

    @Test
    void keepsFullTableScopeEmptyAndAcceptsMaximumLimit() {
        assertThat(compiler.compile(null, null, columns()))
                .isEqualTo(new ReplayDbCompareCompiledScope(null, null, null));
        assertThat(compiler.compile(null, 10_000_000L, columns()).compareLimit())
                .isEqualTo(10_000_000L);
    }

    private ReplayDbCompareConditionTree tree(ReplayDbCompareConditionGroup... groups) {
        return new ReplayDbCompareConditionTree(
                ReplayDbCompareConditionConnector.AND, List.of(groups));
    }

    private ReplayDbCompareConditionGroup group(
            ReplayDbCompareConditionConnector connector,
            ReplayDbCompareCondition... conditions) {
        return new ReplayDbCompareConditionGroup(connector, List.of(conditions));
    }

    private ReplayDbCompareCondition condition(
            String column,
            ReplayDbCompareConditionOperator operator,
            String... values) {
        return new ReplayDbCompareCondition(column, operator, List.of(values));
    }

    private List<ReplayBaseColumnOption> columns() {
        return List.of(
                new ReplayBaseColumnOption("status", "状态", "character varying", 1, false, null),
                new ReplayBaseColumnOption("amount", "金额", "numeric(20,2)", 2, false, null),
                new ReplayBaseColumnOption("name", "名称", "text", 3, false, null),
                new ReplayBaseColumnOption("biz_date", "业务日期", "date", 4, false, null),
                new ReplayBaseColumnOption("created_at", "创建时间", "timestamp without time zone", 5, false, null),
                new ReplayBaseColumnOption("enabled", "启用", "boolean", 6, false, null));
    }
}
