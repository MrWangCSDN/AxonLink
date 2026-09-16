package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayConfigFieldChange;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplaySortFieldDraft;
import com.axonlink.ai.replay.dto.ReplaySortFieldRow;
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
public class ReplaySortFieldDao {

    private static final String SELECT_COLUMNS =
            "id,orig_trcd,orig_arry_name,orig_field_name,tran_mode,created_at,updated_at,version";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReplaySortFieldDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.tx = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public long count(String origTrcd, String origArryName, String origFieldName, Collection<String> serviceCodes) {
        if (serviceCodes != null && serviceCodes.isEmpty()) {
            return 0;
        }
        Filter filter = filter(origTrcd, origArryName, origFieldName, serviceCodes);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_sort_field WHERE 1=1" + filter.sql,
                Long.class, filter.args.toArray());
        return total == null ? 0 : total;
    }

    public List<ReplaySortFieldRow> list(String origTrcd, String origArryName, String origFieldName,
                                         Collection<String> serviceCodes, int limit, int offset) {
        if (serviceCodes != null && serviceCodes.isEmpty()) {
            return List.of();
        }
        Filter filter = filter(origTrcd, origArryName, origFieldName, serviceCodes);
        filter.args.add(limit);
        filter.args.add(offset);
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM dii_replay_sort_field WHERE 1=1"
                        + filter.sql
                        + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRow, filter.args.toArray());
    }

    public ReplaySortFieldRow findById(long id) {
        List<ReplaySortFieldRow> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM dii_replay_sort_field WHERE id=?", this::mapRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReplaySortFieldRow create(String origTrcd, String origArryName, String origFieldName,
                                     ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            long id = insertConfig(origTrcd, origArryName, origFieldName, now);
            insertOperation(id, "CREATE", null, null, null, origTrcd, origArryName, origFieldName, operator, now);
            return findById(id);
        });
    }

    /** 一个事务内批量新增（排序字段一次新增会展开为三条），并逐条写入 CREATE 审计。 */
    public List<ReplaySortFieldRow> createAll(List<ReplaySortFieldDraft> drafts, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            List<ReplaySortFieldRow> created = new ArrayList<>();
            for (ReplaySortFieldDraft draft : drafts) {
                long id = insertConfig(draft.origTrcd(), draft.origArryName(), draft.origFieldName(), now);
                insertOperation(id, "CREATE", null, null, null, draft.origTrcd(), draft.origArryName(),
                        draft.origFieldName(), operator, now);
                created.add(findById(id));
            }
            return created;
        });
    }

    public ReplaySortFieldRow update(ReplaySortFieldRow current, String newOrigTrcd, String newOrigArryName,
                                     String newOrigFieldName, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int rows = jdbc.update(
                    "UPDATE dii_replay_sort_field SET orig_trcd=?,orig_arry_name=?,orig_field_name=?,"
                            + "updated_at=?,version=version+1 WHERE id=? AND version=?",
                    newOrigTrcd, newOrigArryName, newOrigFieldName, Timestamp.valueOf(now),
                    current.id(), current.version());
            if (rows == 0) {
                throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
            }
            boolean trcdChanged = changed(current.origTrcd(), newOrigTrcd);
            boolean arryChanged = changed(current.origArryName(), newOrigArryName);
            boolean fieldChanged = changed(current.origFieldName(), newOrigFieldName);
            insertOperation(current.id(), "UPDATE",
                    trcdChanged ? current.origTrcd() : null,
                    arryChanged ? current.origArryName() : null,
                    fieldChanged ? current.origFieldName() : null,
                    trcdChanged ? newOrigTrcd : null,
                    arryChanged ? newOrigArryName : null,
                    fieldChanged ? newOrigFieldName : null, operator, now);
            return findById(current.id());
        });
    }

    public void delete(ReplaySortFieldRow current, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        tx.executeWithoutResult(status -> {
            insertOperation(current.id(), "DELETE", current.origTrcd(), current.origArryName(),
                    current.origFieldName(), null, null, null, operator, now);
            int rows = jdbc.update("DELETE FROM dii_replay_sort_field WHERE id=? AND version=?",
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
                ReplaySortFieldRow current = findById(item.id());
                if (current == null) {
                    throw new ReplayConfigNotFoundException("记录不存在：" + item.id());
                }
                if (current.version() != item.version()) {
                    throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
                }
                insertOperation(current.id(), "DELETE", current.origTrcd(), current.origArryName(),
                        current.origFieldName(), null, null, null, operator, now);
                int rows = jdbc.update("DELETE FROM dii_replay_sort_field WHERE id=? AND version=?",
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
                "SELECT COUNT(*) FROM dii_replay_sort_field_operation WHERE config_id=?",
                Long.class, configId);
        List<ReplayConfigOperationView> items = jdbc.query(
                "SELECT * FROM dii_replay_sort_field_operation WHERE config_id=? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapOperation, configId, limit, offset);
        return new ReplayConfigPage<>(total == null ? 0 : total, items);
    }

    private long insertConfig(String origTrcd, String origArryName, String origFieldName, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO dii_replay_sort_field "
                            + "(orig_trcd,orig_arry_name,orig_field_name,tran_mode,created_at,updated_at,version) "
                            + "VALUES (?,?,?,1,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, origTrcd);
            statement.setString(2, origArryName);
            statement.setString(3, origFieldName);
            statement.setTimestamp(4, Timestamp.valueOf(now));
            statement.setTimestamp(5, Timestamp.valueOf(now));
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建排序字段配置失败");
        }
        return key.longValue();
    }

    private void insertOperation(long configId, String operationType, String origTrcd, String origArryName,
                                 String origFieldName, String newOrigTrcd, String newOrigArryName,
                                 String newOrigFieldName, ReplayConfigOperator operator, LocalDateTime now) {
        jdbc.update("INSERT INTO dii_replay_sort_field_operation "
                        + "(config_id,operation_type,orig_trcd,orig_arry_name,orig_field_name,new_orig_trcd,"
                        + "new_orig_arry_name,new_orig_field_name,operator_username,operator_real_name,"
                        + "operation_source,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                configId, operationType, origTrcd, origArryName, origFieldName, newOrigTrcd,
                newOrigArryName, newOrigFieldName,
                operator == null ? null : operator.username(),
                operator == null ? null : operator.realName(), "MANUAL", Timestamp.valueOf(now));
    }

    private Filter filter(String origTrcd, String origArryName, String origFieldName,
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
        if (origArryName != null && !origArryName.isBlank()) {
            sql.append(" AND orig_arry_name LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(origArryName));
        }
        if (origFieldName != null && !origFieldName.isBlank()) {
            sql.append(" AND orig_field_name LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(origFieldName));
        }
        return new Filter(sql.toString(), args);
    }

    private ReplaySortFieldRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReplaySortFieldRow(rs.getLong("id"), rs.getString("orig_trcd"),
                rs.getString("orig_arry_name"), rs.getString("orig_field_name"), rs.getInt("tran_mode"),
                ReplayConfigSqlSupport.localDateTime(rs, "created_at"),
                ReplayConfigSqlSupport.localDateTime(rs, "updated_at"), rs.getInt("version"));
    }

    private ReplayConfigOperationView mapOperation(ResultSet rs, int rowNum) throws SQLException {
        List<ReplayConfigFieldChange> changes = new ArrayList<>();
        addChange(changes, "orig_trcd", "服务码", rs.getString("orig_trcd"), rs.getString("new_orig_trcd"));
        addChange(changes, "orig_arry_name", "对象/数组名称", rs.getString("orig_arry_name"),
                rs.getString("new_orig_arry_name"));
        addChange(changes, "orig_field_name", "排序字段", rs.getString("orig_field_name"),
                rs.getString("new_orig_field_name"));
        return new ReplayConfigOperationView(rs.getLong("id"), rs.getString("operation_type"),
                rs.getString("operator_username"), rs.getString("operator_real_name"),
                rs.getString("operation_source"), ReplayConfigSqlSupport.localDateTime(rs, "created_at"), changes);
    }

    private static void addChange(List<ReplayConfigFieldChange> changes, String field, String label,
                                  String oldValue, String newValue) {
        if (oldValue != null || newValue != null) {
            changes.add(new ReplayConfigFieldChange(field, label, oldValue, newValue));
        }
    }

    private static boolean changed(String before, String after) {
        return !Objects.equals(before, after);
    }

    private record Filter(String sql, List<Object> args) {
    }
}
