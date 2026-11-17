package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayBaseDataSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplayDatabaseComparisonCustomSqlAnalyzerTest {

    private ReplayBaseDataSourceRegistry registry;
    private Connection connection;
    private PreparedStatement tableStatement;
    private PreparedStatement queryStatement;
    private ResultSetMetaData metadata;
    private ReplayDatabaseComparisonCustomSqlAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(ReplayBaseDataSourceRegistry.class);
        DataSource source = mock(DataSource.class);
        connection = mock(Connection.class);
        tableStatement = mock(PreparedStatement.class);
        queryStatement = mock(PreparedStatement.class);
        ResultSet table = mock(ResultSet.class);
        ResultSet query = mock(ResultSet.class);
        metadata = mock(ResultSetMetaData.class);
        when(registry.requireDataSource()).thenReturn(source);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("pg_class") ? tableStatement : queryStatement);
        when(tableStatement.executeQuery()).thenReturn(table);
        when(table.next()).thenReturn(true);
        when(table.getString("table_comment")).thenReturn("账户主表");
        when(queryStatement.executeQuery()).thenReturn(query);
        when(query.getMetaData()).thenReturn(metadata);
        columns("tenant_id", "Total_Amount");
        ReplayDatabaseComparisonProperties properties = new ReplayDatabaseComparisonProperties();
        properties.setBaseSchema("base_schema");
        analyzer = new ReplayDatabaseComparisonCustomSqlAnalyzer(registry, properties);
    }

    @Test
    void analyzesAggregationAndPreservesRealLabelsWhileSuggestingGroupingKeys() throws Exception {
        var result = analyzer.analyze("select tenant_id, sum(amount) as \"Total_Amount\" "
                + "from acct_master group by tenant_id having sum(amount) > 0;");

        assertThat(result.schemaName()).isEqualTo("base_schema");
        assertThat(result.sourceTableName()).isEqualTo("acct_master");
        assertThat(result.sourceTableComment()).isEqualTo("账户主表");
        assertThat(result.resultColumns()).extracting("columnName").containsExactly("tenant_id", "total_amount");
        assertThat(result.resultColumns()).extracting("columnLabel").containsExactly("tenant_id", "Total_Amount");
        assertThat(result.resultColumns()).extracting("ordinalPosition").containsExactly(1, 2);
        assertThat(result.candidateKeyColumns()).containsExactly("tenant_id");
        assertThat(result.sql()).doesNotEndWith(";");
        verify(connection).setReadOnly(true);
        verify(connection).setSchema("base_schema");
        verify(tableStatement).setQueryTimeout(5);
        verify(queryStatement).setQueryTimeout(5);
        verify(tableStatement).setString(1, "base_schema");
        verify(tableStatement).setString(2, "acct_master");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, org.mockito.Mockito.times(2)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues().get(1)).containsIgnoringCase("where 1=0");
    }

    @Test
    void preparesUserOrderedFieldsAndOrderedResultKeys() {
        var prepared = analyzer.prepare("select tenant_id, sum(amount) as \"Total_Amount\" "
                + "from acct_master group by tenant_id",
                List.of("TOTAL_AMOUNT", "Tenant_ID"), List.of("TENANT_ID"), 25L);

        assertThat(prepared.fields()).extracting("columnName").containsExactly("total_amount", "tenant_id");
        assertThat(prepared.fields()).extracting("comparisonOrder").containsExactly(1, 2);
        assertThat(prepared.fields()).extracting("ordinalPosition").containsExactly(2, 1);
        assertThat(prepared.comparisonKey().indexName()).isEqualTo("SQL_RESULT_KEY");
        assertThat(prepared.comparisonKey().indexType()).isEqualTo("RESULT_KEY");
        assertThat(prepared.comparisonKey().columnNames()).containsExactly("tenant_id");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select a.id from acct_master a join other_table b on a.id=b.id",
            "select id from (select id from acct_master) a",
            "select (select id from other_table) as id from acct_master",
            "select id from acct_master where id in (select id from other_table)",
            "select id from acct_master union select id from other_table",
            "with a as (select id from acct_master) select id from a",
            "select row_number() over (order by id) as n from acct_master",
            "select id from acct_master limit 1",
            "select id from acct_master offset 1",
            "select id from acct_master fetch first 1 rows only",
            "select id from acct_master where id=?",
            "select id from acct_master where id=$1",
            "select id into copied from acct_master",
            "select id from acct_master for update",
            "delete from acct_master",
            "select id from acct_master; select id from other_table",
            "select nextval('seq') as id from acct_master",
            "select unsafe_fn(id) as id from acct_master",
            "select public.round(id) as id from acct_master",
            "select current_timestamp as value from acct_master",
            "select current_user as value from acct_master",
            "select id from public.acct_master",
            "select sum(amount) from acct_master"
    })
    void rejectsUnsupportedQueriesBeforeOpeningBaseConnection(String sql) {
        assertThatThrownBy(() -> analyzer.analyze(sql)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(registry);
    }

    @Test
    void acceptsCaseArithmeticAndPureBuiltInFunctions() throws Exception {
        columns("tenant_id", "amount_value");

        var result = analyzer.analyze("select tenant_id, case when amount > 0 then round(coalesce(amount,0),2) "
                + "else abs(amount) end as amount_value from acct_master where tenant_id in ('a','b') "
                + "order by tenant_id");

        assertThat(result.resultColumns()).hasSize(2);
    }

    @Test
    void acceptsConstantResultKeyForGlobalAggregate() throws Exception {
        columns("result_key", "total_amount");

        var result = analyzer.prepare("select 'ALL' as result_key, sum(amount) as total_amount from acct_master",
                List.of("result_key", "total_amount"), List.of("result_key"), null);

        assertThat(result.comparisonKey().columnNames()).containsExactly("result_key");
    }

    @Test
    void rejectsUtf8OversizeSqlBeforeDatabaseAccess() {
        String sql = "select '" + "中".repeat(22_000) + "' as result_key from acct_master";
        assertThatThrownBy(() -> analyzer.analyze(sql)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(registry);
    }

    @Test
    void rejectsCaseFoldDuplicateResultAliases() throws Exception {
        columns("value", "VALUE");
        assertThatThrownBy(() -> analyzer.analyze("select id as value, amount as \"VALUE\" from acct_master"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
    }

    @ParameterizedTest
    @ValueSource(strings = {"中文列", "1value", "bad-name"})
    void rejectsAliasesThatCannotBeImported(String label) throws Exception {
        columns(label);
        assertThatThrownBy(() -> analyzer.analyze("select id as \"" + label + "\" from acct_master"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongResultAliases() throws Exception {
        columns("a".repeat(101));
        assertThatThrownBy(() -> analyzer.analyze("select id as \"" + "a".repeat(101) + "\" from acct_master"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresNonemptyUniqueSelectedFieldsAndResultKeys() {
        String sql = "select tenant_id, sum(amount) as \"Total_Amount\" from acct_master group by tenant_id";
        assertThatThrownBy(() -> analyzer.prepare(sql, List.of(), List.of("tenant_id"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.prepare(sql, List.of("tenant_id"), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.prepare(sql, List.of("tenant_id", "TENANT_ID"), List.of("tenant_id"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.prepare(sql, List.of("total_amount"), List.of("tenant_id"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.prepare(sql, List.of("missing"), List.of("missing"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.prepare(sql, List.of("tenant_id"), List.of("tenant_id", "TENANT_ID"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesExistingCompareLimitValidation() {
        assertThatThrownBy(() -> analyzer.prepare("select tenant_id, amount as \"Total_Amount\" from acct_master",
                List.of("tenant_id"), List.of("tenant_id"), 0L))
                .isInstanceOf(ReplayDatabaseComparisonScopeException.class);
    }

    @Test
    void hidesDatabaseConnectionFailureDetails() throws Exception {
        when(registry.requireDataSource().getConnection()).thenThrow(new SQLException("secret jdbc password", "08001"));
        assertThatThrownBy(() -> analyzer.analyze("select id from acct_master"))
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessageNotContaining("password");
    }

    @Test
    void reportsInvalidDatabaseGroupingAsInputError() throws Exception {
        when(queryStatement.executeQuery()).thenThrow(new SQLException("invalid grouping", "42803"));
        assertThatThrownBy(() -> analyzer.analyze("select tenant_id, sum(amount) as total_amount from acct_master"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsInvalidTypedLiteralAsInputError() throws Exception {
        when(queryStatement.executeQuery()).thenThrow(new SQLException("invalid date", "22007"));
        assertThatThrownBy(() -> analyzer.analyze("select to_date('bad','YYYYMMDD') as value from acct_master"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void columns(String... labels) throws Exception {
        when(metadata.getColumnCount()).thenReturn(labels.length);
        for (int index = 0; index < labels.length; index++) {
            when(metadata.getColumnLabel(index + 1)).thenReturn(labels[index]);
            when(metadata.getColumnTypeName(index + 1)).thenReturn("numeric");
        }
    }
}
