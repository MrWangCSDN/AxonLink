package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseKeyOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
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
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplayDatabaseComparisonCustomSqlAnalyzerTest {

    private ReplayBaseDataSourceRegistry registry;
    private ReplayBaseMetadataService metadataService;
    private Connection connection;
    private PreparedStatement tableStatement;
    private ResultSet tableRows;
    private PreparedStatement queryStatement;
    private ResultSetMetaData metadata;
    private ReplayDatabaseComparisonCustomSqlAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(ReplayBaseDataSourceRegistry.class);
        metadataService = mock(ReplayBaseMetadataService.class);
        DataSource source = mock(DataSource.class);
        connection = mock(Connection.class);
        tableStatement = mock(PreparedStatement.class);
        queryStatement = mock(PreparedStatement.class);
        tableRows = mock(ResultSet.class);
        ResultSet query = mock(ResultSet.class);
        metadata = mock(ResultSetMetaData.class);
        when(registry.requireDataSource()).thenReturn(source);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("pg_class") ? tableStatement : queryStatement);
        when(tableStatement.executeQuery()).thenReturn(tableRows);
        when(tableRows.next()).thenReturn(true);
        when(tableRows.getBoolean("table_visible")).thenReturn(true);
        when(tableRows.getString("table_comment")).thenReturn("账户主表");
        when(queryStatement.executeQuery()).thenReturn(query);
        when(query.getMetaData()).thenReturn(metadata);
        columns("tenant_id", "Total_Amount");
        ReplayDatabaseComparisonProperties properties = new ReplayDatabaseComparisonProperties();
        properties.setBaseSchema("base_schema");
        when(metadataService.inspectTables(eq(connection), eq("base_schema"), anyCollection(), eq(5)))
                .thenAnswer(call -> {
                    String table = call.<Collection<String>>getArgument(2).iterator().next();
                    var sourceColumns = List.of(new ReplayBaseColumnOption("tenant_id", "租户", 1, true),
                            new ReplayBaseColumnOption("item_id", "项目", 2, false),
                            new ReplayBaseColumnOption("gntltr_id", "总账", 3, false),
                            new ReplayBaseColumnOption("amount", "金额", 4, false));
                    var keyColumns = MAIN_TABLE.equals(table) ? List.of("tenant_id", "item_id") : List.of("tenant_id");
                    return Map.of(table, new ReplayBaseMetadataSnapshot(true, table, null, sourceColumns,
                            List.of(new ReplayBaseKeyOption("source_pkey", "PRIMARY_KEY", keyColumns))));
                });
        analyzer = new ReplayDatabaseComparisonCustomSqlAnalyzer(registry, properties, metadataService);
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

    private static final String MAIN_TABLE = "kgnb_chrg_item";
    private static final String AUX_TABLE = "kgna_gntl_acct";
    private static final String EXISTS_OUTER = "select kci.tenant_id,kci.item_id,kci.gntltr_id,kci.amount from "
            + MAIN_TABLE + " kci where ";
    private static final String EXISTS_INNER = "(select 1 from " + AUX_TABLE
            + " kga where kga.gntltr_id=kci.gntltr_id and kga.gntltr_bsn_stcd<>'S')";

    private AtomicReference<String> trackCatalogLookup() throws SQLException {
        var boundTable = new AtomicReference<String>();
        doAnswer(call -> {
            boundTable.set(call.getArgument(1));
            return null;
        }).when(tableStatement).setString(eq(2), anyString());
        return boundTable;
    }

    @Test
    void correlatedPredicatesValidateBothPhysicalTablesOnceAndKeepOnlyTheOuterIdentity() throws Exception {
        var boundTable = trackCatalogLookup();
        when(tableRows.getString("table_comment")).thenAnswer(call ->
                MAIN_TABLE.equals(boundTable.get()) ? "收费项目" : "总账辅助表");
        columns("tenant_id", "item_id", "gntltr_id", "amount");
        var sql = EXISTS_OUTER + "(exists " + EXISTS_INNER + " or not (kci.amount < 0)) and not exists "
                + EXISTS_INNER.replace("select 1", "select *");

        var prepared = analyzer.prepare(sql, List.of("tenant_id", "item_id", "gntltr_id", "amount"),
                List.of("tenant_id", "item_id"), 50L);

        assertThat(prepared.analysis().sourceTableName()).isEqualTo(MAIN_TABLE);
        assertThat(prepared.analysis().sourceTableComment()).isEqualTo("收费项目");
        assertThat(prepared.analysis().candidateKeyColumns()).containsExactly("tenant_id", "item_id");
        assertThat(prepared.fields()).extracting("columnName").containsExactly("tenant_id", "item_id", "gntltr_id", "amount");
        assertThat(prepared.comparisonKey().columnNames()).containsExactly("tenant_id", "item_id");
        verify(tableStatement, times(2)).setString(1, "base_schema");
        verify(tableStatement).setString(2, MAIN_TABLE);
        verify(tableStatement).setString(2, AUX_TABLE);
        verify(connection).setReadOnly(true);
        verify(connection).setSchema("base_schema");
        verify(metadataService).inspectTables(connection, "base_schema", List.of(MAIN_TABLE), 5);
        verify(queryStatement).executeQuery();
    }

    @Test
    void allowsNotAroundParenthesizedWherePredicatesAndInfersOnlyOuterGroupingKeys() throws Exception {
        columns("tenant_id", "item_id", "total");
        var result = analyzer.analyze("select kci.tenant_id,kci.item_id,sum(kci.amount) as total from " + MAIN_TABLE
                + " kci where not (exists " + EXISTS_INNER + " or kci.amount < 0) group by kci.tenant_id,kci.item_id");
        assertThat(result.candidateKeyColumns()).containsExactly("tenant_id", "item_id");
        assertThat(result.resultColumns()).extracting("columnName").containsExactly("tenant_id", "item_id", "total");
    }

    @Test
    void requiresTheAuxiliaryRelationToExistAsAPhysicalTableInTheBaseSchema() throws Exception {
        var boundTable = trackCatalogLookup();
        when(tableRows.next()).thenAnswer(call -> MAIN_TABLE.equals(boundTable.get()));

        assertThatThrownBy(() -> analyzer.analyze(EXISTS_OUTER + "exists " + EXISTS_INNER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(AUX_TABLE);
        var catalogSql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(catalogSql.capture());
        assertThat(catalogSql.getAllValues()).allSatisfy(statement ->
                assertThat(statement).contains("relkind IN ('r', 'p')", "n.nspname = ?"));
        verifyNoInteractions(queryStatement);
    }

    @Test
    void permissionsFailureOnAuxiliaryTableUsesTheExistingSanitizedBaseError() throws Exception {
        var boundTable = trackCatalogLookup();
        when(tableStatement.executeQuery()).thenAnswer(call -> {
            if (AUX_TABLE.equals(boundTable.get())) throw new SQLException("private DB diagnostic", "42501");
            return tableRows;
        });
        assertThatThrownBy(() -> analyzer.analyze(EXISTS_OUTER + "exists " + EXISTS_INNER))
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class).hasMessageNotContaining("private DB diagnostic");
        verifyNoInteractions(queryStatement);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select count(*) from aux", "select 1, 2 from aux", "select aux.id from aux",
            "select aux.* from aux", "select * except (id) from aux", "select * replace (1 as id) from aux",
            "select 1 as constant from aux", "select distinct 1 from aux",
            "select 1 from public.aux", "select 1 from (select 1 from aux) x",
            "select 1 from aux x join other_aux y on x.id=y.id",
            "select 1 from aux where exists (select 1 from other_aux)",
            "select 1 from aux where id in (select id from other_aux)",
            "select 1 from aux group by id", "select 1 from aux having count(*)>0", "select 1 from aux order by id",
            "select 1 from aux limit 1", "select 1 from aux offset 1", "select 1 from aux fetch first 1 row only",
            "select 1 from aux for share", "select 1 into aux_copy from aux",
            "select 1 from aux union select 1 from other_aux", "with x as (select 1 from aux) select 1 from x",
            "select 1 from aux where id=?", "select 1 from aux where id=$1",
            "select 1 from aux where unsupported_fn(id)>0", "select 1 from aux where current_date=some_date",
            "select 1 from aux where cast(id as custom_type) is null"
    })
    void rejectsUnsupportedExistsClausesBeforeOpeningBaseConnection(String inner) {
        assertThatThrownBy(() -> analyzer.analyze(EXISTS_OUTER + "exists (" + inner + ")"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(registry);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select exists (select 1 from aux) as flag from acct_master",
            "select id from acct_master where case when exists (select 1 from aux) then true else false end",
            "select id from acct_master where coalesce(exists (select 1 from aux), false)",
            "select id from acct_master where id = any (select 1 from aux)",
            "select id from acct_master where id = all (select 1 from aux)",
            "select exists (select 1 from aux) as flag from acct_master where exists (select 1 from aux)",
            "select id from acct_master where exists (select 1 from aux) and id in (select 1 from aux)",
            "select id from acct_master group by id having exists (select 1 from aux)",
            "select id from acct_master order by exists (select 1 from aux)",
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
    void rejectsConstantResultKeyForGlobalAggregate() throws Exception {
        columns("result_key", "total_amount");
        assertThatThrownBy(() -> analyzer.prepare(
                "select 'ALL' as result_key, sum(amount) as total_amount from acct_master",
                List.of("result_key", "total_amount"), List.of("result_key"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("主键或唯一索引");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select amount from acct_master",
            "select tenant_id+0 as amount from acct_master",
            "select amount from acct_master where exists (select 1 from aux where tenant_id=1)"
    })
    void rejectsResultsWhoseFieldNamesDoNotContainACompleteSourceKey(String sql) throws Exception {
        columns("amount");
        assertThatThrownBy(() -> analyzer.analyze(sql))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("主键或唯一索引");
    }

    @Test
    void rejectsArbitraryResultKeyEvenWhenTheSourceKeyIsProjected() {
        assertThatThrownBy(() -> analyzer.prepare("select tenant_id, amount as \"Total_Amount\" from acct_master",
                List.of("tenant_id", "total_amount"), List.of("total_amount"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("主键或唯一索引");
    }

    @Test
    void suggestsAndAcceptsAnEntireUniqueIndexByResultFieldNames() throws Exception {
        var sourceColumns = List.of(new ReplayBaseColumnOption("tenant_id", "租户", 1, true),
                new ReplayBaseColumnOption("external_id", "外部标识", 2, false),
                new ReplayBaseColumnOption("amount", "金额", 3, false));
        when(metadataService.inspectTables(eq(connection), eq("base_schema"), anyCollection(), eq(5)))
                .thenReturn(Map.of("acct_master", new ReplayBaseMetadataSnapshot(true, "acct_master", null,
                        sourceColumns, List.of(new ReplayBaseKeyOption("source_pkey", "PRIMARY_KEY", List.of("tenant_id")),
                        new ReplayBaseKeyOption("external_uidx", "UNIQUE_INDEX", List.of("external_id"))))));
        columns("External_ID", "amount");
        var prepared = analyzer.prepare("select a.external_id as \"External_ID\",a.amount from acct_master a",
                List.of("amount", "external_id"), List.of("external_id"), null);
        assertThat(prepared.analysis().candidateKeyColumns()).containsExactly("external_id");
        assertThat(prepared.analysis().keyOptions()).extracting("indexName").containsExactly("external_uidx");
        assertThat(prepared.comparisonKey().columnNames()).containsExactly("external_id");
    }

    @Test
    void validatesTheChosenResultFieldNamesWithoutConstrainingTheirSqlExpressions() throws Exception {
        columns("tenant_id", "total");
        var prepared = analyzer.prepare("select 'ALL' as tenant_id,sum(amount) as total from acct_master",
                List.of("tenant_id", "total"), List.of("tenant_id"), null);
        assertThat(prepared.comparisonKey().columnNames()).containsExactly("tenant_id");
    }

    @Test
    void returnsEveryCompleteMatchingIndexForTheUserToChoose() throws Exception {
        var options = List.of(new ReplayBaseKeyOption("source_pkey", "PRIMARY_KEY", List.of("tenant_id")),
                new ReplayBaseKeyOption("external_uidx", "UNIQUE_INDEX", List.of("item_id")));
        when(metadataService.inspectTables(eq(connection), eq("base_schema"), anyCollection(), eq(5)))
                .thenReturn(Map.of("acct_master", new ReplayBaseMetadataSnapshot(true, "acct_master", null,
                        List.of(), options)));
        columns("tenant_id", "item_id", "amount");
        var result = analyzer.analyze("select tenant_id,item_id,amount from acct_master");
        assertThat(result.keyOptions()).isEqualTo(options);
    }

    @Test
    void rejectsPartialCompositeKeyInTheOuterProjectionOrResultKeySelection() throws Exception {
        columns("tenant_id", "amount");
        assertThatThrownBy(() -> analyzer.analyze("select tenant_id,amount from " + MAIN_TABLE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("主键或唯一索引");
        columns("tenant_id", "item_id", "amount");
        assertThatThrownBy(() -> analyzer.prepare("select tenant_id,item_id,amount from " + MAIN_TABLE,
                List.of("tenant_id", "item_id", "amount"), List.of("tenant_id"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("主键或唯一索引");
    }

    @Test
    void refusesSourcesWithoutAnEligibleIndexBeforeOutputMetadata() throws Exception {
        when(metadataService.inspectTables(eq(connection), eq("base_schema"), anyCollection(), eq(5)))
                .thenReturn(Map.of("acct_master", new ReplayBaseMetadataSnapshot(true, "acct_master", null, List.of())));
        assertThatThrownBy(() -> analyzer.analyze("select tenant_id from acct_master"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("主键或唯一索引");
        verifyNoInteractions(queryStatement);
    }

    @Test
    void hidesIndexMetadataPermissionFailureAndDoesNotResolveOutputColumns() throws Exception {
        when(metadataService.inspectTables(eq(connection), eq("base_schema"), anyCollection(), eq(5)))
                .thenThrow(new SQLException("private index permission diagnostic", "42501"));
        assertThatThrownBy(() -> analyzer.analyze("select tenant_id from acct_master"))
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessageNotContaining("private index permission diagnostic");
        verifyNoInteractions(queryStatement);
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
