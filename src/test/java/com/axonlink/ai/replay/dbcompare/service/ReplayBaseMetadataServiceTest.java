package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseTableOption;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayBaseDataSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayBaseMetadataServiceTest {

    private ReplayBaseDataSourceRegistry registry;
    private DataSource dataSource;
    private Connection connection;
    private ReplayBaseMetadataService service;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(ReplayBaseDataSourceRegistry.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(registry.requireDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);

        ReplayDatabaseComparisonProperties properties = new ReplayDatabaseComparisonProperties();
        properties.setBaseSchema("base_schema");
        service = new ReplayBaseMetadataService(registry, properties);
    }

    @Test
    void searchTablesRequiresAtLeastTwoCharactersWithoutOpeningConnection() {
        assertThatThrownBy(() -> service.searchTables("a", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("表搜索关键字至少输入 2 个字符");

        verify(registry, never()).requireDataSource();
    }

    @Test
    void searchTablesBindsSchemaKeywordAndClampedLimit() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("table_name")).thenReturn("acct_info");
        when(rows.getString("table_comment")).thenReturn("账户信息");

        List<ReplayBaseTableOption> result = service.searchTables("账户", 500);

        assertThat(result).containsExactly(new ReplayBaseTableOption(
                "base_schema", "acct_info", "账户信息", "UNREGISTERED", null, null));
        verify(statement).setString(1, "base_schema");
        verify(statement).setString(2, "%账户%");
        verify(statement).setInt(3, 50);
    }

    @Test
    void listColumnsMapsChineseCommentsOrdinalPositionsAndCompositePrimaryKeys() throws Exception {
        PreparedStatement tableStatement = mock(PreparedStatement.class);
        PreparedStatement columnStatement = mock(PreparedStatement.class);
        ResultSet tableRow = mock(ResultSet.class);
        ResultSet columnRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("a.attname") ? columnStatement : tableStatement;
        });
        when(tableStatement.executeQuery()).thenReturn(tableRow);
        when(tableRow.next()).thenReturn(true, false);
        when(tableRow.getString("table_name")).thenReturn("acct_info");
        when(tableRow.getString("table_comment")).thenReturn("账户信息");
        when(columnStatement.executeQuery()).thenReturn(columnRows);
        when(columnRows.next()).thenReturn(true, true, true, false);
        when(columnRows.getString("column_name")).thenReturn("key_b", "customer_name", "key_a");
        when(columnRows.getString("column_comment")).thenReturn("联合主键B", "客户名称", "联合主键A");
        when(columnRows.getString("primary_key_attnums")).thenReturn("3 1", "3 1", "3 1");
        when(columnRows.getInt("ordinal_position")).thenReturn(1, 2, 3);

        List<ReplayBaseColumnOption> result = service.listColumns("ACCT_INFO", "客户");

        assertThat(result).containsExactly(
                new ReplayBaseColumnOption("key_b", "联合主键B", 1, true, 2),
                new ReplayBaseColumnOption("customer_name", "客户名称", 2, false, null),
                new ReplayBaseColumnOption("key_a", "联合主键A", 3, true, 1));
        verify(columnStatement).setString(1, "base_schema");
        verify(columnStatement).setString(2, "acct_info");
        verify(columnStatement).setString(3, "%客户%");
    }

    @Test
    void listColumnsUsesGaussDbCompatiblePrimaryKeyMetadataQuery() throws Exception {
        PreparedStatement tableStatement = mock(PreparedStatement.class);
        PreparedStatement columnStatement = mock(PreparedStatement.class);
        ResultSet tableRow = mock(ResultSet.class);
        ResultSet columnRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("a.attname")) {
                assertThat(sql)
                        .doesNotContainIgnoringCase("LATERAL")
                        .doesNotContainIgnoringCase("WITH ORDINALITY")
                        .contains("indkey::text");
                return columnStatement;
            }
            return tableStatement;
        });
        when(tableStatement.executeQuery()).thenReturn(tableRow);
        when(tableRow.next()).thenReturn(true, false);
        when(tableRow.getString("table_name")).thenReturn("acct_info");
        when(columnStatement.executeQuery()).thenReturn(columnRows);
        when(columnRows.next()).thenReturn(false);

        service.listColumns("acct_info", null);
    }

    @Test
    void requireTableWithColumnsReportsEveryMissingFieldTogether() throws Exception {
        PreparedStatement tableStatement = mock(PreparedStatement.class);
        PreparedStatement columnStatement = mock(PreparedStatement.class);
        ResultSet tableRow = mock(ResultSet.class);
        ResultSet columnRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("a.attname") ? columnStatement : tableStatement;
        });
        when(tableStatement.executeQuery()).thenReturn(tableRow);
        when(tableRow.next()).thenReturn(true, false);
        when(tableRow.getString("table_name")).thenReturn("acct_info");
        when(tableRow.getString("table_comment")).thenReturn("账户信息");
        when(columnStatement.executeQuery()).thenReturn(columnRows);
        when(columnRows.next()).thenReturn(true, false);
        when(columnRows.getString("column_name")).thenReturn("acct_id");
        when(columnRows.getString("column_comment")).thenReturn("账户编号");
        when(columnRows.getInt("ordinal_position")).thenReturn(1);

        assertThatThrownBy(() -> service.requireTableWithColumns(
                        "acct_info", List.of("acct_id", "missing_a", "missing_b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing_a")
                .hasMessageContaining("missing_b");
    }

    @Test
    void findsExistingTableNamesInOneQueryAndNormalizesNames() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("table_name")).thenReturn("acct_info");

        Set<String> existing = service.findExistingTableNames(
                List.of("ACCT_INFO", "removed_table", "acct_info"));

        assertThat(existing).containsExactly("acct_info");
        verify(connection, times(1)).prepareStatement(anyString());
        verify(statement).setString(1, "base_schema");
        verify(statement).setString(2, "acct_info");
        verify(statement).setString(3, "removed_table");
    }

    @Test
    void inspectTableReturnsMissingOnlyAfterSuccessfulQuery() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);

        var snapshot = service.inspectTable("removed_table");

        assertThat(snapshot.tableExists()).isFalse();
        assertThat(snapshot.columns()).isEmpty();
    }

    @Test
    void inspectTableDoesNotConvertConnectionFailureIntoMissingTable() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException(
                "connection refused jdbc:opengauss://secret-host:5432/base user=reader"));

        assertThatThrownBy(() -> service.inspectTable("acct_info"))
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessage("BASE 母库未配置或暂不可用")
                .hasMessageNotContaining("secret-host")
                .hasMessageNotContaining("reader");
    }

    @Test
    void inspectTablesLoadsExistingAndMissingTablesWithTwoQueries() throws Exception {
        PreparedStatement tableStatement = mock(PreparedStatement.class);
        PreparedStatement columnStatement = mock(PreparedStatement.class);
        ResultSet tableRows = mock(ResultSet.class);
        ResultSet columnRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("a.attname")) {
                assertThat(sql)
                        .doesNotContainIgnoringCase("LATERAL")
                        .doesNotContainIgnoringCase("WITH ORDINALITY")
                        .contains("indkey::text");
                return columnStatement;
            }
            return tableStatement;
        });
        when(tableStatement.executeQuery()).thenReturn(tableRows);
        when(columnStatement.executeQuery()).thenReturn(columnRows);
        when(tableRows.next()).thenReturn(true, false);
        when(tableRows.getString("table_name")).thenReturn("acct_master");
        when(tableRows.getString("table_comment")).thenReturn("账户主表");
        when(columnRows.next()).thenReturn(true, true, false);
        when(columnRows.getString("table_name")).thenReturn("acct_master", "acct_master");
        when(columnRows.getString("column_name")).thenReturn("acct_no", "customer_name");
        when(columnRows.getString("column_comment")).thenReturn("账号", "客户名称");
        when(columnRows.getString("primary_key_attnums")).thenReturn("3 1", "3 1");
        when(columnRows.getInt("ordinal_position")).thenReturn(1, 2);

        Map<String, com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot> result =
                service.inspectTables(List.of(" ACCT_MASTER ", "removed_table", "acct_master"));

        assertThat(result).containsOnlyKeys("acct_master", "removed_table");
        assertThat(result.get("acct_master").tableExists()).isTrue();
        assertThat(result.get("acct_master").tableComment()).isEqualTo("账户主表");
        assertThat(result.get("acct_master").columns()).containsExactly(
                new ReplayBaseColumnOption("acct_no", "账号", 1, true, 2),
                new ReplayBaseColumnOption("customer_name", "客户名称", 2, false, null));
        assertThat(result.get("removed_table").tableExists()).isFalse();
        assertThat(result.get("removed_table").columns()).isEmpty();
        verify(connection, times(2)).prepareStatement(anyString());
        verify(tableStatement).setString(1, "base_schema");
        verify(tableStatement).setString(2, "acct_master");
        verify(tableStatement).setString(3, "removed_table");
        verify(columnStatement).setString(1, "base_schema");
        verify(columnStatement).setString(2, "acct_master");
    }

    @Test
    void inspectTablesDoesNotConvertColumnQueryFailureIntoMissingMetadata() throws Exception {
        PreparedStatement tableStatement = mock(PreparedStatement.class);
        PreparedStatement columnStatement = mock(PreparedStatement.class);
        ResultSet tableRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(tableStatement, columnStatement);
        when(tableStatement.executeQuery()).thenReturn(tableRows);
        when(tableRows.next()).thenReturn(true, false);
        when(tableRows.getString("table_name")).thenReturn("acct_master");
        when(tableRows.getString("table_comment")).thenReturn("账户主表");
        when(columnStatement.executeQuery()).thenThrow(new SQLException("secret jdbc failure"));

        assertThatThrownBy(() -> service.inspectTables(List.of("acct_master")))
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessage("BASE 母库未配置或暂不可用")
                .hasMessageNotContaining("secret");
    }

    @Test
    void requireTableWithColumnsUsesDedicatedExceptionWhenTableIsMissing() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);

        assertThatThrownBy(() -> service.requireTableWithColumns("removed_table", List.of("acct_no")))
                .isInstanceOf(ReplayBaseTableNotFoundException.class)
                .hasMessage("BASE 母库中不存在表：removed_table");
    }

    @Test
    void listColumnsUsesDedicatedExceptionWhenTableIsMissing() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);

        assertThatThrownBy(() -> service.listColumns("removed_table", null))
                .isInstanceOf(ReplayBaseTableNotFoundException.class)
                .hasMessage("BASE 母库中不存在表：removed_table");
    }

    @Test
    void connectionFailureIsSanitized() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException(
                "connection refused jdbc:opengauss://secret-host:5432/base user=reader"));

        assertThatThrownBy(() -> service.searchTables("账户", 20))
                .isInstanceOf(ReplayBaseDatabaseUnavailableException.class)
                .hasMessage("BASE 母库未配置或暂不可用")
                .hasMessageNotContaining("secret-host")
                .hasMessageNotContaining("reader");
    }
}
