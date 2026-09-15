package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseTableOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseValidatedTable;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayBaseDataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReplayBaseMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ReplayBaseMetadataService.class);

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
                   a.attnum AS ordinal_position,
                   pk.indkey::text AS primary_key_attnums
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              CROSS JOIN filter_values f
              LEFT JOIN pg_index pk ON pk.indrelid = c.oid AND pk.indisprimary
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
                            schema(), normalizeIdentifier(rows.getString("table_name")),
                            rows.getString("table_comment"), "UNREGISTERED", null, null));
                }
            }
            return result;
        } catch (SQLException exception) {
            throw unavailable("查询表元数据", exception);
        }
    }

    public List<ReplayBaseColumnOption> listColumns(String tableName, String keyword) {
        String normalizedTable = requireIdentifier(tableName, "表英文名不能为空");
        String pattern = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        try (Connection connection = registry.requireDataSource().getConnection()) {
            if (findTable(connection, normalizedTable) == null) {
                throw new ReplayBaseTableNotFoundException(normalizedTable);
            }
            return queryColumns(connection, normalizedTable, pattern);
        } catch (SQLException exception) {
            throw unavailable("查询字段元数据", exception);
        }
    }

    public Set<String> findExistingTableNames(Collection<String> tableNames) {
        LinkedHashSet<String> normalizedNames = new LinkedHashSet<>();
        if (tableNames != null) {
            tableNames.forEach(tableName -> {
                String normalized = normalizeIdentifier(tableName);
                if (!normalized.isEmpty()) {
                    normalizedNames.add(normalized);
                }
            });
        }
        if (normalizedNames.isEmpty()) {
            return Set.of();
        }

        String placeholders = String.join(",", Collections.nCopies(normalizedNames.size(), "?"));
        String sql = """
                SELECT lower(c.relname) AS table_name
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE c.relkind IN ('r', 'p')
                   AND n.nspname = ?
                   AND lower(c.relname) IN (%s)
                 ORDER BY lower(c.relname)
                """.formatted(placeholders);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        try (Connection connection = registry.requireDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema());
            int index = 2;
            for (String tableName : normalizedNames) {
                statement.setString(index++, tableName);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(normalizeIdentifier(rows.getString("table_name")));
                }
            }
            return Collections.unmodifiableSet(result);
        } catch (SQLException exception) {
            throw unavailable();
        }
    }

    public ReplayBaseMetadataSnapshot inspectTable(String tableName) {
        String normalizedTable = requireIdentifier(tableName, "表英文名不能为空");
        try (Connection connection = registry.requireDataSource().getConnection()) {
            ReplayBaseTableOption table = findTable(connection, normalizedTable);
            if (table == null) {
                return new ReplayBaseMetadataSnapshot(false, normalizedTable, null, List.of());
            }
            return new ReplayBaseMetadataSnapshot(
                    true, table.tableName(), table.tableComment(),
                    queryColumns(connection, normalizedTable, "%%"));
        } catch (SQLException exception) {
            throw unavailable();
        }
    }

    public Map<String, ReplayBaseMetadataSnapshot> inspectTables(Collection<String> tableNames) {
        LinkedHashSet<String> normalizedNames = normalizedIdentifiers(tableNames);
        if (normalizedNames.isEmpty()) {
            return Map.of();
        }

        String tablePlaceholders = String.join(",", Collections.nCopies(normalizedNames.size(), "?"));
        String tableSql = """
                SELECT lower(c.relname) AS table_name,
                       obj_description(c.oid) AS table_comment
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE c.relkind IN ('r', 'p')
                   AND n.nspname = ?
                   AND lower(c.relname) IN (%s)
                 ORDER BY lower(c.relname)
                """.formatted(tablePlaceholders);

        try (Connection connection = registry.requireDataSource().getConnection()) {
            Map<String, String> existingTables = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(tableSql)) {
                statement.setString(1, schema());
                int index = 2;
                for (String tableName : normalizedNames) {
                    statement.setString(index++, tableName);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        existingTables.put(
                                normalizeIdentifier(rows.getString("table_name")),
                                rows.getString("table_comment"));
                    }
                }
            }

            Map<String, List<ReplayBaseColumnOption>> columnsByTable = queryColumnsByTables(
                    connection, existingTables.keySet());
            LinkedHashMap<String, ReplayBaseMetadataSnapshot> result = new LinkedHashMap<>();
            for (String tableName : normalizedNames) {
                boolean exists = existingTables.containsKey(tableName);
                result.put(tableName, new ReplayBaseMetadataSnapshot(
                        exists,
                        tableName,
                        existingTables.get(tableName),
                        columnsByTable.getOrDefault(tableName, List.of())));
            }
            return Collections.unmodifiableMap(result);
        } catch (SQLException exception) {
            throw unavailable();
        }
    }

    public String schemaName() {
        return schema();
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

        ReplayBaseMetadataSnapshot snapshot = inspectTable(normalizedTable);
        if (!snapshot.tableExists()) {
            throw new ReplayBaseTableNotFoundException(normalizedTable);
        }
        List<ReplayBaseColumnOption> allColumns = snapshot.columns();
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
        List<ReplayBaseColumnOption> currentPrimaryKeys = allColumns.stream()
                .filter(ReplayBaseColumnOption::primaryKey)
                .sorted(Comparator.comparing(ReplayBaseColumnOption::primaryKeyOrder))
                .toList();
        return new ReplayBaseValidatedTable(
                schema(), snapshot.tableName(), snapshot.tableComment(),
                selectedColumns, currentPrimaryKeys);
    }

    private ReplayBaseTableOption findTable(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_TABLE_SQL)) {
            statement.setString(1, schema());
            statement.setString(2, tableName);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return new ReplayBaseTableOption(
                        normalizeIdentifier(row.getString("table_name")),
                        row.getString("table_comment"));
            }
        }
    }

    private List<ReplayBaseColumnOption> queryColumns(String tableName, String pattern) {
        try (Connection connection = registry.requireDataSource().getConnection()) {
            return queryColumns(connection, tableName, pattern);
        } catch (SQLException exception) {
            throw unavailable();
        }
    }

    private List<ReplayBaseColumnOption> queryColumns(
            Connection connection,
            String tableName,
            String pattern) throws SQLException {
        List<ReplayBaseColumnOption> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LIST_COLUMNS_SQL)) {
            statement.setString(1, schema());
            statement.setString(2, tableName);
            statement.setString(3, pattern);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int ordinalPosition = rows.getInt("ordinal_position");
                    Integer primaryKeyOrder = primaryKeyOrder(
                            rows.getString("primary_key_attnums"), ordinalPosition);
                    result.add(new ReplayBaseColumnOption(
                            normalizeIdentifier(rows.getString("column_name")),
                            rows.getString("column_comment"),
                            ordinalPosition,
                            primaryKeyOrder != null,
                            primaryKeyOrder));
                }
            }
            return result;
        }
    }

    private Map<String, List<ReplayBaseColumnOption>> queryColumnsByTables(
            Connection connection,
            Collection<String> tableNames) throws SQLException {
        if (tableNames.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(tableNames.size(), "?"));
        String sql = """
                SELECT lower(c.relname) AS table_name,
                       lower(a.attname) AS column_name,
                       col_description(c.oid, a.attnum) AS column_comment,
                       a.attnum AS ordinal_position,
                       pk.indkey::text AS primary_key_attnums
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  LEFT JOIN pg_index pk ON pk.indrelid = c.oid AND pk.indisprimary
                 WHERE n.nspname = ?
                   AND lower(c.relname) IN (%s)
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                 ORDER BY lower(c.relname), a.attnum
                """.formatted(placeholders);
        Map<String, List<ReplayBaseColumnOption>> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema());
            int index = 2;
            for (String tableName : tableNames) {
                statement.setString(index++, tableName);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String tableName = normalizeIdentifier(rows.getString("table_name"));
                    int ordinalPosition = rows.getInt("ordinal_position");
                    Integer primaryKeyOrder = primaryKeyOrder(
                            rows.getString("primary_key_attnums"), ordinalPosition);
                    result.computeIfAbsent(tableName, ignored -> new ArrayList<>()).add(
                            new ReplayBaseColumnOption(
                                    normalizeIdentifier(rows.getString("column_name")),
                                    rows.getString("column_comment"),
                                    ordinalPosition,
                                    primaryKeyOrder != null,
                                    primaryKeyOrder));
                }
            }
        }
        return result;
    }

    private LinkedHashSet<String> normalizedIdentifiers(Collection<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String identifier = normalizeIdentifier(value);
                if (!identifier.isEmpty()) {
                    normalized.add(identifier);
                }
            }
        }
        return normalized;
    }

    private String normalizeSearchKeyword(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.length() < 2) {
            throw new IllegalArgumentException("表搜索关键字至少输入 2 个字符");
        }
        return normalized;
    }

    private Integer primaryKeyOrder(String primaryKeyAttnums, int columnAttnum) {
        if (primaryKeyAttnums == null || primaryKeyAttnums.isBlank()) {
            return null;
        }
        String[] attnums = primaryKeyAttnums.trim().split("\\s+");
        for (int index = 0; index < attnums.length; index++) {
            try {
                if (Integer.parseInt(attnums[index]) == columnAttnum) {
                    return index + 1;
                }
            } catch (NumberFormatException ignored) {
                log.warn("[replay-db-compare] BASE 主键位置无法解析：{}", attnums[index]);
            }
        }
        return null;
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
        return properties.getBaseSchema().trim();
    }

    private ReplayBaseDatabaseUnavailableException unavailable() {
        return new ReplayBaseDatabaseUnavailableException("BASE 母库未配置或暂不可用");
    }

    private ReplayBaseDatabaseUnavailableException unavailable(String operation, SQLException exception) {
        log.error("[replay-db-compare] BASE {}失败，SQLState={}，vendorCode={}，exception={}",
                operation, exception.getSQLState(), exception.getErrorCode(),
                exception.getClass().getSimpleName());
        return unavailable();
    }
}
