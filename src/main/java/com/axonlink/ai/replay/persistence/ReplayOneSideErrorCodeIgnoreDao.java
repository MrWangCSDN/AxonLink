package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayConfigFieldChange;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayOneSideErrorCodeIgnoreRow;
import com.axonlink.ai.replay.service.ReplayConfigConflictException;
import com.axonlink.ai.replay.service.ReplayConfigNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

@Repository
public class ReplayOneSideErrorCodeIgnoreDao {

    private static final String COLUMNS = "id,service_code,category,ownership,error_code,ignore_reason,review_status,created_at,updated_at";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReplayOneSideErrorCodeIgnoreDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public long count(String category, String serviceCode, String ownership, String errorCode,
                      Collection<String> serviceCodes, Integer reviewStatus, Boolean reasonEmpty) {
        if (serviceCodes != null && serviceCodes.isEmpty()) return 0;
        Filter filter = filter(category, serviceCode, ownership, errorCode, serviceCodes, reviewStatus, reasonEmpty);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_one_side_error_code_ignore WHERE 1=1" + filter.sql,
                Long.class, filter.args.toArray());
        return total == null ? 0 : total;
    }

    public List<ReplayOneSideErrorCodeIgnoreRow> list(String category, String serviceCode, String ownership,
                                                       String errorCode, Collection<String> serviceCodes,
                                                       Integer reviewStatus, Boolean reasonEmpty, int limit, int offset) {
        if (serviceCodes != null && serviceCodes.isEmpty()) return List.of();
        Filter filter = filter(category, serviceCode, ownership, errorCode, serviceCodes, reviewStatus, reasonEmpty);
        filter.args.add(limit);
        filter.args.add(offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM dii_replay_one_side_error_code_ignore WHERE 1=1" + filter.sql
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", this::mapRow, filter.args.toArray());
    }

    public List<ReplayOneSideErrorCodeIgnoreRow> listApprovedForExport() {
        return jdbc.query("SELECT " + COLUMNS + " FROM dii_replay_one_side_error_code_ignore WHERE review_status=1 "
                + "ORDER BY category,ownership,created_at,id", this::mapRow);
    }

    public ReplayOneSideErrorCodeIgnoreRow findById(long id) {
        List<ReplayOneSideErrorCodeIgnoreRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM dii_replay_one_side_error_code_ignore WHERE id=?", this::mapRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ReplayOneSideErrorCodeIgnoreRow> createAll(String serviceCode, String category, String ownership,
                                                            List<String> codes, String reason,
                                                            ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
            List<Object> args = new ArrayList<>(List.of(category, ownership));
            args.addAll(codes);
            List<String> conflicts = jdbc.queryForList(
                    "SELECT error_code FROM dii_replay_one_side_error_code_ignore WHERE category=? AND ownership=? AND error_code IN ("
                            + placeholders + ")", String.class, args.toArray());
            if (!conflicts.isEmpty()) throw new ReplayConfigConflictException("单边失败错误码已存在：" + String.join(",", conflicts));
            List<ReplayOneSideErrorCodeIgnoreRow> created = new ArrayList<>();
            for (String code : codes) {
                long id = insert(serviceCode, category, ownership, code, reason, now);
                operation(id, "CREATE", null, null, null, null, null, null,
                        serviceCode, category, ownership, code, reason, 0, operator, now);
                created.add(findById(id));
            }
            return created;
        });
    }

    public ReplayOneSideErrorCodeIgnoreRow update(ReplayOneSideErrorCodeIgnoreRow current, String serviceCode,
                                                   String category, String ownership, String code, String reason,
                                                   ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            boolean serviceChanged = changed(current.serviceCode(), serviceCode);
            boolean categoryChanged = changed(current.category(), category);
            boolean ownershipChanged = changed(current.ownership(), ownership);
            boolean codeChanged = changed(current.errorCode(), code);
            boolean reasonChanged = changed(current.ignoreReason(), reason);
            boolean businessChanged = serviceChanged || categoryChanged || ownershipChanged || codeChanged;
            int nextReviewStatus = businessChanged ? 0 : current.reviewStatus();
            jdbc.update("UPDATE dii_replay_one_side_error_code_ignore SET service_code=?,category=?,ownership=?,error_code=?,"
                            + "ignore_reason=?,review_status=?,updated_at=? WHERE id=?",
                    serviceCode, category, ownership, code, reason, nextReviewStatus, Timestamp.valueOf(now), current.id());
            operation(current.id(), "UPDATE",
                    serviceChanged ? current.serviceCode() : null, categoryChanged ? current.category() : null,
                    ownershipChanged ? current.ownership() : null, codeChanged ? current.errorCode() : null,
                    reasonChanged ? current.ignoreReason() : null,
                    current.reviewStatus() == nextReviewStatus ? null : current.reviewStatus(),
                    serviceChanged ? serviceCode : null, categoryChanged ? category : null,
                    ownershipChanged ? ownership : null, codeChanged ? code : null,
                    reasonChanged ? reason : null, current.reviewStatus() == nextReviewStatus ? null : nextReviewStatus,
                    operator, now);
            return findById(current.id());
        });
    }

    public ReplayOneSideErrorCodeIgnoreRow review(ReplayOneSideErrorCodeIgnoreRow current,
                                                   ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int changed = jdbc.update("UPDATE dii_replay_one_side_error_code_ignore SET review_status=1,updated_at=? WHERE id=? AND review_status=0",
                    Timestamp.valueOf(now), current.id());
            if (changed == 1) operation(current.id(), "REVIEW", null, null, null, null, null, current.reviewStatus(),
                    null, null, null, null, null, 1, operator, now);
            return findById(current.id());
        });
    }

    public int batchReview(List<Long> ids, ReplayConfigOperator operator,
                           Predicate<ReplayOneSideErrorCodeIgnoreRow> canReview) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int approved = 0;
            for (Long id : ids) {
                ReplayOneSideErrorCodeIgnoreRow current = findById(id);
                if (current == null || current.reviewStatus() != 0 || !canReview.test(current)) continue;
                if (jdbc.update("UPDATE dii_replay_one_side_error_code_ignore SET review_status=1,updated_at=? WHERE id=? AND review_status=0",
                        Timestamp.valueOf(now), id) == 1) {
                    operation(id, "REVIEW", null, null, null, null, null, 0,
                            null, null, null, null, null, 1, operator, now);
                    approved++;
                }
            }
            return approved;
        });
    }

    public int batchDelete(List<Long> ids, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int deleted = 0;
            for (Long id : ids) {
                ReplayOneSideErrorCodeIgnoreRow current = findById(id);
                if (current == null) throw new ReplayConfigNotFoundException("记录不存在：" + id);
                operation(id, "DELETE", current.serviceCode(), current.category(), current.ownership(), current.errorCode(),
                        current.ignoreReason(), current.reviewStatus(), null, null, null, null, null, null, operator, now);
                deleted += jdbc.update("DELETE FROM dii_replay_one_side_error_code_ignore WHERE id=?", id);
            }
            return deleted;
        });
    }

    public ReplayConfigPage<ReplayConfigOperationView> operations(long configId, int limit, int offset) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_one_side_error_code_ignore_operation WHERE config_id=?", Long.class, configId);
        List<ReplayConfigOperationView> items = jdbc.query(
                "SELECT * FROM dii_replay_one_side_error_code_ignore_operation WHERE config_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::mapOperation, configId, limit, offset);
        return new ReplayConfigPage<>(total == null ? 0 : total, items);
    }

    private long insert(String serviceCode, String category, String ownership, String code, String reason, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO dii_replay_one_side_error_code_ignore "
                    + "(service_code,category,ownership,error_code,ignore_reason,review_status,created_at,updated_at) VALUES (?,?,?,?,?,0,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, serviceCode); statement.setString(2, category); statement.setString(3, ownership);
            statement.setString(4, code); statement.setString(5, reason);
            statement.setTimestamp(6, Timestamp.valueOf(now)); statement.setTimestamp(7, Timestamp.valueOf(now));
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("创建单边失败错误码忽略失败");
        return key.longValue();
    }

    private void operation(long id, String type, String oldService, String oldCategory, String oldOwnership,
                           String oldCode, String oldReason, Integer oldReview, String newService, String newCategory,
                           String newOwnership, String newCode, String newReason, Integer newReview,
                           ReplayConfigOperator operator, LocalDateTime now) {
        jdbc.update("INSERT INTO dii_replay_one_side_error_code_ignore_operation "
                        + "(config_id,operation_type,service_code,category,ownership,error_code,ignore_reason,review_status,"
                        + "new_service_code,new_category,new_ownership,new_error_code,new_ignore_reason,new_review_status,"
                        + "operator_username,operator_real_name,operation_source,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, type, oldService, oldCategory, oldOwnership, oldCode, oldReason, oldReview,
                newService, newCategory, newOwnership, newCode, newReason, newReview,
                operator == null ? null : operator.username(), operator == null ? null : operator.realName(),
                "MANUAL", Timestamp.valueOf(now));
    }

    private Filter filter(String category, String serviceCode, String ownership, String errorCode,
                          Collection<String> serviceCodes, Integer reviewStatus, Boolean reasonEmpty) {
        StringBuilder sql = new StringBuilder(); List<Object> args = new ArrayList<>();
        if (serviceCodes != null) sql.append(" AND service_code IN ").append(ReplayConfigSqlSupport.appendIn(args, serviceCodes));
        if (category != null && !category.isBlank()) { sql.append(" AND category=?"); args.add(category.trim()); }
        if (serviceCode != null && !serviceCode.isBlank()) { sql.append(" AND service_code LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE); args.add(ReplayConfigSqlSupport.likeContains(serviceCode)); }
        if (ownership != null && !ownership.isBlank()) { sql.append(" AND ownership=?"); args.add(ownership.trim()); }
        if (errorCode != null && !errorCode.isBlank()) { sql.append(" AND error_code LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE); args.add(ReplayConfigSqlSupport.likeContains(errorCode)); }
        if (reviewStatus != null) { sql.append(" AND review_status=?"); args.add(reviewStatus); }
        if (Boolean.TRUE.equals(reasonEmpty)) sql.append(" AND TRIM(COALESCE(ignore_reason,''))=''");
        return new Filter(sql.toString(), args);
    }

    private ReplayOneSideErrorCodeIgnoreRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReplayOneSideErrorCodeIgnoreRow(rs.getLong("id"), rs.getString("service_code"), rs.getString("category"),
                rs.getString("ownership"), rs.getString("error_code"), rs.getString("ignore_reason"), rs.getInt("review_status"),
                ReplayConfigSqlSupport.localDateTime(rs, "created_at"), ReplayConfigSqlSupport.localDateTime(rs, "updated_at"),
                null, null, null, false, null, null);
    }

    private ReplayConfigOperationView mapOperation(ResultSet rs, int rowNum) throws SQLException {
        List<ReplayConfigFieldChange> changes = new ArrayList<>();
        add(changes, "service_code", "服务码", rs.getObject("service_code"), rs.getObject("new_service_code"));
        add(changes, "category", "领域", rs.getObject("category"), rs.getObject("new_category"));
        add(changes, "ownership", "归属", rs.getObject("ownership"), rs.getObject("new_ownership"));
        add(changes, "error_code", "忽略错误码", rs.getObject("error_code"), rs.getObject("new_error_code"));
        add(changes, "ignore_reason", "忽略原因", rs.getObject("ignore_reason"), rs.getObject("new_ignore_reason"));
        add(changes, "review_status", "审核状态", rs.getObject("review_status"), rs.getObject("new_review_status"));
        return new ReplayConfigOperationView(rs.getLong("id"), rs.getString("operation_type"), rs.getString("operator_username"),
                rs.getString("operator_real_name"), rs.getString("operation_source"), ReplayConfigSqlSupport.localDateTime(rs, "created_at"), changes);
    }

    private static void add(List<ReplayConfigFieldChange> changes, String field, String label, Object oldValue, Object newValue) {
        if (oldValue != null || newValue != null) changes.add(new ReplayConfigFieldChange(field, label,
                oldValue == null ? null : String.valueOf(oldValue), newValue == null ? null : String.valueOf(newValue)));
    }
    private static boolean changed(Object oldValue, Object newValue) { return !Objects.equals(oldValue, newValue); }
    private record Filter(String sql, List<Object> args) { }
}
