package com.axonlink.ai.replay.dbissue.report;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.sql.ResultSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reads batch inputs without loading the complete issue snapshot table into application memory. */
@Repository
public class ReplayDatabaseIssueReportDao {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTransaction;

    public ReplayDatabaseIssueReportDao(@Qualifier("diiResultJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        readTransaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        readTransaction.setReadOnly(true);
        readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public <T> T readConsistently(Supplier<T> work) {
        return readTransaction.execute(status -> work.get());
    }

    public List<String> listBatchNames() {
        return jdbc.queryForList("SELECT batch_no FROM dii_replay_database_issue_batch "
                + "ORDER BY batch_date DESC, imported_at DESC, batch_no DESC", String.class);
    }

    String latestBatchName() {
        var names = jdbc.queryForList("SELECT batch_no FROM dii_replay_database_issue_batch "
                + "ORDER BY batch_date DESC, imported_at DESC, batch_no DESC LIMIT 1", String.class);
        return names.isEmpty() ? null : names.get(0);
    }

    boolean batchExists(String batchNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_batch WHERE batch_no=?", Long.class, batchNo) > 0;
    }

    record VersionTable(String domain, long fieldCount, long matchingTables) {}

    record RegistrationSnapshot(String versionNo, Map<String, VersionTable> tables) {
        VersionTable table(String tableName) {
            VersionTable table = tables.get(tableName);
            if (table == null) throw new IllegalArgumentException("登记版本 " + versionNo + " 缺少表 " + tableName);
            if (table.matchingTables() != 1) {
                throw new IllegalArgumentException("登记版本 " + versionNo + " 存在同名多份表配置 " + tableName);
            }
            return table;
        }
    }

    RegistrationSnapshot registrationSnapshot(String batchNo) {
        List<Long> bindings = jdbc.query("SELECT registration_version_id FROM dii_replay_database_issue_batch WHERE batch_no=?",
                (rs, index) -> rs.getObject("registration_version_id", Long.class), batchNo);
        if (bindings.isEmpty() || bindings.get(0) == null) return null;
        long versionId = bindings.get(0);
        List<String> versions = jdbc.queryForList("SELECT version_no FROM dii_replay_db_compare_version WHERE id=?", String.class, versionId);
        if (versions.isEmpty()) throw new IllegalArgumentException("批次 " + batchNo + " 关联的登记版本不存在，请核对历史版本绑定");
        Map<String, VersionTable> tables = new HashMap<>();
        jdbc.query("""
                SELECT LOWER(t.table_name) AS table_name, MIN(t.domain_name) AS domain_name,
                       COUNT(DISTINCT t.id) AS matching_tables, COUNT(f.id) AS field_count
                FROM dii_replay_db_compare_version_table t
                LEFT JOIN dii_replay_db_compare_version_field f ON f.version_table_id=t.id
                WHERE t.version_id=?
                GROUP BY LOWER(t.table_name)
                """, (RowCallbackHandler) rs -> tables.put(rs.getString("table_name"),
                new VersionTable(rs.getString("domain_name"), rs.getLong("field_count"), rs.getLong("matching_tables"))), versionId);
        return new RegistrationSnapshot(versions.get(0), Map.copyOf(tables));
    }

    List<String> currentIssueTypes() {
        return jdbc.queryForList("SELECT DISTINCT TRIM(issue_type) FROM dii_replay_database_issue "
                + "WHERE issue_status<>'不参与比对' AND issue_type IS NOT NULL AND TRIM(issue_type)<>'' ORDER BY TRIM(issue_type)", String.class);
    }

    record ImportedIssue(long id, String tableName, String issueLevel, String issueType, String issueStatus, String domain, String rawSnapshot) {}

    void forEachImportedIssue(String batchNo, Consumer<ImportedIssue> consumer) {
        String sql = """
                WITH ranked_imports AS (
                    SELECT issue_id, incoming_snapshot,
                           ROW_NUMBER() OVER (PARTITION BY issue_id ORDER BY operation_at DESC, id DESC) AS rn
                    FROM dii_replay_database_issue_history
                    WHERE batch_no=? AND import_event=1
                )
                SELECT i.id, LOWER(i.table_name) AS table_name, i.issue_level, i.issue_type, i.issue_status,
                       p.domain_name,
                       CASE WHEN h.issue_id IS NULL THEN i.data_snapshot ELSE h.incoming_snapshot END AS raw_snapshot
                FROM dii_replay_database_issue i
                LEFT JOIN ranked_imports h ON h.issue_id=i.id AND h.rn=1
                LEFT JOIN dii_replay_database_table_person p ON p.table_name=LOWER(i.table_name)
                WHERE (h.issue_id IS NOT NULL AND h.incoming_snapshot IS NOT NULL)
                   OR (h.issue_id IS NULL AND i.batch_no=?)
                """;
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setString(1, batchNo);
            statement.setString(2, batchNo);
            // Connector/J requires this sentinel to stream a forward-only result rather than buffer all snapshots.
            boolean mysql = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
            statement.setFetchSize(mysql ? Integer.MIN_VALUE : 200);
            return statement;
        }, (RowCallbackHandler) rs -> consumer.accept(new ImportedIssue(
                rs.getLong("id"), rs.getString("table_name"), rs.getString("issue_level"),
                rs.getString("issue_type"), rs.getString("issue_status"), rs.getString("domain_name"), rs.getString("raw_snapshot"))));
    }

    /** Missing keys and explicit null values both mean the field total cannot be established. */
    Map<String, Long> configuredFieldCounts(Collection<String> tableNames) {
        Map<String, Long> result = new HashMap<>();
        List<String> tables = new ArrayList<>(tableNames);
        for (int start = 0; start < tables.size(); start += 200) {
            var batch = tables.subList(start, Math.min(tables.size(), start + 200));
            String sql = "SELECT LOWER(r.table_name) AS table_name, COUNT(DISTINCT r.id) AS registrations, COUNT(f.id) AS fields "
                    + "FROM dii_replay_db_compare_registration r LEFT JOIN dii_replay_db_compare_field f ON f.registration_id=r.id "
                    + "WHERE r.deleted=0 AND LOWER(r.table_name) IN (" + String.join(",", Collections.nCopies(batch.size(), "?")) + ") "
                    + "GROUP BY LOWER(r.table_name)";
            jdbc.query(sql, (RowCallbackHandler) rs -> result.put(rs.getString("table_name"),
                    rs.getLong("registrations") == 1 ? rs.getLong("fields") : null), batch.toArray());
        }
        return result;
    }
}
