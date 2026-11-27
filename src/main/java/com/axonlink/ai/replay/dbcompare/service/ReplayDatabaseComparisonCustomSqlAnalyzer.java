package com.axonlink.ai.replay.dbcompare.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.SQLCurrentTimeExpr;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExprGroup;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLNotExpr;
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
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
            SELECT pg_catalog.obj_description(c.oid) AS table_comment,
                   pg_catalog.pg_table_is_visible(c.oid) AS table_visible
              FROM pg_catalog.pg_class c
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')
            """;

    private final ReplayBaseDataSourceRegistry registry;
    private final ReplayDatabaseComparisonProperties properties;
    private final ReplayBaseMetadataService metadataService;
    private final ReplayDatabaseComparisonScopeCompiler scopeCompiler =
            new ReplayDatabaseComparisonScopeCompiler(new ReplayDatabaseComparisonConditionCodec());

    public ReplayDatabaseComparisonCustomSqlAnalyzer(
            ReplayBaseDataSourceRegistry registry,
            ReplayDatabaseComparisonProperties properties,
            ReplayBaseMetadataService metadataService) {
        this.registry = registry;
        this.properties = properties;
        this.metadataService = metadataService;
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
            String comment = null;
            for (String table : parsed.referencedTableNames()) {
                String tableComment = sourceTableComment(connection, schema, table);
                if (table.equals(parsed.sourceTableName())) comment = tableComment;
            }
            ReplayBaseMetadataSnapshot source = metadataService.inspectTables(connection, schema,
                    List.of(parsed.sourceTableName()), METADATA_TIMEOUT_SECONDS).get(parsed.sourceTableName());
            if (source == null || !source.tableExists() || source.keyOptions().isEmpty()) {
                throw new IllegalArgumentException("来源表缺少有效主键或唯一索引：" + parsed.sourceTableName());
            }
            List<ReplayDbCompareSqlColumn> columns = resultColumns(connection, parsed.sql());
            var availableKeys = ReplayDatabaseComparisonCustomSqlKeys.availableKeys(columns, source.keyOptions());
            if (availableKeys.isEmpty()) {
                throw new IllegalArgumentException("SQL 结果字段未包含来源表任何一组完整主键或唯一索引，请修改 SQL 后重新解析");
            }
            return new ReplayDbCompareSqlAnalysis(parsed.sql(), schema, parsed.sourceTableName(), comment,
                    columns, availableKeys.get(0).columnNames(), availableKeys);
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
        if (!ReplayDatabaseComparisonCustomSqlKeys.matchesSourceKey(analysis.keyOptions(), keys)) {
            throw new IllegalArgumentException("结果比对键必须选择来源表一组完整主键或唯一索引字段");
        }
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
        String tableName = sourceTableName(query);
        validateClauses(select, query);
        Set<SQLSelect> allowedSubqueries = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> referencedTables = new LinkedHashSet<>();
        referencedTables.add(tableName);
        collectWhereExists(query.getWhere(), allowedSubqueries, referencedTables);
        statement.accept(new PGASTVisitorAdapter() {
            @Override
            public void preVisit(SQLObject object) {
                if (object instanceof SQLSelect nested && nested != select && !allowedSubqueries.contains(nested)) {
                    throw new IllegalArgumentException("仅支持外层 WHERE 中的单层 EXISTS/NOT EXISTS 子查询");
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
        return new ParsedSql(normalized, tableName, query, referencedTables);
    }

    private static String sourceTableName(SQLSelectQueryBlock query) {
        if (!(query.getFrom() instanceof SQLExprTableSource source)
                || !(source.getExpr() instanceof SQLIdentifierExpr identifier)
                || source.getSampling() != null || source.getPartitionSize() != 0) {
            throw new IllegalArgumentException("FROM 必须是不带 Schema 的单个基础表，暂不支持 JOIN 或 FROM 子查询");
        }
        String name = SQLUtils.normalize(identifier.getName());
        String table = name.toLowerCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(table).matches() || table.length() > 256
                || identifier.getName().startsWith("\"") && !name.equals(table)) {
            throw new IllegalArgumentException("来源表必须使用普通小写表名");
        }
        return table;
    }

    /** Only boolean WHERE leaves grant permission; structurally equal subqueries elsewhere must remain rejected. */
    private static void collectWhereExists(SQLExpr expression, Set<SQLSelect> allowed, Set<String> tables) {
        if (expression instanceof SQLExistsExpr exists) {
            SQLSelect nested = exists.getSubQuery();
            if (nested == null || nested.getWithSubQuery() != null
                    || !(nested.getQuery() instanceof SQLSelectQueryBlock query)) {
                throw new IllegalArgumentException("EXISTS 内层仅支持单个基础表 SELECT 查询");
            }
            validateClauses(nested, query);
            if (query.getDistionOption() != 0 || query.getGroupBy() != null
                    || query.getOrderBy() != null || nested.getOrderBy() != null
                    || query.getSelectList().size() != 1) {
                throw new IllegalArgumentException("EXISTS 内层仅支持 SELECT 1 或 SELECT *，不支持分组、排序或 DISTINCT");
            }
            SQLSelectItem projection = query.getSelectItem(0);
            boolean constantOne = projection.getExpr() instanceof SQLIntegerExpr integer
                    && "1".equals(integer.getNumber().toString());
            boolean bareStar = projection.getExpr() instanceof SQLAllColumnExpr star
                    && star.getOwner() == null
                    && (star.getExcept() == null || star.getExcept().isEmpty())
                    && (star.getReplace() == null || star.getReplace().isEmpty());
            if (projection.getAlias() != null || !(constantOne || bareStar)) {
                throw new IllegalArgumentException("EXISTS 内层仅支持无别名的 SELECT 1 或 SELECT *");
            }
            nested.accept(new PGASTVisitorAdapter() {
                @Override public void preVisit(SQLObject object) {
                    if (object instanceof SQLAggregateExpr) {
                        throw new IllegalArgumentException("EXISTS 内层暂不支持聚合函数");
                    }
                }
            });
            tables.add(sourceTableName(query));
            allowed.add(nested);
            // Do not grant permissions inside this subquery: deeper SELECT nodes fail the whole-tree validation.
        } else if (expression instanceof SQLBinaryOpExpr binary && booleanConnector(binary.getOperator())) {
            collectWhereExists(binary.getLeft(), allowed, tables);
            collectWhereExists(binary.getRight(), allowed, tables);
        } else if (expression instanceof SQLBinaryOpExprGroup group && booleanConnector(group.getOperator())) {
            for (SQLExpr item : group.getItems()) collectWhereExists(item, allowed, tables);
        } else if (expression instanceof SQLNotExpr not) {
            collectWhereExists(not.getExpr(), allowed, tables);
        }
    }

    private static boolean booleanConnector(SQLBinaryOperator operator) {
        return operator == SQLBinaryOperator.BooleanAnd || operator == SQLBinaryOperator.BooleanOr;
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
                if (!rows.getBoolean("table_visible")) {
                    throw new IllegalArgumentException("BASE 母库来源表被同名系统表或临时表遮蔽：" + tableName);
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

    record ParsedSql(String sql, String sourceTableName, SQLSelectQueryBlock query, Set<String> referencedTableNames) {
        ParsedSql {
            referencedTableNames = Collections.unmodifiableSet(new LinkedHashSet<>(referencedTableNames));
        }
    }

    public record PreparedSql(ReplayDbCompareSqlAnalysis analysis,
                              List<ReplayDbCompareField> fields,
                              ReplayDbCompareKeySelection comparisonKey) {
        public PreparedSql {
            fields = List.copyOf(fields);
        }
    }
}
