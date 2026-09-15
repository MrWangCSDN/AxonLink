package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetail;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditEvent;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditGroup;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditOperation;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareChangeType;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterResult;
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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class ReplayDatabaseComparisonDao {

    private static final int MAX_PAGE_SIZE = 200;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public ReplayDatabaseComparisonDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public long insertRegistration(ReplayDbCompareRegistration registration) {
        Long result = transactionTemplate.execute(status -> {
            long id = insertRegistrationRow(registration);
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
        SqlFilter filter = buildRegistrationFilter(effective);
        long total = count(filter);
        List<Object> arguments = new ArrayList<>(filter.arguments());
        arguments.add(size);
        arguments.add(page * size);
        List<ReplayDbCompareListItem> items = jdbc.query("""
                        SELECT r.id,r.schema_name,r.table_name,r.table_comment,r.domain_name,
                               r.reviser_emp_no,r.reviser_username,r.reviser_name,
                               r.group_owner_emp_no,r.group_owner_name,
                               r.registered_date,r.version
                          FROM dii_replay_db_compare_registration r
                        """ + filter.sql() + " ORDER BY r.table_name,r.id LIMIT ? OFFSET ?",
                (row, rowNumber) -> new ReplayDbCompareListItem(
                        row.getLong("id"), row.getString("schema_name"), row.getString("table_name"),
                        row.getString("table_comment"), row.getString("domain_name"),
                        row.getString("reviser_emp_no"), row.getString("reviser_username"),
                        row.getString("reviser_name"),
                        row.getString("group_owner_emp_no"), row.getString("group_owner_name"),
                        row.getDate("registered_date").toLocalDate(), row.getLong("version"), 0, List.of()),
                arguments.toArray());
        return new ReplayDbCompareListPage(withFieldPreviews(items), page, size, total);
    }

    public List<ReplayDbCompareListItem> findMetadataCandidates(ReplayDbCompareQuery query) {
        ReplayDbCompareQuery effective = query == null ? ReplayDbCompareQuery.empty(0, MAX_PAGE_SIZE) : query;
        SqlFilter filter = buildRegistrationFilter(effective);
        List<ReplayDbCompareListItem> items = jdbc.query("""
                        SELECT r.id,r.schema_name,r.table_name,r.table_comment,r.domain_name,
                               r.reviser_emp_no,r.reviser_username,r.reviser_name,
                               r.group_owner_emp_no,r.group_owner_name,
                               r.registered_date,r.version
                          FROM dii_replay_db_compare_registration r
                        """ + filter.sql() + " ORDER BY r.table_name,r.id",
                (row, rowNumber) -> new ReplayDbCompareListItem(
                        row.getLong("id"), row.getString("schema_name"), row.getString("table_name"),
                        row.getString("table_comment"), row.getString("domain_name"),
                        row.getString("reviser_emp_no"), row.getString("reviser_username"),
                        row.getString("reviser_name"), row.getString("group_owner_emp_no"),
                        row.getString("group_owner_name"), row.getDate("registered_date").toLocalDate(),
                        row.getLong("version"), 0, List.of()),
                filter.arguments().toArray());
        return withFieldPreviews(items);
    }

    public long count(ReplayDbCompareQuery query) {
        return count(buildRegistrationFilter(query == null ? ReplayDbCompareQuery.empty(0, 50) : query));
    }

    public List<ReplayDbCompareRegistration> findAllActiveWithFields() {
        List<ReplayDbCompareRegistration> registrations = jdbc.query(
                "SELECT * FROM dii_replay_db_compare_registration "
                        + "WHERE deleted=0 ORDER BY schema_name,table_name,id",
                (row, rowNumber) -> mapRegistration(row, List.of()));
        Map<Long, List<ReplayDbCompareField>> fields = findFieldsByRegistrationIds(
                registrations.stream().map(ReplayDbCompareRegistration::id).toList());
        return registrations.stream()
                .map(registration -> withFields(
                        registration, fields.getOrDefault(registration.id(), List.of())))
                .toList();
    }

    public ReplayDbCompareRegistration findByIdIncludingDeleted(long id) {
        List<ReplayDbCompareRegistration> rows = jdbc.query(
                "SELECT * FROM dii_replay_db_compare_registration WHERE id=?",
                (row, rowNumber) -> mapRegistration(row, List.of()), id);
        return rows.isEmpty() ? null : withFields(rows.get(0), findFields(id));
    }

    public ReplayDbCompareRegistration findBySchemaAndTable(String schemaName, String tableName) {
        List<Long> ids = jdbc.query("""
                        SELECT id FROM dii_replay_db_compare_registration
                         WHERE schema_name=? AND table_name=? LIMIT 1
                        """, (row, rowNumber) -> row.getLong(1),
                normalizeIdentifier(schemaName), normalizeIdentifier(tableName));
        return ids.isEmpty() ? null : findByIdIncludingDeleted(ids.get(0));
    }

    public List<ReplayDbCompareField> findFields(long registrationId) {
        return jdbc.query("""
                        SELECT column_name,column_comment,ordinal_position,primary_key,comparison_order
                          FROM dii_replay_db_compare_field
                         WHERE registration_id=?
                         ORDER BY comparison_order,id
                        """, (row, rowNumber) -> new ReplayDbCompareField(
                        row.getString("column_name"), row.getString("column_comment"),
                        row.getInt("ordinal_position"), row.getBoolean("primary_key"),
                        row.getInt("comparison_order")),
                registrationId);
    }

    public Map<Long, List<ReplayDbCompareField>> findFieldsByRegistrationIds(
            Collection<Long> registrationIds) {
        List<Long> ids = registrationIds == null ? List.of() : registrationIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .sorted()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<Long, List<ReplayDbCompareField>> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new ArrayList<>()));
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.query("""
                        SELECT registration_id,column_name,column_comment,ordinal_position,primary_key,comparison_order
                          FROM dii_replay_db_compare_field
                         WHERE registration_id IN (%s)
                         ORDER BY registration_id,comparison_order,id
                        """.formatted(placeholders),
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result
                        .get(row.getLong("registration_id"))
                        .add(new ReplayDbCompareField(
                                row.getString("column_name"), row.getString("column_comment"),
                                row.getInt("ordinal_position"), row.getBoolean("primary_key"),
                                row.getInt("comparison_order"))),
                ids.toArray());
        return Collections.unmodifiableMap(result);
    }

    public boolean updateRegistration(long id, long expectedVersion, String tableComment, String domainName,
                                      String groupOwnerEmpNo, String groupOwnerName, java.time.LocalDate registeredDate,
                                      String reviserEmpNo, String reviserName, LocalDateTime updatedAt) {
        return updateRegistration(id, expectedVersion, tableComment, domainName,
                groupOwnerEmpNo, groupOwnerName, registeredDate,
                reviserEmpNo, null, reviserName, updatedAt);
    }

    public boolean updateRegistration(long id, long expectedVersion, String tableComment, String domainName,
                                      String groupOwnerEmpNo, String groupOwnerName, java.time.LocalDate registeredDate,
                                      String reviserEmpNo, String reviserUsername, String reviserName,
                                      LocalDateTime updatedAt) {
        return updateRegistration(id, expectedVersion, tableComment, domainName,
                groupOwnerEmpNo, groupOwnerName, registeredDate,
                reviserEmpNo, reviserUsername, reviserName,
                reviserEmpNo, reviserName, updatedAt);
    }

    public boolean updateRegistration(long id, long expectedVersion, String tableComment, String domainName,
                                      String groupOwnerEmpNo, String groupOwnerName, java.time.LocalDate registeredDate,
                                      String reviserEmpNo, String reviserUsername, String reviserName,
                                      String updatedBy, String updatedName, LocalDateTime updatedAt) {
        return jdbc.update("""
                        UPDATE dii_replay_db_compare_registration
                           SET table_comment=?,domain_name=?,group_owner_emp_no=?,group_owner_name=?,
                               registered_date=?,reviser_emp_no=?,reviser_username=?,reviser_name=?,
                               updated_by=?,updated_name=?,updated_at=?,version=version+1
                         WHERE id=? AND version=? AND deleted=0
                        """, tableComment, domainName, groupOwnerEmpNo, groupOwnerName,
                Date.valueOf(registeredDate), reviserEmpNo, reviserUsername, reviserName,
                updatedBy, updatedName, Timestamp.valueOf(updatedAt),
                id, expectedVersion) == 1;
    }

    public boolean touchSystemUpdate(long id, long expectedVersion, LocalDateTime updatedAt) {
        return jdbc.update("""
                        UPDATE dii_replay_db_compare_registration
                           SET updated_by='SYSTEM',updated_name='系统',updated_at=?,version=version+1
                         WHERE id=? AND version=? AND deleted=0
                        """, Timestamp.valueOf(updatedAt), id, expectedVersion) == 1;
    }

    public boolean markDeleted(long id, long expectedVersion, String reason, String deletedBy,
                               String deletedName, LocalDateTime deletedAt) {
        return markDeleted(id, expectedVersion, reason, deletedBy, null, deletedName, deletedAt);
    }

    public boolean markDeleted(long id, long expectedVersion, String reason, String deletedBy,
                               String deletedUsername, String deletedName, LocalDateTime deletedAt) {
        return jdbc.update("""
                        UPDATE dii_replay_db_compare_registration
                           SET deleted=1,deleted_reason=?,deleted_by=?,deleted_at=?,
                               reviser_emp_no=?,reviser_username=?,reviser_name=?,updated_by=?,updated_name=?,updated_at=?,
                               version=version+1
                         WHERE id=? AND version=? AND deleted=0
                        """, reason, deletedBy, Timestamp.valueOf(deletedAt), deletedBy, deletedUsername, deletedName,
                deletedBy, deletedName, Timestamp.valueOf(deletedAt), id, expectedVersion) == 1;
    }

    public boolean reregisterRegistration(long id, long expectedVersion, String tableComment, String domainName,
                                          String groupOwnerEmpNo, String groupOwnerName,
                                          java.time.LocalDate registeredDate, String reviserEmpNo,
                                          String reviserName, LocalDateTime updatedAt) {
        return reregisterRegistration(id, expectedVersion, tableComment, domainName,
                groupOwnerEmpNo, groupOwnerName, registeredDate,
                reviserEmpNo, null, reviserName, updatedAt);
    }

    public boolean reregisterRegistration(long id, long expectedVersion, String tableComment, String domainName,
                                          String groupOwnerEmpNo, String groupOwnerName,
                                          java.time.LocalDate registeredDate, String reviserEmpNo,
                                          String reviserUsername, String reviserName, LocalDateTime updatedAt) {
        return jdbc.update("""
                        UPDATE dii_replay_db_compare_registration
                           SET table_comment=?,domain_name=?,group_owner_emp_no=?,group_owner_name=?,
                               registered_date=?,deleted=0,deleted_reason=NULL,deleted_by=NULL,deleted_at=NULL,
                               reviser_emp_no=?,reviser_username=?,reviser_name=?,updated_by=?,updated_name=?,updated_at=?,
                               version=version+1
                         WHERE id=? AND version=? AND deleted=1
                        """, tableComment, domainName, groupOwnerEmpNo, groupOwnerName, Date.valueOf(registeredDate),
                reviserEmpNo, reviserUsername, reviserName, reviserEmpNo, reviserName, Timestamp.valueOf(updatedAt),
                id, expectedVersion) == 1;
    }

    public void replaceFields(long registrationId, List<ReplayDbCompareField> fields, LocalDateTime createdAt) {
        transactionTemplate.executeWithoutResult(status -> {
            clearFields(registrationId);
            insertFields(registrationId, fields, createdAt);
        });
    }

    public void clearFields(long registrationId) {
        jdbc.update("DELETE FROM dii_replay_db_compare_field WHERE registration_id=?", registrationId);
    }

    public long insertAuditEvent(ReplayDbCompareAuditEvent event) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO dii_replay_db_compare_audit_event
                    (registration_id,schema_name,table_name,operation,registration_version,change_count,reason,
                     operator_emp_no,operator_username,operator_name,operated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, event.registrationId());
            statement.setString(2, normalizeIdentifier(event.schemaName()));
            statement.setString(3, normalizeIdentifier(event.tableName()));
            statement.setString(4, event.operation().name());
            statement.setLong(5, event.registrationVersion());
            statement.setInt(6, event.changeCount());
            statement.setString(7, event.reason());
            statement.setString(8, event.operatorEmpNo());
            statement.setString(9, event.operatorUsername());
            statement.setString(10, event.operatorName());
            statement.setTimestamp(11, Timestamp.valueOf(event.operatedAt()));
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建数据库比对字段审计事件失败");
        }
        return key.longValue();
    }

    public void insertAuditDetails(long auditEventId, List<ReplayDbCompareAuditDetail> details,
                                   LocalDateTime createdAt) {
        if (details == null || details.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                        INSERT INTO dii_replay_db_compare_audit_detail
                        (audit_event_id,detail_order,change_type,field_code,field_label,
                         before_value,after_value,created_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, details, 500, (statement, detail) -> {
                    statement.setLong(1, auditEventId);
                    statement.setInt(2, detail.detailOrder());
                    statement.setString(3, detail.changeType().name());
                    statement.setString(4, detail.fieldCode());
                    statement.setString(5, detail.fieldLabel());
                    statement.setString(6, detail.beforeValue());
                    statement.setString(7, detail.afterValue());
                    statement.setTimestamp(8, Timestamp.valueOf(createdAt));
                });
    }

    public ReplayDbCompareAuditPage searchAuditEvents(ReplayDbCompareAuditQuery query) {
        ReplayDbCompareAuditQuery effective = query == null ? ReplayDbCompareAuditQuery.empty(0, 50) : query;
        int page = Math.max(0, effective.page());
        int size = Math.min(Math.max(1, effective.size()), MAX_PAGE_SIZE);
        SqlFilter filter = buildAuditFilter(effective);
        Long totalValue = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_audit_event e" + filter.sql(),
                Long.class, filter.arguments().toArray());
        long total = totalValue == null ? 0 : totalValue;
        List<Object> arguments = new ArrayList<>(filter.arguments());
        arguments.add(size);
        arguments.add(page * size);
        List<ReplayDbCompareAuditEvent> items = jdbc.query("""
                        SELECT * FROM dii_replay_db_compare_audit_event e
                        """ + filter.sql() + " ORDER BY e.operated_at DESC,e.id DESC LIMIT ? OFFSET ?",
                (row, rowNumber) -> new ReplayDbCompareAuditEvent(
                        row.getLong("id"), row.getLong("registration_id"), row.getString("schema_name"),
                        row.getString("table_name"), ReplayDbCompareAuditOperation.valueOf(row.getString("operation")),
                        row.getLong("registration_version"), row.getInt("change_count"), row.getString("reason"),
                        row.getString("operator_emp_no"), row.getString("operator_username"),
                        row.getString("operator_name"),
                        row.getTimestamp("operated_at").toLocalDateTime()),
                arguments.toArray());
        return new ReplayDbCompareAuditPage(items, page, size, total);
    }

    public long countAuditGroups(ReplayDbCompareAuditQuery query) {
        ReplayDbCompareAuditQuery effective = query == null ? ReplayDbCompareAuditQuery.empty(0, 20) : query;
        SqlFilter filter = buildGroupedAuditFilter(effective);
        Long value = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM (
                            SELECT e.schema_name,e.table_name
                              FROM dii_replay_db_compare_audit_event e
                              LEFT JOIN dii_replay_db_compare_registration r ON r.id=e.registration_id
                        """ + filter.sql() + " GROUP BY e.schema_name,e.table_name) grouped_audits",
                Long.class, filter.arguments().toArray());
        return value == null ? 0 : value;
    }

    public List<ReplayDbCompareAuditGroup> findAuditGroupPage(ReplayDbCompareAuditQuery query) {
        ReplayDbCompareAuditQuery effective = query == null ? ReplayDbCompareAuditQuery.empty(0, 20) : query;
        int page = Math.max(0, effective.page());
        int size = Math.min(Math.max(1, effective.size()), MAX_PAGE_SIZE);
        SqlFilter filter = buildGroupedAuditFilter(effective);
        List<Object> arguments = new ArrayList<>(filter.arguments());
        arguments.add(size);
        arguments.add(page * size);
        return jdbc.query("""
                        SELECT e.schema_name,e.table_name,
                               MAX(COALESCE(r.table_comment,'')) AS table_comment,
                               COUNT(*) AS matched_event_count,
                               MAX(e.operated_at) AS latest_operated_at,
                               MAX(e.id) AS latest_event_id
                          FROM dii_replay_db_compare_audit_event e
                          LEFT JOIN dii_replay_db_compare_registration r ON r.id=e.registration_id
                        """ + filter.sql() + """
                         GROUP BY e.schema_name,e.table_name
                         ORDER BY latest_operated_at DESC,latest_event_id DESC
                         LIMIT ? OFFSET ?
                        """, (row, rowNumber) -> new ReplayDbCompareAuditGroup(
                        row.getString("schema_name"), row.getString("table_name"), row.getString("table_comment"),
                        row.getLong("matched_event_count"), null, null, null,
                        row.getTimestamp("latest_operated_at").toLocalDateTime(), List.of()),
                arguments.toArray());
    }

    public List<ReplayDbCompareAuditEvent> findAuditEventsForGroups(
            ReplayDbCompareAuditQuery query,
            List<ReplayDbCompareAuditGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        ReplayDbCompareAuditQuery effective = query == null ? ReplayDbCompareAuditQuery.empty(0, 20) : query;
        SqlFilter filter = buildGroupedAuditFilter(effective);
        StringBuilder sql = new StringBuilder("""
                SELECT e.* FROM dii_replay_db_compare_audit_event e
                LEFT JOIN dii_replay_db_compare_registration r ON r.id=e.registration_id
                """).append(filter.sql()).append(" AND (");
        List<Object> arguments = new ArrayList<>(filter.arguments());
        for (int index = 0; index < groups.size(); index++) {
            if (index > 0) {
                sql.append(" OR ");
            }
            sql.append("(e.schema_name=? AND e.table_name=?)");
            arguments.add(groups.get(index).schemaName());
            arguments.add(groups.get(index).tableName());
        }
        sql.append(") ORDER BY e.operated_at DESC,e.id DESC");
        return jdbc.query(sql.toString(), (row, rowNumber) -> new ReplayDbCompareAuditEvent(
                        row.getLong("id"), row.getLong("registration_id"), row.getString("schema_name"),
                        row.getString("table_name"), ReplayDbCompareAuditOperation.valueOf(row.getString("operation")),
                        row.getLong("registration_version"), row.getInt("change_count"), row.getString("reason"),
                        row.getString("operator_emp_no"), row.getString("operator_username"),
                        row.getString("operator_name"),
                        row.getTimestamp("operated_at").toLocalDateTime()),
                arguments.toArray());
    }

    public long countAuditEvents(ReplayDbCompareAuditQuery query) {
        SqlFilter filter = buildAuditFilter(query == null ? ReplayDbCompareAuditQuery.empty(0, 50) : query);
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_audit_event e" + filter.sql(),
                Long.class, filter.arguments().toArray());
        return value == null ? 0 : value;
    }

    public ReplayDbCompareHeaderFilterResult headerFilterOptions(ReplayDbCompareHeaderFilterRequest request) {
        HeaderColumn column = HeaderColumn.from(request.targetColumn());
        SqlFilter filter = buildRegistrationFilter(request.query(), column.queryKey());
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(column.valueExpression()).append(" AS option_value,")
                .append(column.labelExpression()).append(" AS option_label,")
                .append("COUNT(DISTINCT r.id) AS option_count FROM dii_replay_db_compare_registration r ");
        if (column.fieldColumn()) {
            sql.append("JOIN dii_replay_db_compare_field hf ON hf.registration_id=r.id ");
        }
        sql.append(filter.sql());
        List<Object> arguments = new ArrayList<>(filter.arguments());
        if (hasText(request.keyword())) {
            sql.append(" AND LOWER(").append(column.searchExpression()).append(") LIKE ?");
            arguments.add(pattern(request.keyword()));
        }
        sql.append(" GROUP BY ").append(column.valueExpression()).append(',').append(column.labelExpression())
                .append(" ORDER BY option_count DESC,option_value");
        List<ReplayDbCompareHeaderFilterOption> all = jdbc.query(sql.toString(),
                (row, rowNumber) -> new ReplayDbCompareHeaderFilterOption(
                        row.getString("option_value"), row.getString("option_label"), row.getLong("option_count")),
                arguments.toArray());
        int limit = request.effectiveLimit();
        List<ReplayDbCompareHeaderFilterOption> options = all.stream().limit(limit).toList();
        return new ReplayDbCompareHeaderFilterResult(
                options, all.size(), count(filter), all.size() > options.size());
    }

    public List<ReplayDbCompareAuditDetail> findAuditDetails(long auditEventId) {
        return jdbc.query("""
                        SELECT * FROM dii_replay_db_compare_audit_detail
                         WHERE audit_event_id=?
                         ORDER BY detail_order,id
                        """, (row, rowNumber) -> new ReplayDbCompareAuditDetail(
                        row.getLong("id"), row.getLong("audit_event_id"), row.getInt("detail_order"),
                        ReplayDbCompareChangeType.valueOf(row.getString("change_type")),
                        row.getString("field_code"), row.getString("field_label"),
                        row.getString("before_value"), row.getString("after_value"),
                        row.getTimestamp("created_at").toLocalDateTime()),
                auditEventId);
    }

    private long insertRegistrationRow(ReplayDbCompareRegistration registration) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO dii_replay_db_compare_registration
                    (schema_name,table_name,table_comment,domain_name,reviser_emp_no,reviser_username,reviser_name,
                     group_owner_emp_no,group_owner_name,registered_date,deleted,deleted_reason,deleted_by,deleted_at,
                     version,created_by,created_name,created_at,updated_by,updated_name,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, normalizeIdentifier(registration.schemaName()));
            statement.setString(2, normalizeIdentifier(registration.tableName()));
            statement.setString(3, registration.tableComment());
            statement.setString(4, registration.domainName());
            statement.setString(5, registration.reviserEmpNo());
            statement.setString(6, registration.reviserUsername());
            statement.setString(7, registration.reviserName());
            statement.setString(8, registration.groupOwnerEmpNo());
            statement.setString(9, registration.groupOwnerName());
            statement.setDate(10, Date.valueOf(registration.registeredDate()));
            statement.setBoolean(11, registration.deleted());
            statement.setString(12, registration.deletedReason());
            statement.setString(13, registration.deletedBy());
            statement.setTimestamp(14, timestamp(registration.deletedAt()));
            statement.setLong(15, registration.version());
            statement.setString(16, registration.createdBy());
            statement.setString(17, registration.createdName());
            statement.setTimestamp(18, Timestamp.valueOf(registration.createdAt()));
            statement.setString(19, registration.updatedBy());
            statement.setString(20, registration.updatedName());
            statement.setTimestamp(21, Timestamp.valueOf(registration.updatedAt()));
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建数据库比对字段登记失败");
        }
        return key.longValue();
    }

    private SqlFilter buildRegistrationFilter(ReplayDbCompareQuery query) {
        return buildRegistrationFilter(query, null);
    }

    private SqlFilter buildRegistrationFilter(ReplayDbCompareQuery query, String excludedKey) {
        StringBuilder sql = new StringBuilder(" WHERE r.deleted=0");
        List<Object> arguments = new ArrayList<>();
        if (!"tableName".equals(excludedKey) && hasText(query.tableKeyword())) {
            sql.append(" AND (LOWER(r.table_name) LIKE ? OR LOWER(COALESCE(r.table_comment,'')) LIKE ?)");
            String pattern = pattern(query.tableKeyword());
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (!"fieldName".equals(excludedKey) && hasText(query.fieldKeyword())) {
            sql.append(" AND EXISTS (SELECT 1 FROM dii_replay_db_compare_field f WHERE f.registration_id=r.id")
                    .append(" AND (LOWER(f.column_name) LIKE ? OR LOWER(COALESCE(f.column_comment,'')) LIKE ?))");
            String pattern = pattern(query.fieldKeyword());
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (!"domainName".equals(excludedKey)) appendValues(sql, arguments, "r.domain_name", query.domains());
        if (!"reviser".equals(excludedKey)) appendValues(sql, arguments, "r.reviser_emp_no", query.reviserEmpNos());
        if (!"groupOwner".equals(excludedKey)) appendValues(sql, arguments, "r.group_owner_emp_no", query.groupOwnerEmpNos());
        if (!"registeredDate".equals(excludedKey) && query.registeredDateFrom() != null) {
            sql.append(" AND r.registered_date>=?");
            arguments.add(Date.valueOf(query.registeredDateFrom()));
        }
        if (!"registeredDate".equals(excludedKey) && query.registeredDateTo() != null) {
            sql.append(" AND r.registered_date<=?");
            arguments.add(Date.valueOf(query.registeredDateTo()));
        }
        return new SqlFilter(sql.toString(), arguments);
    }

    private SqlFilter buildAuditFilter(ReplayDbCompareAuditQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> arguments = new ArrayList<>();
        if (query.registrationId() != null) {
            sql.append(" AND e.registration_id=?");
            arguments.add(query.registrationId());
        }
        if (hasText(query.tableKeyword())) {
            sql.append(" AND LOWER(e.table_name) LIKE ?");
            arguments.add(pattern(query.tableKeyword()));
        }
        if (hasText(query.operatorKeyword())) {
            sql.append(" AND (LOWER(e.operator_emp_no) LIKE ? OR LOWER(COALESCE(e.operator_username,'')) LIKE ? "
                    + "OR LOWER(COALESCE(e.operator_name,'')) LIKE ?)");
            String operatorPattern = pattern(query.operatorKeyword());
            arguments.add(operatorPattern);
            arguments.add(operatorPattern);
            arguments.add(operatorPattern);
        }
        appendValues(sql, arguments, "e.operator_emp_no", query.operatorEmpNos());
        List<String> operations = query.operations().stream().map(Enum::name).toList();
        appendValues(sql, arguments, "e.operation", operations);
        if (query.operatedFrom() != null) {
            sql.append(" AND e.operated_at>=?");
            arguments.add(Timestamp.valueOf(query.operatedFrom()));
        }
        if (query.operatedTo() != null) {
            sql.append(" AND e.operated_at<=?");
            arguments.add(Timestamp.valueOf(query.operatedTo()));
        }
        return new SqlFilter(sql.toString(), arguments);
    }

    private SqlFilter buildGroupedAuditFilter(ReplayDbCompareAuditQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> arguments = new ArrayList<>();
        if (query.registrationId() != null) {
            sql.append(" AND e.registration_id=?");
            arguments.add(query.registrationId());
        }
        if (hasText(query.tableKeyword())) {
            sql.append(" AND (LOWER(e.table_name) LIKE ? OR LOWER(COALESCE(r.table_comment,'')) LIKE ?)");
            String tablePattern = pattern(query.tableKeyword());
            arguments.add(tablePattern);
            arguments.add(tablePattern);
        }
        if (hasText(query.operatorKeyword())) {
            sql.append(" AND (LOWER(e.operator_emp_no) LIKE ? OR LOWER(COALESCE(e.operator_username,'')) LIKE ? "
                    + "OR LOWER(COALESCE(e.operator_name,'')) LIKE ?)");
            String operatorPattern = pattern(query.operatorKeyword());
            arguments.add(operatorPattern);
            arguments.add(operatorPattern);
            arguments.add(operatorPattern);
        }
        appendValues(sql, arguments, "e.operator_emp_no", query.operatorEmpNos());
        appendValues(sql, arguments, "e.operation", query.operations().stream().map(Enum::name).toList());
        if (query.operatedFrom() != null) {
            sql.append(" AND e.operated_at>=?");
            arguments.add(Timestamp.valueOf(query.operatedFrom()));
        }
        if (query.operatedTo() != null) {
            sql.append(" AND e.operated_at<=?");
            arguments.add(Timestamp.valueOf(query.operatedTo()));
        }
        return new SqlFilter(sql.toString(), arguments);
    }

    private long count(SqlFilter filter) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_db_compare_registration r" + filter.sql(),
                Long.class, filter.arguments().toArray());
        return value == null ? 0 : value;
    }

    private void appendValues(StringBuilder sql, List<Object> arguments, String column, List<String> values) {
        List<String> normalized = values.stream().filter(this::hasText).map(String::trim).distinct().toList();
        if (normalized.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", Collections.nCopies(normalized.size(), "?"))).append(")");
        arguments.addAll(normalized);
    }

    private List<ReplayDbCompareListItem> withFieldPreviews(List<ReplayDbCompareListItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, List<ReplayDbCompareField>> fieldsByRegistration = findFieldsByRegistrationIds(
                items.stream().map(ReplayDbCompareListItem::id).toList());
        return items.stream().map(item -> {
            List<ReplayDbCompareField> fields = fieldsByRegistration.get(item.id());
            return new ReplayDbCompareListItem(
                    item.id(), item.schemaName(), item.tableName(), item.tableComment(), item.domainName(),
                    item.reviserEmpNo(), item.reviserUsername(), item.reviserName(),
                    item.groupOwnerEmpNo(), item.groupOwnerName(),
                    item.registeredDate(), item.version(), fields.size(),
                    fields.stream().map(ReplayDbCompareField::displayName).toList());
        }).toList();
    }

    private void insertFields(long registrationId, List<ReplayDbCompareField> fields, LocalDateTime createdAt) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                        INSERT INTO dii_replay_db_compare_field
                        (registration_id,column_name,column_comment,ordinal_position,primary_key,comparison_order,created_at)
                        VALUES (?,?,?,?,?,?,?)
                        """, fields, 500, (statement, field) -> {
                    statement.setLong(1, registrationId);
                    statement.setString(2, normalizeIdentifier(field.columnName()));
                    statement.setString(3, field.columnComment());
                    statement.setInt(4, field.ordinalPosition());
                    statement.setBoolean(5, field.primaryKey());
                    statement.setInt(6, field.comparisonOrder());
                    statement.setTimestamp(7, Timestamp.valueOf(createdAt));
                });
    }

    private ReplayDbCompareRegistration mapRegistration(java.sql.ResultSet row,
                                                         List<ReplayDbCompareField> fields) throws java.sql.SQLException {
        return new ReplayDbCompareRegistration(
                row.getLong("id"), row.getString("schema_name"), row.getString("table_name"),
                row.getString("table_comment"), row.getString("domain_name"),
                row.getString("reviser_emp_no"), row.getString("reviser_username"),
                row.getString("reviser_name"),
                row.getString("group_owner_emp_no"), row.getString("group_owner_name"),
                row.getDate("registered_date").toLocalDate(), row.getBoolean("deleted"),
                row.getString("deleted_reason"), row.getString("deleted_by"),
                localDateTime(row.getTimestamp("deleted_at")), row.getLong("version"),
                row.getString("created_by"), row.getString("created_name"),
                row.getTimestamp("created_at").toLocalDateTime(), row.getString("updated_by"),
                row.getString("updated_name"), row.getTimestamp("updated_at").toLocalDateTime(), fields);
    }

    private ReplayDbCompareRegistration withFields(ReplayDbCompareRegistration row,
                                                   List<ReplayDbCompareField> fields) {
        return new ReplayDbCompareRegistration(
                row.id(), row.schemaName(), row.tableName(), row.tableComment(), row.domainName(),
                row.reviserEmpNo(), row.reviserUsername(), row.reviserName(),
                row.groupOwnerEmpNo(), row.groupOwnerName(),
                row.registeredDate(), row.deleted(), row.deletedReason(), row.deletedBy(), row.deletedAt(),
                row.version(), row.createdBy(), row.createdName(), row.createdAt(), row.updatedBy(),
                row.updatedName(), row.updatedAt(), fields);
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

    private record HeaderColumn(
            String queryKey,
            String valueExpression,
            String labelExpression,
            String searchExpression,
            boolean fieldColumn) {

        private static HeaderColumn from(String key) {
            return switch (key == null ? "" : key) {
                case "domainName" -> new HeaderColumn(key, "r.domain_name", "r.domain_name", "r.domain_name", false);
                case "tableName" -> new HeaderColumn(key, "r.table_name", "CASE WHEN COALESCE(r.table_comment,'')='' THEN r.table_name ELSE CONCAT(r.table_name,'（',r.table_comment,'）') END", "CONCAT(r.table_name,' ',COALESCE(r.table_comment,''))", false);
                case "fieldName" -> new HeaderColumn(key, "hf.column_name", "CASE WHEN COALESCE(hf.column_comment,'')='' THEN hf.column_name ELSE CONCAT(hf.column_name,'（',hf.column_comment,'）') END", "CONCAT(hf.column_name,' ',COALESCE(hf.column_comment,''))", true);
                case "reviser" -> new HeaderColumn(key, "r.reviser_emp_no", "CASE WHEN COALESCE(r.reviser_name,'')<>'' AND COALESCE(NULLIF(r.reviser_username,''),r.reviser_emp_no,'')<>'' THEN CONCAT(r.reviser_name,'（',COALESCE(NULLIF(r.reviser_username,''),r.reviser_emp_no),'）') WHEN COALESCE(r.reviser_name,'')<>'' THEN r.reviser_name ELSE COALESCE(NULLIF(r.reviser_username,''),r.reviser_emp_no,'') END", "CONCAT(COALESCE(r.reviser_emp_no,''),' ',COALESCE(r.reviser_username,''),' ',COALESCE(r.reviser_name,''))", false);
                case "groupOwner" -> new HeaderColumn(key, "r.group_owner_emp_no", "CONCAT(COALESCE(r.group_owner_name,''),'(',r.group_owner_emp_no,')')", "CONCAT(r.group_owner_emp_no,' ',COALESCE(r.group_owner_name,''))", false);
                case "registeredDate" -> new HeaderColumn(key, "CAST(r.registered_date AS CHAR)", "CAST(r.registered_date AS CHAR)", "CAST(r.registered_date AS CHAR)", false);
                default -> throw new IllegalArgumentException("不支持的筛选列");
            };
        }
    }
}
