package com.axonlink.ai.replay.dbcompare;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.SQLException;
import java.util.Locale;

/** Adds current identity columns to historical test fixtures that intentionally load selected old DDLs. */
public final class ReplayDbCompareMultiConfigurationFixture {
    private ReplayDbCompareMultiConfigurationFixture() {}

    public static void install(JdbcTemplate jdbc) {
        for (String table : new String[]{"dii_replay_db_compare_registration", "dii_replay_db_compare_version_table"}) {
            if (!exists(jdbc, table, null)) continue;
            if (!exists(jdbc, table, "source_table_name")) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN source_table_name VARCHAR(256)");
            if (!exists(jdbc, table, "configuration_name")) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN configuration_name VARCHAR(200) NOT NULL DEFAULT ''");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS dii_replay_db_compare_configuration_sequence (schema_name VARCHAR(128) NOT NULL, source_table_name VARCHAR(256) NOT NULL, last_suffix BIGINT NOT NULL DEFAULT 0, base_reserved INT NOT NULL DEFAULT 0, PRIMARY KEY(schema_name,source_table_name))");
    }

    private static boolean exists(JdbcTemplate jdbc, String table, String column) {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            var metadata = connection.getMetaData();
            String tablePattern = metadata.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT) : table;
            if (column == null) {
                try (var rows = metadata.getTables(connection.getCatalog(), connection.getSchema(), tablePattern, new String[]{"TABLE"})) {
                    while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
                }
            } else {
                String pattern = metadata.storesUpperCaseIdentifiers() ? column.toUpperCase(Locale.ROOT) : column;
                try (var rows = metadata.getColumns(connection.getCatalog(), connection.getSchema(), tablePattern, pattern)) {
                    while (rows.next()) if (column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) return true;
                }
            }
            return false;
        }));
    }
}
