package com.axonlink.ai.replay.dbcompare.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Allocates codes under the caller's save transaction; deleted configuration suffixes are never recycled. */
final class ReplayDatabaseComparisonCodeAllocator {
    private final JdbcTemplate jdbc;
    private final ReplayBaseMetadataService metadata;

    ReplayDatabaseComparisonCodeAllocator(JdbcTemplate jdbc, ReplayBaseMetadataService metadata) {
        this.jdbc = jdbc;
        this.metadata = metadata;
    }

    boolean hasSource(String schema, String source) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_db_compare_registration WHERE LOWER(schema_name)=? AND LOWER(COALESCE(NULLIF(source_table_name,''),table_name))=?", Long.class,
                normalize(schema), normalize(source)) > 0;
    }

    String allocate(String schemaName, String physicalTable, boolean allowAdditional) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("配置标识必须在登记事务中分配");
        }
        String schema = normalize(schemaName), source = normalize(physicalTable);
        jdbc.update("INSERT INTO dii_replay_db_compare_configuration_sequence(schema_name,source_table_name,last_suffix,base_reserved) VALUES(?,?,0,0) ON DUPLICATE KEY UPDATE schema_name=schema_name", schema, source);
        var state = jdbc.queryForMap("SELECT last_suffix,base_reserved FROM dii_replay_db_compare_configuration_sequence WHERE schema_name=? AND source_table_name=? FOR UPDATE", schema, source);
        long suffix = ((Number) state.get("last_suffix")).longValue();
        boolean baseReserved = ((Number) state.get("base_reserved")).intValue() != 0;
        List<String> priorCodes = jdbc.queryForList("SELECT table_name FROM dii_replay_db_compare_registration WHERE LOWER(schema_name)=? AND LOWER(COALESCE(NULLIF(source_table_name,''),table_name))=? UNION SELECT table_name FROM dii_replay_db_compare_version_table WHERE LOWER(schema_name)=? AND LOWER(COALESCE(NULLIF(source_table_name,''),table_name))=?", String.class, schema, source, schema, source);
        for (String prior : priorCodes) {
            String code = normalize(prior);
            baseReserved = true;
            if (!code.startsWith(source + "_")) continue;
            String number = code.substring(source.length() + 1);
            if (!number.matches("[0-9]+")) continue;
            try { suffix = Math.max(suffix, Long.parseLong(number)); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("配置后缀超出整数范围", exception); }
        }
        // The locked row is a current read even under MySQL REPEATABLE READ.
        // An earlier duplicate check may have used a snapshot before a concurrent first create committed.
        if (baseReserved && !allowAdditional) {
            throw new ReplayDatabaseComparisonGenerationException(HttpStatus.CONFLICT, "DUPLICATE_REGISTRATION",
                    "该母库表已有配置，请明确新增同表配置", Map.of("schema", schema, "tableName", source));
        }
        if (!baseReserved && !used(source, false)) {
            reserve(schema, source, suffix);
            return source;
        }
        while (suffix < Long.MAX_VALUE) {
            suffix++;
            String code = source + "_" + suffix;
            if (code.length() > 100) throw new IllegalArgumentException("固定配置标识不能超过100个字符，请调整来源表名");
            if (used(code, true)) continue;
            var physical = metadata.inspectTable(code);
            if (physical != null && physical.tableExists()) continue;
            reserve(schema, source, suffix);
            return code;
        }
        throw new IllegalArgumentException("配置后缀已耗尽");
    }

    private boolean used(String code, boolean includeLegacyIssues) {
        if (jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM dii_replay_db_compare_registration WHERE LOWER(table_name)=?) + (SELECT COUNT(*) FROM dii_replay_db_compare_version_table WHERE LOWER(table_name)=?) + (SELECT COUNT(*) FROM dii_replay_db_compare_audit_event WHERE LOWER(table_name)=?)", Long.class, code, code, code) > 0) return true;
        // A first registration for an old physical table retains its unbound issue history.
        if (!includeLegacyIssues) return false;
        for (String table : List.of("dii_replay_database_issue", "dii_replay_database_issue_table_state")) {
            if (tableExists(table) && jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE LOWER(table_name)=?", Long.class, code) > 0) return true;
        }
        return false;
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            var metadata = connection.getMetaData();
            String pattern = metadata.storesUpperCaseIdentifiers() ? tableName.toUpperCase(Locale.ROOT) : tableName;
            try (var tables = metadata.getTables(connection.getCatalog(), connection.getSchema(), pattern, null)) {
                while (tables.next()) if (tableName.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        }));
    }

    private void reserve(String schema, String source, long suffix) {
        jdbc.update("UPDATE dii_replay_db_compare_configuration_sequence SET last_suffix=?,base_reserved=1 WHERE schema_name=? AND source_table_name=?", suffix, schema, source);
    }

    private static String normalize(String value) { return value.strip().toLowerCase(Locale.ROOT); }
}
