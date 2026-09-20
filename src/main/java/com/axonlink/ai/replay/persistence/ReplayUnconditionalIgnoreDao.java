package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayConfigFieldChange;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreDraft;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreRow;
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
public class ReplayUnconditionalIgnoreDao {

    private static final String SELECT_COLUMNS =
            "id,tran_code,field_name,enable_flag,review_status,created_at,updated_at,version";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReplayUnconditionalIgnoreDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.tx = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public long count(String tranCode, String fieldName, Collection<String> serviceCodes, Integer reviewStatus) {
        if (serviceCodes != null && serviceCodes.isEmpty()) {
            return 0;
        }
        Filter filter = filter(tranCode, fieldName, serviceCodes, reviewStatus);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_unconditional_ignore WHERE 1=1" + filter.sql,
                Long.class, filter.args.toArray());
        return total == null ? 0 : total;
    }

    public List<ReplayUnconditionalIgnoreRow> list(String tranCode, String fieldName,
                                                   Collection<String> serviceCodes, Integer reviewStatus,
                                                   int limit, int offset) {
        if (serviceCodes != null && serviceCodes.isEmpty()) {
            return List.of();
        }
        Filter filter = filter(tranCode, fieldName, serviceCodes, reviewStatus);
        filter.args.add(limit);
        filter.args.add(offset);
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM dii_replay_unconditional_ignore WHERE 1=1"
                        + filter.sql + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRow, filter.args.toArray());
    }

    public ReplayUnconditionalIgnoreRow findById(long id) {
        List<ReplayUnconditionalIgnoreRow> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM dii_replay_unconditional_ignore WHERE id=?",
                this::mapRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReplayUnconditionalIgnoreRow create(String tranCode, String fieldName, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            long id = insertConfig(tranCode, fieldName, now);
            insertOperation(id, "CREATE", null, null, tranCode, fieldName, null, 0, operator, now);
            return findById(id);
        });
    }

    /** 一个事务内批量新增并逐条写 CREATE 审计；任一条失败整体回滚。 */
    public List<ReplayUnconditionalIgnoreRow> createAll(List<ReplayUnconditionalIgnoreDraft> drafts,
                                                        ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            List<ReplayUnconditionalIgnoreRow> created = new ArrayList<>();
            for (ReplayUnconditionalIgnoreDraft draft : drafts) {
                long id = insertConfig(draft.tranCode(), draft.fieldName(), now);
                insertOperation(id, "CREATE", null, null, draft.tranCode(), draft.fieldName(),
                        null, 0, operator, now);
                created.add(findById(id));
            }
            return created;
        });
    }

    public ReplayUnconditionalIgnoreRow update(ReplayUnconditionalIgnoreRow current, String newTranCode,
                                               String newFieldName, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int rows = jdbc.update(
                    "UPDATE dii_replay_unconditional_ignore SET tran_code=?,field_name=?,review_status=0,"
                            + "updated_at=?,version=version+1 WHERE id=? AND version=?",
                    newTranCode, newFieldName, Timestamp.valueOf(now), current.id(), current.version());
            if (rows == 0) {
                throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
            }
            boolean tranChanged = !Objects.equals(current.tranCode(), newTranCode);
            boolean fieldChanged = !Objects.equals(current.fieldName(), newFieldName);
            boolean reviewChanged = current.reviewStatus() != 0;
            insertOperation(current.id(), "UPDATE",
                    tranChanged ? current.tranCode() : null, fieldChanged ? current.fieldName() : null,
                    tranChanged ? newTranCode : null, fieldChanged ? newFieldName : null,
                    reviewChanged ? current.reviewStatus() : null, reviewChanged ? 0 : null, operator, now);
            return findById(current.id());
        });
    }

    public ReplayUnconditionalIgnoreRow review(ReplayUnconditionalIgnoreRow current, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int rows = jdbc.update(
                    "UPDATE dii_replay_unconditional_ignore SET review_status=1,updated_at=?,"
                            + "version=version+1 WHERE id=? AND version=?",
                    Timestamp.valueOf(now), current.id(), current.version());
            if (rows == 0) {
                throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
            }
            insertOperation(current.id(), "REVIEW", null, null, null, null,
                    current.reviewStatus(), 1, operator, now);
            return findById(current.id());
        });
    }

    public void delete(ReplayUnconditionalIgnoreRow current, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        tx.executeWithoutResult(status -> {
            insertOperation(current.id(), "DELETE", current.tranCode(), current.fieldName(),
                    null, null, current.reviewStatus(), null, operator, now);
            int rows = jdbc.update("DELETE FROM dii_replay_unconditional_ignore WHERE id=? AND version=?",
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
                ReplayUnconditionalIgnoreRow current = findById(item.id());
                if (current == null) {
                    throw new ReplayConfigNotFoundException("记录不存在：" + item.id());
                }
                if (current.version() != item.version()) {
                    throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
                }
                insertOperation(current.id(), "DELETE", current.tranCode(), current.fieldName(),
                        null, null, current.reviewStatus(), null, operator, now);
                int rows = jdbc.update("DELETE FROM dii_replay_unconditional_ignore WHERE id=? AND version=?",
                        current.id(), current.version());
                if (rows == 0) {
                    throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
                }
                deleted++;
            }
            return deleted;
        });
    }
    /** 批量审核：逐条校验后置为已审核，不满足条件的跳过；返回通过条数。 */
    public int batchReview(List<ReplayConfigVersionedId> items, ReplayConfigOperator operator,
                           java.util.function.Predicate<ReplayUnconditionalIgnoreRow> canApprove) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int approved = 0;
            for (ReplayConfigVersionedId item : items) {
                ReplayUnconditionalIgnoreRow current = findById(item.id());
                if (current == null || current.version() != item.version() || !canApprove.test(current)) {
                    continue;
                }
                int rows = jdbc.update(
                        "UPDATE dii_replay_unconditional_ignore SET review_status=1,updated_at=?,"
                                + "version=version+1 WHERE id=? AND version=?",
                        Timestamp.valueOf(now), current.id(), current.version());
                if (rows == 0) {
                    continue;
                }
                insertOperation(current.id(), "REVIEW", null, null, null, null,
                        current.reviewStatus(), 1, operator, now);
                approved++;
            }
            return approved;
        });
    }

    public ReplayConfigPage<ReplayConfigOperationView> operations(long configId, int limit, int offset) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_unconditional_ignore_operation WHERE config_id=?",
                Long.class, configId);
        List<ReplayConfigOperationView> items = jdbc.query(
                "SELECT * FROM dii_replay_unconditional_ignore_operation WHERE config_id=? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapOperation, configId, limit, offset);
        return new ReplayConfigPage<>(total == null ? 0 : total, items);
    }

    private long insertConfig(String tranCode, String fieldName, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO dii_replay_unconditional_ignore "
                            + "(tran_code,field_name,enable_flag,review_status,created_at,updated_at,version) "
                            + "VALUES (?,?,1,0,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, tranCode);
            statement.setString(2, fieldName);
            statement.setTimestamp(3, Timestamp.valueOf(now));
            statement.setTimestamp(4, Timestamp.valueOf(now));
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建无条件忽略配置失败");
        }
        return key.longValue();
    }

    private void insertOperation(long configId, String operationType, String tranCode, String fieldName,
                                 String newTranCode, String newFieldName, Integer reviewStatus,
                                 Integer newReviewStatus, ReplayConfigOperator operator, LocalDateTime now) {
        jdbc.update("INSERT INTO dii_replay_unconditional_ignore_operation "
                        + "(config_id,operation_type,tran_code,field_name,new_tran_code,new_field_name,"
                        + "review_status,new_review_status,operator_username,operator_real_name,operation_source,"
                        + "created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                configId, operationType, tranCode, fieldName, newTranCode, newFieldName,
                reviewStatus, newReviewStatus,
                operator == null ? null : operator.username(),
                operator == null ? null : operator.realName(), "MANUAL", Timestamp.valueOf(now));
    }

    private Filter filter(String tranCode, String fieldName, Collection<String> serviceCodes, Integer reviewStatus) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (serviceCodes != null) {
            sql.append(" AND tran_code IN ").append(ReplayConfigSqlSupport.appendIn(args, serviceCodes));
        }
        if (tranCode != null && !tranCode.isBlank()) {
            sql.append(" AND tran_code LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(tranCode));
        }
        if (fieldName != null && !fieldName.isBlank()) {
            sql.append(" AND field_name LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(fieldName));
        }
        if (reviewStatus != null) {
            sql.append(" AND review_status=?");
            args.add(reviewStatus);
        }
        return new Filter(sql.toString(), args);
    }

    private ReplayUnconditionalIgnoreRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReplayUnconditionalIgnoreRow(rs.getLong("id"), rs.getString("tran_code"),
                rs.getString("field_name"), rs.getInt("enable_flag"),
                ReplayConfigSqlSupport.localDateTime(rs, "created_at"),
                ReplayConfigSqlSupport.localDateTime(rs, "updated_at"), rs.getInt("version"),
                rs.getInt("review_status"), null, null, null, false, null);
    }

    private ReplayConfigOperationView mapOperation(ResultSet rs, int rowNum) throws SQLException {
        List<ReplayConfigFieldChange> changes = new ArrayList<>();
        addChange(changes, "tran_code", "服务码", rs.getString("tran_code"), rs.getString("new_tran_code"));
        addChange(changes, "field_name", "忽略字段", rs.getString("field_name"), rs.getString("new_field_name"));
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

    private record Filter(String sql, List<Object> args) {
    }
}
