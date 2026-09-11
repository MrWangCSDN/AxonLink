package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseTableOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseValidatedTable;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayBaseDataSourceRegistry;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReplayBaseMetadataService {

    private static final String SEARCH_TABLES_SQL = """
            WITH filter_values AS (SELECT ? AS schema_name, ? AS pattern)
            SELECT lower(c.relname) AS table_name,
                   obj_description(c.oid) AS table_comment
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
              CROSS JOIN filter_values f
             WHERE c.relkind IN ('r', 'p')
               AND n.nspname = f.schema_name
               AND (lower(c.relname) LIKE lower(f.pattern)
                    OR lower(COALESCE(obj_description(c.oid), '')) LIKE lower(f.pattern))
             ORDER BY lower(c.relname)
             LIMIT ?
            """;

    private static final String FIND_TABLE_SQL = """
            SELECT lower(c.relname) AS table_name,
                   obj_description(c.oid) AS table_comment
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relkind IN ('r', 'p')
               AND n.nspname = ?
               AND lower(c.relname) = lower(?)
             LIMIT 1
            """;

    private static final String LIST_COLUMNS_SQL = """
            WITH filter_values AS (SELECT ? AS schema_name, ? AS table_name, ? AS pattern)
            SELECT lower(a.attname) AS column_name,
                   col_description(c.oid, a.attnum) AS column_comment,
                   a.attnum AS ordinal_position
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              CROSS JOIN filter_values f
             WHERE n.nspname = f.schema_name
               AND lower(c.relname) = lower(f.table_name)
               AND a.attnum > 0
               AND NOT a.attisdropped
               AND (lower(a.attname) LIKE lower(f.pattern)
                    OR lower(COALESCE(col_description(c.oid, a.attnum), '')) LIKE lower(f.pattern))
             ORDER BY a.attnum
            """;

    private final ReplayBaseDataSourceRegistry registry;
    private final ReplayDatabaseComparisonProperties properties;

    public ReplayBaseMetadataService(
            ReplayBaseDataSourceRegistry registry,
            ReplayDatabaseComparisonProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public List<ReplayBaseTableOption> searchTables(String keyword, int limit) {
        String normalizedKeyword = normalizeSearchKeyword(keyword);
        int effectiveLimit = Math.max(1, Math.min(limit, 50));
        String pattern = "%" + normalizedKeyword + "%";
        List<ReplayBaseTableOption> result = new ArrayList<>();
        try (Connection connection = registry.requireDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(SEARCH_TABLES_SQL)) {
            statement.setString(1, schema());
            statement.setString(2, pattern);
            statement.setInt(3, effectiveLimit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new ReplayBaseTableOption(
                            normalizeIdentifier(rows.getString("table_name")),
                            rows.getString("table_comment")));
                }
            }
            return result;
        } catch (SQLException exception) {
            throw unavailable();
        }
    }

    public List<ReplayBaseColumnOption> listColumns(String tableName, String keyword) {
        String normalizedTable = requireIdentifier(tableName, "表英文名不能为空");
        String pattern = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return queryColumns(normalizedTable, pattern);
    }

    public ReplayBaseValidatedTable requireTableWithColumns(
            String tableName,
            Collection<String> fieldNames) {
        String normalizedTable = requireIdentifier(tableName, "表英文名不能为空");
        Set<String> requestedFields = new LinkedHashSet<>();
        if (fieldNames != null) {
            for (String fieldName : fieldNames) {
                requestedFields.add(requireIdentifier(fieldName, "字段英文名不能为空"));
            }
        }
        if (requestedFields.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个比对字段");
        }

        ReplayBaseTableOption table = requireTable(normalizedTable);
        List<ReplayBaseColumnOption> allColumns = queryColumns(normalizedTable, "%%");
        Map<String, ReplayBaseColumnOption> columnsByName = new LinkedHashMap<>();
        for (ReplayBaseColumnOption column : allColumns) {
            columnsByName.put(column.columnName(), column);
        }

        List<String> missingFields = requestedFields.stream()
                .filter(field -> !columnsByName.containsKey(field))
                .toList();
        if (!missingFields.isEmpty()) {
            throw new IllegalArgumentException("母库字段不存在：" + String.join("、", missingFields));
        }

        List<ReplayBaseColumnOption> selectedColumns = allColumns.stream()
                .filter(column -> requestedFields.contains(column.columnName()))
                .toList();
        return new ReplayBaseValidatedTable(
                schema(), table.tableName(), table.tableComment(), selectedColumns);
    }

    private ReplayBaseTableOption requireTable(String tableName) {
        try (Connection connection = registry.requireDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_TABLE_SQL)) {
            statement.setString(1, schema());
            statement.setString(2, tableName);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("母库表不存在：" + tableName);
                }
                return new ReplayBaseTableOption(
                        normalizeIdentifier(row.getString("table_name")),
                        row.getString("table_comment"));
            }
        } catch (SQLException exception) {
            throw unavailable();
        }
    }

    private List<ReplayBaseColumnOption> queryColumns(String tableName, String pattern) {
        List<ReplayBaseColumnOption> result = new ArrayList<>();
        try (Connection connection = registry.requireDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_COLUMNS_SQL)) {
            statement.setString(1, schema());
            statement.setString(2, tableName);
            statement.setString(3, pattern);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new ReplayBaseColumnOption(
                            normalizeIdentifier(rows.getString("column_name")),
                            rows.getString("column_comment"),
                            rows.getInt("ordinal_position")));
                }
            }
            return result;
        } catch (SQLException exception) {
            throw unavailable();
        }
    }

    private String normalizeSearchKeyword(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.length() < 2) {
            throw new IllegalArgumentException("表搜索关键字至少输入 2 个字符");
        }
        return normalized;
    }

    private String requireIdentifier(String value, String message) {
        String normalized = normalizeIdentifier(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String schema() {
        return properties.getBaseDatasource().getSchema().trim();
    }

    private ReplayBaseDatabaseUnavailableException unavailable() {
        return new ReplayBaseDatabaseUnavailableException("BASE 母库未配置或暂不可用");
    }
}
