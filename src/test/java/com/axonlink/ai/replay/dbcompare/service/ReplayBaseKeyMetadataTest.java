package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseKeyOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseValidatedTable;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayBaseDataSourceRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayBaseKeyMetadataTest {

    @Test
    void legacyConstructorsDerivePrimaryCandidateInPrimaryKeyOrder() {
        var keyB = new ReplayBaseColumnOption("key_b", null, "text", 1, true, 2);
        var regular = new ReplayBaseColumnOption("value", null, "text", 2, false, null);
        var keyA = new ReplayBaseColumnOption("key_a", null, "text", 3, true, 1);
        var columns = List.of(keyB, regular, keyA);
        var expected = new ReplayBaseKeyOption("PRIMARY", "PRIMARY_KEY", List.of("key_a", "key_b"));

        assertThat(new ReplayBaseMetadataSnapshot(true, "acct", null, columns).keyOptions())
                .containsExactly(expected);
        assertThat(new ReplayBaseValidatedTable("base", "acct", null, columns).keyOptions())
                .containsExactly(expected);
        assertThat(new ReplayBaseValidatedTable("base", "acct", null, List.of(regular),
                List.of(keyB, keyA)).keyOptions()).containsExactly(expected);
        assertThat(new ReplayBaseValidatedTable("base", "acct", null, List.of(regular),
                List.of(keyB, keyA), columns).keyOptions()).containsExactly(expected);
        assertThat(new ReplayBaseMetadataSnapshot(false, "absent", null, null).keyOptions()).isEmpty();
    }

    @Test
    void keyCandidateAndSnapshotDefensivelyCopyLists() {
        var names = new ArrayList<>(List.of("nullable_code"));
        var candidate = new ReplayBaseKeyOption("uq_nullable", "UNIQUE_INDEX", names);
        var candidates = new ArrayList<>(List.of(candidate));
        var snapshot = new ReplayBaseMetadataSnapshot(true, "acct", null, List.of(), candidates);
        names.clear();
        candidates.clear();

        assertThat(snapshot.keyOptions()).containsExactly(candidate);
        assertThat(candidate.columnNames()).containsExactly("nullable_code");
        assertThatThrownBy(() -> candidate.columnNames().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listKeysOrdersPrimaryFirstAndKeepsNullableCompositeUniqueColumnsWithoutInclude() throws Exception {
        var service = serviceWithIndexes(true, List.of(
                index("uq_z", false, "2", 1, 1),
                index("uq_a", false, "3 2 1", 2, 3),
                index("acct_pkey", true, "1", 1, 1),
                index("invalid_expression", false, "0 2", 2, 2),
                index("missing_column", false, "2 9", 2, 2)));

        assertThat(service.listKeys(" ACCT ")).containsExactly(
                new ReplayBaseKeyOption("acct_pkey", "PRIMARY_KEY", List.of("id")),
                new ReplayBaseKeyOption("uq_a", "UNIQUE_INDEX", List.of("nullable_code", "tenant_id")),
                new ReplayBaseKeyOption("uq_z", "UNIQUE_INDEX", List.of("tenant_id")));
    }

    @Test
    void oldPgCatalogWithoutIndnkeyattsUsesAllIndexAttributes() throws Exception {
        var service = serviceWithIndexes(false, List.of(index("uq_only", false, "3 2", 0, 2)));

        var snapshot = service.inspectTable("acct");

        assertThat(snapshot.keyOptions()).containsExactly(
                new ReplayBaseKeyOption("uq_only", "UNIQUE_INDEX", List.of("nullable_code", "tenant_id")));
        assertThat(snapshot.columns()).filteredOn(ReplayBaseColumnOption::primaryKey)
                .extracting(ReplayBaseColumnOption::columnName).containsExactly("id");
    }

    @Test
    void realCatalogResultsDoNotFabricateCandidateFromPhysicalPrimaryKeyFlags() throws Exception {
        var service = serviceWithIndexes(true, List.of());

        assertThat(service.listKeys("acct")).isEmpty();
        assertThat(service.inspectTable("acct").columns()).filteredOn(ReplayBaseColumnOption::primaryKey)
                .extracting(ReplayBaseColumnOption::columnName).containsExactly("id");
    }

    @Test
    void primaryKeyIncludeColumnsStayOrdinaryInSingleAndBatchSnapshots() throws Exception {
        var service = serviceWithIndexes(true, List.of(index("acct_pkey", true, "1 3", 1, 2)));
        var primary = new ReplayBaseKeyOption("acct_pkey", "PRIMARY_KEY", List.of("id"));

        var snapshot = service.inspectTable("acct");
        assertThat(snapshot.keyOptions()).containsExactly(primary);
        assertThat(snapshot.columns()).filteredOn(ReplayBaseColumnOption::primaryKey)
                .extracting(ReplayBaseColumnOption::columnName).containsExactly("id");
        assertThat(snapshot.columns()).filteredOn(column -> column.columnName().equals("nullable_code"))
                .allSatisfy(column -> {
                    assertThat(column.primaryKey()).isFalse();
                    assertThat(column.primaryKeyOrder()).isNull();
                });
        var validated = service.requireTableWithColumns("acct", List.of("id", "nullable_code"));
        assertThat(validated.currentPrimaryKeys()).extracting(ReplayBaseColumnOption::columnName)
                .containsExactly("id");
        assertThat(validated.keyOptions()).containsExactly(primary);
        var batch = service.inspectTables(List.of("acct")).get("acct");
        assertThat(batch.columns()).filteredOn(ReplayBaseColumnOption::primaryKey)
                .extracting(ReplayBaseColumnOption::columnName).containsExactly("id");
        assertThat(batch.keyOptions()).containsExactly(primary);
    }

    @Test
    void validatedAndBatchMetadataCarryCatalogCandidatesAndPhysicalPrimaryKeys() throws Exception {
        var service = serviceWithIndexes(true, List.of(index("uq_code", false, "3 2", 2, 2)));
        var expected = new ReplayBaseKeyOption("uq_code", "UNIQUE_INDEX", List.of("nullable_code", "tenant_id"));

        var validated = service.requireTableWithColumns("acct", List.of("tenant_id", "nullable_code"));
        assertThat(validated.keyOptions()).containsExactly(expected);
        assertThat(validated.currentPrimaryKeys()).extracting(ReplayBaseColumnOption::columnName)
                .containsExactly("id");
        assertThat(service.inspectTables(List.of("acct", "absent")).get("acct").keyOptions())
                .containsExactly(expected);
        assertThat(service.inspectTables(List.of("absent")).get("absent").keyOptions()).isEmpty();
    }

    private ReplayBaseMetadataService serviceWithIndexes(boolean modernPg, List<Map<String, Object>> indexes)
            throws Exception {
        var registry = mock(ReplayBaseDataSourceRegistry.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(registry.requireDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            var statement = mock(PreparedStatement.class);
            if (sql.contains("FROM pg_index i")) {
                // These predicates determine which catalog indexes can match every row in the table.
                assertThat(sql).contains("i.indisunique", "i.indisvalid", "i.indisready",
                                "i.indexprs IS NULL", "i.indpred IS NULL", "i.*")
                        .doesNotContain("i.indnkeyatts", "attnotnull");
                var rows = rows(indexes);
                var metadata = mock(ResultSetMetaData.class);
                when(metadata.getColumnCount()).thenReturn(modernPg ? 1 : 0);
                when(metadata.getColumnLabel(1)).thenReturn("indnkeyatts");
                when(rows.getMetaData()).thenReturn(metadata);
                when(statement.executeQuery()).thenReturn(rows);
            } else if (sql.contains("a.attname")) {
                // Constraint keys exclude covering attributes from physical primary-key metadata.
                assertThat(sql).contains("LEFT JOIN pg_constraint pk", "pk.contype = 'p'",
                                "array_to_string(pk.conkey, ' ') AS primary_key_attnums")
                        .doesNotContain("pk.indkey");
                var columnRows = rows(List.of(
                        column("id", 1), column("tenant_id", 2), column("nullable_code", 3)));
                when(statement.executeQuery()).thenReturn(columnRows);
            } else {
                var tableRows = rows(List.of(Map.of("table_name", "acct")));
                when(statement.executeQuery()).thenReturn(tableRows);
            }
            return statement;
        });
        var properties = new ReplayDatabaseComparisonProperties();
        properties.setBaseSchema("base");
        return new ReplayBaseMetadataService(registry, properties);
    }

    private Map<String, Object> column(String name, int position) {
        return Map.of("table_name", "acct", "column_name", name, "ordinal_position", position,
                "primary_key_attnums", "1", "data_type", "text");
    }

    private Map<String, Object> index(String name, boolean primary, String attnums, int keyCount, int totalCount) {
        return Map.of("table_name", "acct", "index_name", name, "indisprimary", primary,
                "index_attnums", attnums, "indnkeyatts", keyCount, "indnatts", totalCount);
    }

    private ResultSet rows(List<Map<String, Object>> values) throws Exception {
        var rows = mock(ResultSet.class);
        var index = new AtomicInteger(-1);
        when(rows.next()).thenAnswer(invocation -> index.incrementAndGet() < values.size());
        when(rows.getString(anyString())).thenAnswer(invocation -> {
            var value = values.get(index.get()).get(invocation.getArgument(0));
            return value == null ? null : value.toString();
        });
        when(rows.getInt(anyString())).thenAnswer(invocation -> {
            var value = values.get(index.get()).get(invocation.getArgument(0));
            return value == null ? 0 : ((Number) value).intValue();
        });
        when(rows.getBoolean(anyString())).thenAnswer(invocation ->
                Boolean.TRUE.equals(values.get(index.get()).get(invocation.getArgument(0))));
        return rows;
    }
}
