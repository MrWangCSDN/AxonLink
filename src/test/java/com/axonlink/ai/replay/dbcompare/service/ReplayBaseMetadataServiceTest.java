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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        properties.getBaseDatasource().setSchema("base_schema");
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

        assertThat(result).containsExactly(new ReplayBaseTableOption("acct_info", "账户信息"));
        verify(statement).setString(1, "base_schema");
        verify(statement).setString(2, "%账户%");
        verify(statement).setInt(3, 50);
    }

    @Test
    void listColumnsMapsChineseCommentsAndOrdinalPositions() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString("column_name")).thenReturn("acct_id", "customer_name");
        when(rows.getString("column_comment")).thenReturn("账户编号", "客户名称");
        when(rows.getInt("ordinal_position")).thenReturn(1, 3);

        List<ReplayBaseColumnOption> result = service.listColumns("ACCT_INFO", "客户");

        assertThat(result).containsExactly(
                new ReplayBaseColumnOption("acct_id", "账户编号", 1),
                new ReplayBaseColumnOption("customer_name", "客户名称", 3));
        verify(statement).setString(1, "base_schema");
        verify(statement).setString(2, "acct_info");
        verify(statement).setString(3, "%客户%");
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
