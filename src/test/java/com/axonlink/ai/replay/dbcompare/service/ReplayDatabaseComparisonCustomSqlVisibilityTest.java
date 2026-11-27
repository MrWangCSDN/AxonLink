package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayBaseDataSourceRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReplayDatabaseComparisonCustomSqlVisibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"acct_main", "acct_filter"})
    void rejectsBaseRelationsShadowedByCatalogOrTemporaryRelationsBeforeResolvingOutput(String hiddenTable)
            throws Exception {
        var registry = mock(ReplayBaseDataSourceRegistry.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var tableStatement = mock(PreparedStatement.class);
        var tableRows = mock(ResultSet.class);
        var outputStatement = mock(PreparedStatement.class);
        var outputRows = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        var boundTable = new AtomicReference<String>();
        when(registry.requireDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call ->
                call.<String>getArgument(0).contains("pg_class") ? tableStatement : outputStatement);
        doAnswer(call -> {
            boundTable.set(call.getArgument(1));
            return null;
        }).when(tableStatement).setString(eq(2), anyString());
        when(tableStatement.executeQuery()).thenReturn(tableRows);
        when(tableRows.next()).thenReturn(true);
        when(tableRows.getBoolean("table_visible")).thenAnswer(call -> !hiddenTable.equals(boundTable.get()));
        when(outputStatement.executeQuery()).thenReturn(outputRows);
        when(outputRows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnTypeName(1)).thenReturn("int4");
        var properties = new ReplayDatabaseComparisonProperties();
        properties.setBaseSchema("base_schema");
        var metadataService = mock(ReplayBaseMetadataService.class);
        var analyzer = new ReplayDatabaseComparisonCustomSqlAnalyzer(registry, properties, metadataService);

        assertThatThrownBy(() -> analyzer.analyze("select a.id from acct_main a "
                + "where exists (select 1 from acct_filter f where f.id=a.id)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(hiddenTable).hasMessageContaining("遮蔽");
        verifyNoInteractions(outputStatement);
        verifyNoInteractions(metadataService);
        verify(connection).close();
    }
}
