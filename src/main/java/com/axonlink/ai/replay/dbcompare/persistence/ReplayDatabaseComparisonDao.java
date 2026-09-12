package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHistoryEntry;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListItem;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class ReplayDatabaseComparisonDao {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int FIELD_PREVIEW_SIZE = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public ReplayDatabaseComparisonDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public long insert(ReplayDbCompareRegistration registration) {
        Long result = transactionTemplate.execute(status -> {
            KeyHolder holder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO dii_replay_db_compare_registration
                        (schema_name,table_name,table_comment,domain_name,owner_emp_no,owner_name,group_name,
                         registered_date,remark,deleted,deleted_reason,deleted_by,deleted_at,version,
                         created_by,created_name,created_at,updated_by,updated_name,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, normalizeIdentifier(registration.schemaName()));
                statement.setString(2, normalizeIdentifier(registration.tableName()));
                statement.setString(3, registration.tableComment());
                statement.setString(4, registration.domainName());
                statement.setString(5, registration.ownerEmpNo());
                statement.setString(6, registration.ownerName());
                statement.setString(7, registration.groupName());
                statement.setDate(8, Date.valueOf(registration.registeredDate()));
                statement.setString(9, registration.remark());
                statement.setBoolean(10, registration.deleted());
                statement.setString(11, registration.deletedReason());
                statement.setString(12, registration.deletedBy());
                statement.setTimestamp(13, timestamp(registration.deletedAt()));
                statement.setLong(14, registration.version());
                statement.setString(15, registration.createdBy());
                statement.setString(16, registration.createdName());
                statement.setTimestamp(17, Timestamp.valueOf(registration.createdAt()));
                statement.setString(18, registration.updatedBy());
                statement.setString(19, registration.updatedName());
                statement.setTimestamp(20, Timestamp.valueOf(registration.updatedAt()));
                return statement;
            }, holder);
            Number key = holder.getKey();
            if (key == null) {
                throw new IllegalStateException("创建数据库比对字段登记失败");
            }
            long id = key.longValue();
            insertFields(id, registration.fields(), registration.createdAt());
            return id;
        });
        if (result == null) {
            throw new IllegalStateException("创建数据库比对字段登记失败");
        }
        return result;
    }

    public ReplayDbCompareListPage search(ReplayDbCompareQuery query) {
        ReplayDbCompareQuery effective = query == null ? ReplayDbCompareQuery.empty(0, 50) : query;
        int page = Math.max(0, effective.page());
        int size = Math.min(Math.max(1, effective.size()), MAX_PAGE_SIZE);
        SqlFilter filter = buildFilter(effective);
        Long totalValue = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_registration r" + filter.sql(),
                Long.class, filter.arguments().toArray());
        long total = totalValue == null ? 0 : totalValue;
        List<Object> pageArguments = new ArrayList<>(filter.arguments());
        pageArguments.add(size);
        pageArguments.add(page * size);
        List<ReplayDbCompareListItem> items = jdbc.query("""
                        SELECT r.id,r.schema_name,r.table_name,r.table_comment,r.domain_name,r.owner_emp_no,
                               r.owner_name,r.group_name,r.registered_date,r.version
                          FROM dii_replay_db_compare_registration r
                        """ + filter.sql() + " ORDER BY r.table_name,r.id LIMIT ? OFFSET ?",
                (row, rowNumber) -> new ReplayDbCompareListItem(
                        row.getLong("id"), row.getString("schema_name"), row.getString("table_name"),
                        row.getString("table_comment"), row.getString("domain_name"),
                        row.getString("owner_emp_no"), row.getString("owner_name"),
                        row.getString("group_name"), row.getDate("registered_date").toLocalDate(),
                        row.getLong("version"), 0, List.of()), pageArguments.toArray());
        return new ReplayDbCompareListPage(withFieldPreviews(items), page, size, total);
    }

    public ReplayDbCompareRegistration findById(long id) {
        List<ReplayDbCompareRegistration> rows = jdbc.query(
                "SELECT * FROM dii_replay_db_compare_registration WHERE id=?",
                (row, rowNumber) -> mapRegistration(row, List.of()), id);
        if (rows.isEmpty()) {
            return null;
        }
        ReplayDbCompareRegistration row = rows.get(0);
        return withFields(row, listFields(id));
    }

    public ReplayDbCompareRegistration findActiveBySchemaAndTable(String schemaName, String tableName) {
        List<Long> ids = jdbc.query("SELECT id FROM dii_replay_db_compare_registration "
                        + "WHERE schema_name=? AND table_name=? AND deleted=0 LIMIT 1",
                (row, rowNumber) -> row.getLong(1),
                normalizeIdentifier(schemaName), normalizeIdentifier(tableName));
        return ids.isEmpty() ? null : findById(ids.get(0));
    }

    public boolean updateRegistration(long id, long expectedVersion, String tableComment, String domainName,
                                      String ownerEmpNo, String ownerName, String groupName,
                                      java.time.LocalDate registeredDate, String remark,
                                      String updatedBy, String updatedName, LocalDateTime updatedAt) {
        return jdbc.update("""
                        UPDATE dii_replay_db_compare_registration
                           SET table_comment=?,domain_name=?,owner_emp_no=?,owner_name=?,group_name=?,registered_date=?,
                               remark=?,updated_by=?,updated_name=?,updated_at=?,version=version+1
                         WHERE id=? AND version=?
                        """,
                tableComment, domainName, ownerEmpNo, ownerName, groupName, Date.valueOf(registeredDate),
                remark, updatedBy, updatedName, Timestamp.valueOf(updatedAt), id, expectedVersion) == 1;
    }

    public boolean markDeleted(long id, long expectedVersion, String reason,
                               String deletedBy, String deletedName, LocalDateTime deletedAt) {
        return jdbc.update("""
                        UPDATE dii_replay_db_compare_registration
                           SET deleted=1,deleted_reason=?,deleted_by=?,deleted_at=?,updated_by=?,updated_name=?,updated_at=?,
                               version=version+1
                         WHERE id=? AND version=? AND deleted=0
                        """, reason, deletedBy, Timestamp.valueOf(deletedAt), deletedBy, deletedName,
                Timestamp.valueOf(deletedAt), id, expectedVersion) == 1;
    }

    public boolean restore(long id, long expectedVersion, String updatedBy,
                           String updatedName, LocalDateTime updatedAt) {
        return jdbc.update("""
                        UPDATE dii_replay_db_compare_registration
                           SET deleted=0,deleted_reason=NULL,deleted_by=NULL,deleted_at=NULL,
                               updated_by=?,updated_name=?,updated_at=?,version=version+1
                         WHERE id=? AND version=? AND deleted=1
                        """, updatedBy, updatedName, Timestamp.valueOf(updatedAt), id, expectedVersion) == 1;
    }

    public void replaceFields(long registrationId, List<ReplayDbCompareField> fields, LocalDateTime createdAt) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM dii_replay_db_compare_field WHERE registration_id=?", registrationId);
            insertFields(registrationId, fields, createdAt);
        });
    }

    public void insertHistory(long registrationId, String operation, String beforeSnapshot,
                              String afterSnapshot, String reason, String operatorEmpNo,
                              String operatorName, LocalDateTime operatedAt) {
        jdbc.update("""
                        INSERT INTO dii_replay_db_compare_history
                        (registration_id,operation,before_snapshot,after_snapshot,reason,
                         operator_emp_no,operator_name,operated_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, registrationId, operation, beforeSnapshot, afterSnapshot, reason,
                operatorEmpNo, operatorName, Timestamp.valueOf(operatedAt));
    }

    public List<ReplayDbCompareHistoryEntry> listHistory(long registrationId, int limit, int offset) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        int effectiveOffset = Math.max(offset, 0);
        return jdbc.query("SELECT * FROM dii_replay_db_compare_history WHERE registration_id=? "
                        + "ORDER BY operated_at DESC,id DESC LIMIT ? OFFSET ?",
                (row, rowNumber) -> new ReplayDbCompareHistoryEntry(
                        row.getLong("id"), row.getLong("registration_id"), row.getString("operation"),
                        row.getString("before_snapshot"), row.getString("after_snapshot"), row.getString("reason"),
                        row.getString("operator_emp_no"), row.getString("operator_name"),
                        row.getTimestamp("operated_at").toLocalDateTime()),
                registrationId, effectiveLimit, effectiveOffset);
    }

    private SqlFilter buildFilter(ReplayDbCompareQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE r.deleted=?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(query.deleted());
        if (hasText(query.tableKeyword())) {
            sql.append(" AND (LOWER(r.table_name) LIKE ? OR LOWER(COALESCE(r.table_comment,'')) LIKE ?)");
            String pattern = pattern(query.tableKeyword());
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (hasText(query.fieldKeyword())) {
            sql.append(" AND EXISTS (SELECT 1 FROM dii_replay_db_compare_field f WHERE f.registration_id=r.id")
                    .append(" AND (LOWER(f.column_name) LIKE ? OR LOWER(COALESCE(f.column_comment,'')) LIKE ?))");
            String pattern = pattern(query.fieldKeyword());
            arguments.add(pattern);
            arguments.add(pattern);
        }
        appendValues(sql, arguments, "r.domain_name", query.domains());
        appendValues(sql, arguments, "r.owner_emp_no", query.ownerEmpNos());
        appendValues(sql, arguments, "r.group_name", query.groups());
        if (query.registeredDateFrom() != null) {
            sql.append(" AND r.registered_date>=?");
            arguments.add(Date.valueOf(query.registeredDateFrom()));
        }
        if (query.registeredDateTo() != null) {
            sql.append(" AND r.registered_date<=?");
            arguments.add(Date.valueOf(query.registeredDateTo()));
        }
        return new SqlFilter(sql.toString(), arguments);
    }

    private void appendValues(StringBuilder sql, List<Object> arguments, String column, List<String> values) {
        List<String> normalized = values.stream().filter(this::hasText).map(String::trim).distinct().toList();
        if (normalized.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(normalized.size(), "?"))).append(")");
        arguments.addAll(normalized);
    }

    private List<ReplayDbCompareListItem> withFieldPreviews(List<ReplayDbCompareListItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, List<ReplayDbCompareField>> fieldsByRegistration = new LinkedHashMap<>();
        for (ReplayDbCompareListItem item : items) {
            fieldsByRegistration.put(item.id(), new ArrayList<>());
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(items.size(), "?"));
        jdbc.query("SELECT registration_id,column_name,column_comment,ordinal_position "
                        + "FROM dii_replay_db_compare_field WHERE registration_id IN (" + placeholders + ") "
                        + "ORDER BY registration_id,ordinal_position,id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> fieldsByRegistration
                        .get(row.getLong("registration_id"))
                        .add(new ReplayDbCompareField(row.getString("column_name"),
                                row.getString("column_comment"), row.getInt("ordinal_position"))),
                items.stream().map(ReplayDbCompareListItem::id).toArray());
        return items.stream().map(item -> {
            List<ReplayDbCompareField> fields = fieldsByRegistration.get(item.id());
            return new ReplayDbCompareListItem(item.id(), item.schemaName(), item.tableName(), item.tableComment(),
                    item.domainName(), item.ownerEmpNo(), item.ownerName(), item.groupName(), item.registeredDate(),
                    item.version(), fields.size(), fields.stream().limit(FIELD_PREVIEW_SIZE)
                    .map(ReplayDbCompareField::displayName).toList());
        }).toList();
    }

    private List<ReplayDbCompareField> listFields(long registrationId) {
        return jdbc.query("SELECT column_name,column_comment,ordinal_position FROM dii_replay_db_compare_field "
                        + "WHERE registration_id=? ORDER BY ordinal_position,id",
                (row, rowNumber) -> new ReplayDbCompareField(row.getString("column_name"),
                        row.getString("column_comment"), row.getInt("ordinal_position")), registrationId);
    }

    private void insertFields(long registrationId, List<ReplayDbCompareField> fields, LocalDateTime createdAt) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dii_replay_db_compare_field "
                        + "(registration_id,column_name,column_comment,ordinal_position,created_at) VALUES (?,?,?,?,?)",
                fields, 500, (statement, field) -> {
                    statement.setLong(1, registrationId);
                    statement.setString(2, normalizeIdentifier(field.columnName()));
                    statement.setString(3, field.columnComment());
                    statement.setInt(4, field.ordinalPosition());
                    statement.setTimestamp(5, Timestamp.valueOf(createdAt));
                });
    }

    private ReplayDbCompareRegistration mapRegistration(java.sql.ResultSet row,
                                                         List<ReplayDbCompareField> fields) throws java.sql.SQLException {
        return new ReplayDbCompareRegistration(
                row.getLong("id"), row.getString("schema_name"), row.getString("table_name"),
                row.getString("table_comment"), row.getString("domain_name"), row.getString("owner_emp_no"),
                row.getString("owner_name"), row.getString("group_name"), row.getDate("registered_date").toLocalDate(),
                row.getString("remark"), row.getBoolean("deleted"), row.getString("deleted_reason"),
                row.getString("deleted_by"), localDateTime(row.getTimestamp("deleted_at")), row.getLong("version"),
                row.getString("created_by"), row.getString("created_name"),
                row.getTimestamp("created_at").toLocalDateTime(), row.getString("updated_by"),
                row.getString("updated_name"), row.getTimestamp("updated_at").toLocalDateTime(), fields);
    }

    private ReplayDbCompareRegistration withFields(ReplayDbCompareRegistration row,
                                                   List<ReplayDbCompareField> fields) {
        return new ReplayDbCompareRegistration(row.id(), row.schemaName(), row.tableName(), row.tableComment(),
                row.domainName(), row.ownerEmpNo(), row.ownerName(), row.groupName(), row.registeredDate(),
                row.remark(), row.deleted(), row.deletedReason(), row.deletedBy(), row.deletedAt(), row.version(),
                row.createdBy(), row.createdName(), row.createdAt(), row.updatedBy(), row.updatedName(),
                row.updatedAt(), fields);
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String pattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private record SqlFilter(String sql, List<Object> arguments) {
    }
}
