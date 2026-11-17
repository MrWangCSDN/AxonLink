package com.axonlink.ai.replay.dbcompare.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.SQLCurrentTimeExpr;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitorAdapter;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareKeySelection;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlAnalysis;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayBaseDataSourceRegistry;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ReplayDatabaseComparisonCustomSqlAnalyzer {

    private static final int MAX_SQL_BYTES = 65_535;
    private static final int METADATA_TIMEOUT_SECONDS = 5;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_$]*");
    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> FUNCTIONS = Set.of(
            "SUM", "COUNT", "AVG", "MIN", "MAX", "COALESCE", "NULLIF", "NVL", "CAST", "ROUND", "ABS",
            "TRUNC", "CEIL", "CEILING", "FLOOR", "CONCAT", "SUBSTR", "SUBSTRING", "TRIM", "LTRIM",
            "RTRIM", "UPPER", "LOWER", "LENGTH", "CHAR_LENGTH", "TO_CHAR", "TO_DATE", "TO_TIMESTAMP",
            "DATE_TRUNC", "EXTRACT");
    private static final Set<String> CAST_TYPES = Set.of(
            "smallint", "int2", "integer", "int", "int4", "bigint", "int8", "decimal", "numeric",
            "real", "float", "float4", "float8", "double precision", "char", "character", "varchar",
            "character varying", "text", "boolean", "bool", "date", "time", "timestamp", "timestamptz",
            "timetz", "interval");
    private static final Set<String> SESSION_KEYWORDS = Set.of(
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP",
            "CURRENT_USER", "SESSION_USER", "SYSTEM_USER", "USER", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_CATALOG");
    private static final String TABLE_SQL = """
            SELECT obj_description(c.oid) AS table_comment
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')
            """;

    private final ReplayBaseDataSourceRegistry registry;
    private final ReplayDatabaseComparisonProperties properties;
    private final ReplayDatabaseComparisonScopeCompiler scopeCompiler =
            new ReplayDatabaseComparisonScopeCompiler(new ReplayDatabaseComparisonConditionCodec());

    public ReplayDatabaseComparisonCustomSqlAnalyzer(
            ReplayBaseDataSourceRegistry registry,
            ReplayDatabaseComparisonProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public ReplayDbCompareSqlAnalysis analyze(String sql) {
        ParsedSql parsed = parseSql(sql);
        String schema = properties.getBaseSchema();
        if (schema == null || !SCHEMA.matcher(schema.trim()).matches()) {
            throw new ReplayBaseDatabaseUnavailableException("BASE 母库配置无效");
        }
        schema = schema.trim();
        try (Connection connection = registry.requireDataSource().getConnection()) {
            connection.setReadOnly(true);
            connection.setSchema(schema);
            String comment = sourceTableComment(connection, schema, parsed.sourceTableName());
            List<ReplayDbCompareSqlColumn> columns = resultColumns(connection, parsed.sql());
            return new ReplayDbCompareSqlAnalysis(parsed.sql(), schema, parsed.sourceTableName(), comment,
                    columns, candidateKeys(parsed.query(), columns));
        } catch (SQLException exception) {
            String state = exception.getSQLState();
            if (state != null && (state.startsWith("22")
                    || state.startsWith("42") && !"42501".equals(state))) {
                throw new IllegalArgumentException("SQL 在 BASE 母库校验失败，请检查字段、分组和函数是否有效");
            }
            throw new ReplayBaseDatabaseUnavailableException("BASE 母库 SQL 解析暂不可用，请检查连接、权限或查询超时");
        }
    }

    public PreparedSql prepare(String sql, List<String> fieldNames,
                               List<String> resultKeyColumns, Long compareLimit) {
        scopeCompiler.compile(null, compareLimit, List.of());
        List<String> selected = selectedNames(fieldNames, "至少选择一个比对字段");
        List<String> keys = selectedNames(resultKeyColumns, "至少选择一个结果比对键");
        if (!selected.containsAll(keys)) {
            throw new IllegalArgumentException("结果比对键必须包含在所选比对字段中");
        }
        ReplayDbCompareSqlAnalysis analysis = analyze(sql);
        Map<String, ReplayDbCompareSqlColumn> columns = new LinkedHashMap<>();
        analysis.resultColumns().forEach(column -> columns.put(column.columnName(), column));
        List<ReplayDbCompareField> fields = new ArrayList<>();
        for (String name : selected) {
            ReplayDbCompareSqlColumn column = columns.get(name);
            if (column == null) {
                throw new IllegalArgumentException("SQL 结果字段不存在：" + name);
            }
            fields.add(new ReplayDbCompareField(name, column.columnLabel(), column.ordinalPosition(),
                    false, fields.size() + 1, null, true));
        }
        return new PreparedSql(analysis, fields,
                new ReplayDbCompareKeySelection("SQL_RESULT_KEY", "RESULT_KEY", keys));
    }

    static ParsedSql parseSql(String sql) {
        requireSqlSize(sql);
        List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(sql.trim(), DbType.postgresql);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("SQL 语法无法解析，请使用单表 SELECT 查询");
        }
        if (statements.size() != 1 || !(statements.get(0) instanceof SQLSelectStatement statement)) {
            throw new IllegalArgumentException("仅支持一条 SELECT 查询");
        }
        SQLSelect select = statement.getSelect();
        if (select.getWithSubQuery() != null || !(select.getQuery() instanceof SQLSelectQueryBlock query)) {
            throw new IllegalArgumentException("暂不支持 WITH、UNION 或子查询");
        }
        if (!(query.getFrom() instanceof SQLExprTableSource source)
                || !(source.getExpr() instanceof SQLIdentifierExpr identifier)
                || source.getSampling() != null || source.getPartitionSize() != 0) {
            throw new IllegalArgumentException("FROM 必须是不带 Schema 的单个基础表，暂不支持 JOIN 或子查询");
        }
        String name = SQLUtils.normalize(identifier.getName());
        String tableName = name.toLowerCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(tableName).matches() || tableName.length() > 256
                || identifier.getName().startsWith("\"") && !name.equals(tableName)) {
            throw new IllegalArgumentException("来源表必须使用普通小写表名");
        }
        validateClauses(select, query);
        statement.accept(new PGASTVisitorAdapter() {
            @Override
            public void preVisit(SQLObject object) {
                if (object instanceof SQLSelect nested && nested != select) {
                    throw new IllegalArgumentException("暂不支持子查询");
                }
                if (object instanceof SQLVariantRefExpr) {
                    throw new IllegalArgumentException("SQL 不支持参数占位符");
                }
                if (object instanceof SQLMethodInvokeExpr function
                        && (function.getOwner() != null
                        || !FUNCTIONS.contains(function.getMethodName().toUpperCase(Locale.ROOT)))) {
                    throw new IllegalArgumentException("SQL 包含未支持的函数");
                }
                if (object instanceof SQLAggregateExpr aggregate
                        && (aggregate.getOver() != null || aggregate.getOverRef() != null)) {
                    throw new IllegalArgumentException("暂不支持窗口函数");
                }
                if (object instanceof SQLCurrentTimeExpr
                        || object instanceof SQLIdentifierExpr identifier
                        && SESSION_KEYWORDS.contains(identifier.getName().toUpperCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("SQL 仅支持约定的纯内置函数");
                }
                if (object instanceof SQLCastExpr cast
                        && !CAST_TYPES.contains(cast.getDataType().getName().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("CAST 仅支持内置基础类型");
                }
            }
        });
        for (SQLSelectItem item : query.getSelectList()) {
            if (item.getAlias() == null && !(item.getExpr() instanceof SQLIdentifierExpr)
                    && !(item.getExpr() instanceof SQLPropertyExpr) && !(item.getExpr() instanceof SQLAllColumnExpr)) {
                throw new IllegalArgumentException("计算列和聚合列必须设置明确的英文别名");
            }
        }
        statement.setAfterSemi(false);
        String normalized = SQLUtils.toSQLString(statement, DbType.postgresql).trim();
        requireSqlSize(normalized);
        return new ParsedSql(normalized, tableName, query);
    }

    private static void validateClauses(SQLSelect select, SQLSelectQueryBlock query) {
        if (select.getLimit() != null || select.getOffset() != null || select.getRowCount() != null
                || query.getLimit() != null || query.getOffset() != null || query.getFirst() != null
                || query instanceof PGSelectQueryBlock pg && pg.getFetch() != null) {
            throw new IllegalArgumentException("请使用比对条数配置，SQL 中不支持 LIMIT、OFFSET 或 FETCH");
        }
        if (query.getInto() != null || query.isForUpdate() || query.isForShare()
                || query instanceof PGSelectQueryBlock pg && pg.getForClause() != null) {
            throw new IllegalArgumentException("SQL 不支持 INTO 或加锁查询");
        }
        if (query.getWindows() != null && !query.getWindows().isEmpty()
                || query.getQualify() != null || query.getStartWith() != null || query.getConnectBy() != null
                || query.getHintsSize() != 0) {
            throw new IllegalArgumentException("SQL 包含暂不支持的查询子句");
        }
    }

    private static void requireSqlSize(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("自定义 SQL 不能为空");
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            throw new IllegalArgumentException("自定义 SQL 不能超过 65535 字节");
        }
    }

    static String canonicalColumnName(String label) {
        if (label == null || label.length() > 100 || !IDENTIFIER.matcher(label.toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("SQL 结果字段别名必须是100字符以内的英文标识符");
        }
        return label.toLowerCase(Locale.ROOT);
    }

    private List<String> selectedNames(List<String> values, String emptyMessage) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String name = canonicalColumnName(value == null ? null : value.trim());
            if (!seen.add(name)) {
                throw new IllegalArgumentException("SQL 字段或结果键重复：" + name);
            }
            result.add(name);
        }
        return List.copyOf(result);
    }

    private String sourceTableComment(Connection connection, String schema, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TABLE_SQL)) {
            statement.setQueryTimeout(METADATA_TIMEOUT_SECONDS);
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("BASE 母库中不存在来源表：" + tableName);
                }
                return rows.getString("table_comment");
            }
        }
    }

    private List<ReplayDbCompareSqlColumn> resultColumns(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM (\n" + sql + "\n) result_q WHERE 1=0")) {
            statement.setQueryTimeout(METADATA_TIMEOUT_SECONDS);
            try (ResultSet rows = statement.executeQuery()) {
                ResultSetMetaData metadata = rows.getMetaData();
                List<ReplayDbCompareSqlColumn> columns = new ArrayList<>();
                Set<String> names = new HashSet<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String label = metadata.getColumnLabel(index);
                    String name = canonicalColumnName(label);
                    if (!names.add(name)) {
                        throw new IllegalArgumentException("SQL 结果字段别名重复：" + name);
                    }
                    columns.add(new ReplayDbCompareSqlColumn(name, label, metadata.getColumnTypeName(index), index));
                }
                if (columns.isEmpty()) {
                    throw new IllegalArgumentException("SQL 必须返回至少一个结果字段");
                }
                return List.copyOf(columns);
            }
        }
    }

    private List<String> candidateKeys(SQLSelectQueryBlock query, List<ReplayDbCompareSqlColumn> columns) {
        if (query.getGroupBy() == null || query.getSelectList().size() != columns.size()) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (var grouping : query.getGroupBy().getItems()) {
            int matched = -1;
            for (int index = 0; index < query.getSelectList().size(); index++) {
                SQLSelectItem item = query.getSelectItem(index);
                if (item.getExpr().toString().equalsIgnoreCase(grouping.toString())
                        || item.getAlias() != null && SQLUtils.normalize(item.getAlias()).equalsIgnoreCase(grouping.toString())) {
                    matched = index;
                    break;
                }
            }
            if (matched < 0) {
                return List.of();
            }
            keys.add(columns.get(matched).columnName());
        }
        return List.copyOf(keys);
    }

    record ParsedSql(String sql, String sourceTableName, SQLSelectQueryBlock query) {
    }

    public record PreparedSql(ReplayDbCompareSqlAnalysis analysis,
                              List<ReplayDbCompareField> fields,
                              ReplayDbCompareKeySelection comparisonKey) {
        public PreparedSql {
            fields = List.copyOf(fields);
        }
    }
}
