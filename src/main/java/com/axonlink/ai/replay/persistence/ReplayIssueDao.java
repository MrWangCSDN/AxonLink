package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result-database access for the active parallel replay issue snapshot. */
@Repository
public class ReplayIssueDao {

    private static final int BATCH_SIZE = 2_000;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 200;

    private static final String INSERT_SQL = """
            INSERT INTO dii_replay_issue (
                source_sheet, group_name, is_sandbox, row_order,
                domain, sequence_no, batch_no, transaction_code, transaction_name, issue_level,
                registered_date, field_name, issue_description, transaction_owner, issue_type,
                initial_analysis, final_solution, resolved_date, cooperation_group, resolver,
                serial_no, data_repair_date, remark, affected_transaction_count, issue_id, issue_key,
                historical_occurrence_count, first_occurrence_date, last_occurrence_date, imported_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    public ReplayIssueDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.txTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    /** Atomically replaces the complete active snapshot. */
    public void replaceAll(List<ReplayIssueRow> rows, LocalDateTime importedAt) {
        txTemplate.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM dii_replay_issue");
            for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
                batchInsert(rows.subList(from, Math.min(from + BATCH_SIZE, rows.size())), importedAt);
            }
        });
    }

    public List<Map<String, Object>> list(ReplayIssueQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM dii_replay_issue WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, query);
        sql.append(" ORDER BY group_name, is_sandbox, row_order, id LIMIT ? OFFSET ?");
        args.add(clampLimit(query.limit()));
        args.add(Math.max(query.offset(), 0));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long count(ReplayIssueQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dii_replay_issue WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, query);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public ReplayIssueFilterOptions options() {
        return new ReplayIssueFilterOptions(
                jdbc.queryForList("SELECT DISTINCT group_name FROM dii_replay_issue "
                        + "WHERE TRIM(group_name) <> '' ORDER BY group_name", String.class),
                distinctNonBlank("issue_level"),
                distinctNonBlank("issue_type"));
    }

    public Map<String, Object> stats() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(DISTINCT group_name) AS group_count,
                       COALESCE(SUM(CASE WHEN is_sandbox = 1 THEN 1 ELSE 0 END), 0) AS sandbox_count,
                       MAX(imported_at) AS imported_at
                  FROM dii_replay_issue
                """);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", number(row.get("total")));
        stats.put("groupCount", number(row.get("group_count")));
        stats.put("sandboxCount", number(row.get("sandbox_count")));
        stats.put("importedAt", asLocalDateTime(row.get("imported_at")));
        return stats;
    }

    private void batchInsert(List<ReplayIssueRow> rows, LocalDateTime importedAt) {
        Timestamp timestamp = Timestamp.valueOf(importedAt);
        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ReplayIssueRow row = rows.get(index);
                statement.setString(1, row.sourceSheet());
                statement.setString(2, row.groupName());
                statement.setBoolean(3, row.sandbox());
                statement.setInt(4, row.rowOrder());
                statement.setString(5, row.domain());
                statement.setString(6, row.sequenceNo());
                statement.setString(7, row.batchNo());
                statement.setString(8, row.transactionCode());
                statement.setString(9, row.transactionName());
                statement.setString(10, row.issueLevel());
                statement.setString(11, row.registeredDate());
                statement.setString(12, row.fieldName());
                statement.setString(13, row.issueDescription());
                statement.setString(14, row.transactionOwner());
                statement.setString(15, row.issueType());
                statement.setString(16, row.initialAnalysis());
                statement.setString(17, row.finalSolution());
                statement.setString(18, row.resolvedDate());
                statement.setString(19, row.cooperationGroup());
                statement.setString(20, row.resolver());
                statement.setString(21, row.serialNo());
                statement.setString(22, row.dataRepairDate());
                statement.setString(23, row.remark());
                statement.setString(24, row.affectedTransactionCount());
                statement.setString(25, row.issueId());
                statement.setString(26, row.issueKey());
                statement.setString(27, row.historicalOccurrenceCount());
                statement.setString(28, row.firstOccurrenceDate());
                statement.setString(29, row.lastOccurrenceDate());
                statement.setTimestamp(30, timestamp);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, ReplayIssueQuery query) {
        if (hasText(query.groupName())) {
            sql.append(" AND group_name = ?");
            args.add(query.groupName().trim());
        }
        if (query.sandbox() != null) {
            sql.append(" AND is_sandbox = ?");
            args.add(query.sandbox());
        }
        if (hasText(query.issueLevel())) {
            sql.append(" AND issue_level = ?");
            args.add(query.issueLevel().trim());
        }
        if (hasText(query.issueType())) {
            sql.append(" AND issue_type = ?");
            args.add(query.issueType().trim());
        }
        if (hasText(query.keyword())) {
            sql.append(" AND (transaction_code LIKE ? OR transaction_name LIKE ? OR field_name LIKE ?"
                    + " OR issue_description LIKE ? OR serial_no LIKE ? OR issue_id LIKE ? OR issue_key LIKE ?)");
            String keyword = "%" + query.keyword().trim() + "%";
            for (int i = 0; i < 7; i++) {
                args.add(keyword);
            }
        }
    }

    private List<String> distinctNonBlank(String column) {
        return jdbc.queryForList("SELECT DISTINCT " + column + " FROM dii_replay_issue "
                + "WHERE " + column + " IS NOT NULL AND TRIM(" + column + ") <> '' ORDER BY " + column,
                String.class);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return value instanceof LocalDateTime dateTime ? dateTime : null;
    }
}
