package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayConditionalRmoveRow;
import com.axonlink.ai.replay.dto.ReplayConfigFieldChange;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
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
import java.util.List;
import java.util.Objects;

@Repository
public class ReplayConditionalRmoveDao {

    private static final String SELECT_COLUMNS = "id,orig_trcd,field_rmove_name,field_fiel_state,"
            + "field_file_indx,field_file_flag,orig_field_cond,dest_field_cond,review_status,"
            + "created_at,updated_at,version";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReplayConditionalRmoveDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.tx = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public long count(String origTrcd, String fieldRmoveName, Integer fieldFileFlag,
                      Collection<String> serviceCodes) {
        if (serviceCodes != null && serviceCodes.isEmpty()) {
            return 0;
        }
        Filter filter = filter(origTrcd, fieldRmoveName, fieldFileFlag, serviceCodes);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_conditional_rmove WHERE 1=1" + filter.sql,
                Long.class, filter.args.toArray());
        return total == null ? 0 : total;
    }

    public List<ReplayConditionalRmoveRow> list(String origTrcd, String fieldRmoveName, Integer fieldFileFlag,
                                                Collection<String> serviceCodes, int limit, int offset) {
        if (serviceCodes != null && serviceCodes.isEmpty()) {
            return List.of();
        }
        Filter filter = filter(origTrcd, fieldRmoveName, fieldFileFlag, serviceCodes);
        filter.args.add(limit);
        filter.args.add(offset);
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM dii_replay_conditional_rmove WHERE 1=1"
                        + filter.sql + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRow, filter.args.toArray());
    }

    public ReplayConditionalRmoveRow findById(long id) {
        List<ReplayConditionalRmoveRow> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM dii_replay_conditional_rmove WHERE id=?",
                this::mapRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int nextIndex(String origTrcd) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(field_file_indx),0)+1 FROM dii_replay_conditional_rmove WHERE orig_trcd=?",
                Integer.class, origTrcd);
        return next == null ? 1 : next;
    }

    public ReplayConditionalRmoveRow create(String origTrcd, String fieldRmoveName, int fieldFileFlag,
                                            String origFieldCond, String destFieldCond,
                                            ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int index = nextIndex(origTrcd);
            long id = insertConfig(origTrcd, fieldRmoveName, index, fieldFileFlag, origFieldCond, destFieldCond, now);
            insertOperation(id, "CREATE",
                    null, null, null, null, null, null,
                    origTrcd, fieldRmoveName, index, fieldFileFlag, origFieldCond, destFieldCond,
                    null, 0, operator, now);
            return findById(id);
        });
    }

    public ReplayConditionalRmoveRow update(ReplayConditionalRmoveRow current, String newOrigTrcd,
                                            String newFieldRmoveName, int newFieldFileFlag, String newOrigFieldCond,
                                            String newDestFieldCond, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            boolean codeChanged = !Objects.equals(current.origTrcd(), newOrigTrcd);
            int newIndex = codeChanged ? nextIndex(newOrigTrcd) : current.fieldFileIndx();
            int rows = jdbc.update("UPDATE dii_replay_conditional_rmove SET orig_trcd=?,field_rmove_name=?,"
                            + "field_file_indx=?,field_file_flag=?,orig_field_cond=?,dest_field_cond=?,"
                            + "review_status=0,updated_at=?,version=version+1 WHERE id=? AND version=?",
                    newOrigTrcd, newFieldRmoveName, newIndex, newFieldFileFlag, newOrigFieldCond, newDestFieldCond,
                    Timestamp.valueOf(now), current.id(), current.version());
            if (rows == 0) {
                throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
            }
            boolean reviewChanged = current.reviewStatus() != 0;
            insertOperation(current.id(), "UPDATE",
                    changed(current.origTrcd(), newOrigTrcd) ? current.origTrcd() : null,
                    changed(current.fieldRmoveName(), newFieldRmoveName) ? current.fieldRmoveName() : null,
                    current.fieldFileIndx() != newIndex ? current.fieldFileIndx() : null,
                    current.fieldFileFlag() != newFieldFileFlag ? current.fieldFileFlag() : null,
                    changed(current.origFieldCond(), newOrigFieldCond) ? current.origFieldCond() : null,
                    changed(current.destFieldCond(), newDestFieldCond) ? current.destFieldCond() : null,
                    changed(current.origTrcd(), newOrigTrcd) ? newOrigTrcd : null,
                    changed(current.fieldRmoveName(), newFieldRmoveName) ? newFieldRmoveName : null,
                    current.fieldFileIndx() != newIndex ? newIndex : null,
                    current.fieldFileFlag() != newFieldFileFlag ? newFieldFileFlag : null,
                    changed(current.origFieldCond(), newOrigFieldCond) ? newOrigFieldCond : null,
                    changed(current.destFieldCond(), newDestFieldCond) ? newDestFieldCond : null,
                    reviewChanged ? current.reviewStatus() : null, reviewChanged ? 0 : null,
                    operator, now);
            return findById(current.id());
        });
    }

    public ReplayConditionalRmoveRow review(ReplayConditionalRmoveRow current, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int rows = jdbc.update(
                    "UPDATE dii_replay_conditional_rmove SET review_status=1,updated_at=?,"
                            + "version=version+1 WHERE id=? AND version=?",
                    Timestamp.valueOf(now), current.id(), current.version());
            if (rows == 0) {
                throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
            }
            insertOperation(current.id(), "REVIEW",
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    current.reviewStatus(), 1, operator, now);
            return findById(current.id());
        });
    }

    public void delete(ReplayConditionalRmoveRow current, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        tx.executeWithoutResult(status -> {
            insertOperation(current.id(), "DELETE",
                    current.origTrcd(), current.fieldRmoveName(), current.fieldFileIndx(), current.fieldFileFlag(),
                    current.origFieldCond(), current.destFieldCond(),
                    null, null, null, null, null, null,
                    current.reviewStatus(), null, operator, now);
            int rows = jdbc.update("DELETE FROM dii_replay_conditional_rmove WHERE id=? AND version=?",
                    current.id(), current.version());
            if (rows == 0) {
                throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
            }
        });
    }

    public int batchDelete(List<ReplayConfigVersionedId> items, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int deleted = 0;
            for (ReplayConfigVersionedId item : items) {
                ReplayConditionalRmoveRow current = findById(item.id());
                if (current == null) {
                    throw new ReplayConfigNotFoundException("记录不存在：" + item.id());
                }
                if (current.version() != item.version()) {
                    throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
                }
                insertOperation(current.id(), "DELETE",
                        current.origTrcd(), current.fieldRmoveName(), current.fieldFileIndx(), current.fieldFileFlag(),
                        current.origFieldCond(), current.destFieldCond(),
                        null, null, null, null, null, null,
                        current.reviewStatus(), null, operator, now);
                int rows = jdbc.update("DELETE FROM dii_replay_conditional_rmove WHERE id=? AND version=?",
                        current.id(), current.version());
                if (rows == 0) {
                    throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
                }
                deleted++;
            }
            return deleted;
        });
    }

    public ReplayConfigPage<ReplayConfigOperationView> operations(long configId, int limit, int offset) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_conditional_rmove_operation WHERE config_id=?",
                Long.class, configId);
        List<ReplayConfigOperationView> items = jdbc.query(
                "SELECT * FROM dii_replay_conditional_rmove_operation WHERE config_id=? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapOperation, configId, limit, offset);
        return new ReplayConfigPage<>(total == null ? 0 : total, items);
    }

    private long insertConfig(String origTrcd, String fieldRmoveName, int fieldFileIndx, int fieldFileFlag,
                              String origFieldCond, String destFieldCond, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO dii_replay_conditional_rmove "
                            + "(orig_trcd,field_rmove_name,field_fiel_state,field_file_indx,field_file_flag,"
                            + "orig_field_cond,dest_field_cond,review_status,created_at,updated_at,version) "
                            + "VALUES (?,?,1,?,?,?,?,0,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, origTrcd);
            statement.setString(2, fieldRmoveName);
            statement.setInt(3, fieldFileIndx);
            statement.setInt(4, fieldFileFlag);
            statement.setString(5, origFieldCond);
            statement.setString(6, destFieldCond);
            statement.setTimestamp(7, Timestamp.valueOf(now));
            statement.setTimestamp(8, Timestamp.valueOf(now));
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建有条件忽略配置失败");
        }
        return key.longValue();
    }

    @SuppressWarnings("java:S107")
    private void insertOperation(long configId, String operationType,
                                 String origTrcd, String fieldRmoveName, Integer fieldFileIndx, Integer fieldFileFlag,
                                 String origFieldCond, String destFieldCond,
                                 String newOrigTrcd, String newFieldRmoveName, Integer newFieldFileIndx,
                                 Integer newFieldFileFlag, String newOrigFieldCond, String newDestFieldCond,
                                 Integer reviewStatus, Integer newReviewStatus,
                                 ReplayConfigOperator operator, LocalDateTime now) {
        jdbc.update("INSERT INTO dii_replay_conditional_rmove_operation "
                        + "(config_id,operation_type,orig_trcd,field_rmove_name,field_file_indx,field_file_flag,"
                        + "orig_field_cond,dest_field_cond,new_orig_trcd,new_field_rmove_name,new_field_file_indx,"
                        + "new_field_file_flag,new_orig_field_cond,new_dest_field_cond,review_status,"
                        + "new_review_status,operator_username,operator_real_name,operation_source,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                configId, operationType, origTrcd, fieldRmoveName, fieldFileIndx, fieldFileFlag,
                origFieldCond, destFieldCond, newOrigTrcd, newFieldRmoveName, newFieldFileIndx,
                newFieldFileFlag, newOrigFieldCond, newDestFieldCond, reviewStatus, newReviewStatus,
                operator == null ? null : operator.username(),
                operator == null ? null : operator.realName(), "MANUAL", Timestamp.valueOf(now));
    }

    private Filter filter(String origTrcd, String fieldRmoveName, Integer fieldFileFlag,
                          Collection<String> serviceCodes) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (serviceCodes != null) {
            sql.append(" AND orig_trcd IN ").append(ReplayConfigSqlSupport.appendIn(args, serviceCodes));
        }
        if (origTrcd != null && !origTrcd.isBlank()) {
            sql.append(" AND orig_trcd LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(origTrcd));
        }
        if (fieldRmoveName != null && !fieldRmoveName.isBlank()) {
            sql.append(" AND field_rmove_name LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(fieldRmoveName));
        }
        if (fieldFileFlag != null) {
            sql.append(" AND field_file_flag=?");
            args.add(fieldFileFlag);
        }
        return new Filter(sql.toString(), args);
    }

    private ReplayConditionalRmoveRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReplayConditionalRmoveRow(rs.getLong("id"), rs.getString("orig_trcd"),
                rs.getString("field_rmove_name"), rs.getInt("field_fiel_state"), rs.getInt("field_file_indx"),
                rs.getInt("field_file_flag"), rs.getString("orig_field_cond"), rs.getString("dest_field_cond"),
                ReplayConfigSqlSupport.localDateTime(rs, "created_at"),
                ReplayConfigSqlSupport.localDateTime(rs, "updated_at"), rs.getInt("version"),
                rs.getInt("review_status"), null, null, null, false, null);
    }

    private ReplayConfigOperationView mapOperation(ResultSet rs, int rowNum) throws SQLException {
        List<ReplayConfigFieldChange> changes = new ArrayList<>();
        addChange(changes, "orig_trcd", "服务码", rs.getString("orig_trcd"), rs.getString("new_orig_trcd"));
        addChange(changes, "field_rmove_name", "忽略字段", rs.getString("field_rmove_name"),
                rs.getString("new_field_rmove_name"));
        addChange(changes, "field_file_indx", "字段索引", rs.getObject("field_file_indx"),
                rs.getObject("new_field_file_indx"));
        addChange(changes, "field_file_flag", "字段标识", rs.getObject("field_file_flag"),
                rs.getObject("new_field_file_flag"));
        addChange(changes, "orig_field_cond", "主系统字段忽略条件", rs.getString("orig_field_cond"),
                rs.getString("new_orig_field_cond"));
        addChange(changes, "dest_field_cond", "备系统字段忽略条件", rs.getString("dest_field_cond"),
                rs.getString("new_dest_field_cond"));
        addChange(changes, "review_status", "审核状态", rs.getObject("review_status"),
                rs.getObject("new_review_status"));
        return new ReplayConfigOperationView(rs.getLong("id"), rs.getString("operation_type"),
                rs.getString("operator_username"), rs.getString("operator_real_name"),
                rs.getString("operation_source"), ReplayConfigSqlSupport.localDateTime(rs, "created_at"), changes);
    }

    private static void addChange(List<ReplayConfigFieldChange> changes, String field, String label,
                                  Object oldValue, Object newValue) {
        if (oldValue != null || newValue != null) {
            changes.add(new ReplayConfigFieldChange(field, label,
                    oldValue == null ? null : String.valueOf(oldValue),
                    newValue == null ? null : String.valueOf(newValue)));
        }
    }

    private static boolean changed(String before, String after) {
        return !Objects.equals(before, after);
    }

    private record Filter(String sql, List<Object> args) {
    }
}
