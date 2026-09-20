package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayConfigFieldChange;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigOperationView;
import com.axonlink.ai.replay.dto.ReplayConfigPage;
import com.axonlink.ai.replay.dto.ReplayConfigVersionedId;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreDraft;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreRow;
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
public class ReplayErrorCodeIgnoreDao {

    private static final String SELECT_COLUMNS =
            "id,service_code,old_resp_code,new_resp_code,enabled,review_status,created_at,updated_at,version";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReplayErrorCodeIgnoreDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        this.tx = new TransactionTemplate(
                new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    public long count(String serviceCode, String oldRespCode, String newRespCode, Collection<String> serviceCodes,
                      Integer reviewStatus) {
        if (serviceCodes != null && serviceCodes.isEmpty()) {
            return 0;
        }
        Filter filter = filter(serviceCode, oldRespCode, newRespCode, serviceCodes, reviewStatus);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_error_code_ignore_config WHERE 1=1" + filter.sql,
                Long.class, filter.args.toArray());
        return total == null ? 0 : total;
    }

    public List<ReplayErrorCodeIgnoreRow> list(String serviceCode, String oldRespCode, String newRespCode,
                                               Collection<String> serviceCodes, Integer reviewStatus,
                                               int limit, int offset) {
        if (serviceCodes != null && serviceCodes.isEmpty()) {
            return List.of();
        }
        Filter filter = filter(serviceCode, oldRespCode, newRespCode, serviceCodes, reviewStatus);
        filter.args.add(limit);
        filter.args.add(offset);
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM dii_replay_error_code_ignore_config WHERE 1=1"
                        + filter.sql
                        + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRow, filter.args.toArray());
    }

    public ReplayErrorCodeIgnoreRow findById(long id) {
        List<ReplayErrorCodeIgnoreRow> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM dii_replay_error_code_ignore_config WHERE id=?",
                this::mapRow, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReplayErrorCodeIgnoreRow create(String serviceCode, String oldRespCode, String newRespCode,
                                           ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            long id = insertConfig(serviceCode, oldRespCode, newRespCode, now);
            insertOperation(id, "CREATE", null, null, null, serviceCode, oldRespCode, newRespCode,
                    null, 0, operator, now);
            return findById(id);
        });
    }

    /** 一个事务内批量新增并逐条写 CREATE 审计；任一条失败整体回滚。 */
    public List<ReplayErrorCodeIgnoreRow> createAll(List<ReplayErrorCodeIgnoreDraft> drafts,
                                                    ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            List<ReplayErrorCodeIgnoreRow> created = new ArrayList<>();
            for (ReplayErrorCodeIgnoreDraft draft : drafts) {
                long id = insertConfig(draft.serviceCode(), draft.oldRespCode(), draft.newRespCode(), now);
                insertOperation(id, "CREATE", null, null, null, draft.serviceCode(), draft.oldRespCode(),
                        draft.newRespCode(), null, 0, operator, now);
                created.add(findById(id));
            }
            return created;
        });
    }

    public ReplayErrorCodeIgnoreRow update(ReplayErrorCodeIgnoreRow current, String newServiceCode,
                                           String newOldRespCode, String newNewRespCode,
                                           ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int rows = jdbc.update(
                    "UPDATE dii_replay_error_code_ignore_config SET service_code=?,old_resp_code=?,"
                            + "new_resp_code=?,review_status=0,updated_at=?,version=version+1 "
                            + "WHERE id=? AND version=?",
                    newServiceCode, newOldRespCode, newNewRespCode, Timestamp.valueOf(now),
                    current.id(), current.version());
            if (rows == 0) {
                throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
            }
            boolean serviceChanged = changed(current.serviceCode(), newServiceCode);
            boolean oldChanged = changed(current.oldRespCode(), newOldRespCode);
            boolean newChanged = changed(current.newRespCode(), newNewRespCode);
            boolean reviewChanged = current.reviewStatus() != 0;
            insertOperation(current.id(), "UPDATE",
                    serviceChanged ? current.serviceCode() : null,
                    oldChanged ? current.oldRespCode() : null,
                    newChanged ? current.newRespCode() : null,
                    serviceChanged ? newServiceCode : null,
                    oldChanged ? newOldRespCode : null,
                    newChanged ? newNewRespCode : null,
                    reviewChanged ? current.reviewStatus() : null, reviewChanged ? 0 : null, operator, now);
            return findById(current.id());
        });
    }

    public ReplayErrorCodeIgnoreRow review(ReplayErrorCodeIgnoreRow current, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int rows = jdbc.update(
                    "UPDATE dii_replay_error_code_ignore_config SET review_status=1,updated_at=?,"
                            + "version=version+1 WHERE id=? AND version=?",
                    Timestamp.valueOf(now), current.id(), current.version());
            if (rows == 0) {
                throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
            }
            insertOperation(current.id(), "REVIEW", null, null, null, null, null, null,
                    current.reviewStatus(), 1, operator, now);
            return findById(current.id());
        });
    }

    public void delete(ReplayErrorCodeIgnoreRow current, ReplayConfigOperator operator) {
        LocalDateTime now = LocalDateTime.now();
        tx.executeWithoutResult(status -> {
            insertOperation(current.id(), "DELETE", current.serviceCode(), current.oldRespCode(),
                    current.newRespCode(), null, null, null, current.reviewStatus(), null, operator, now);
            int rows = jdbc.update("DELETE FROM dii_replay_error_code_ignore_config WHERE id=? AND version=?",
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
                ReplayErrorCodeIgnoreRow current = findById(item.id());
                if (current == null) {
                    throw new ReplayConfigNotFoundException("记录不存在：" + item.id());
                }
                if (current.version() != item.version()) {
                    throw new ReplayConfigConflictException("数据已被其他用户修改，请刷新后重试");
                }
                insertOperation(current.id(), "DELETE", current.serviceCode(), current.oldRespCode(),
                        current.newRespCode(), null, null, null, current.reviewStatus(), null, operator, now);
                int rows = jdbc.update("DELETE FROM dii_replay_error_code_ignore_config WHERE id=? AND version=?",
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
                           java.util.function.Predicate<ReplayErrorCodeIgnoreRow> canApprove) {
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(status -> {
            int approved = 0;
            for (ReplayConfigVersionedId item : items) {
                ReplayErrorCodeIgnoreRow current = findById(item.id());
                if (current == null || current.version() != item.version() || !canApprove.test(current)) {
                    continue;
                }
                int rows = jdbc.update(
                        "UPDATE dii_replay_error_code_ignore_config SET review_status=1,updated_at=?,"
                                + "version=version+1 WHERE id=? AND version=?",
                        Timestamp.valueOf(now), current.id(), current.version());
                if (rows == 0) {
                    continue;
                }
                insertOperation(current.id(), "REVIEW", null, null, null, null, null, null,
                        current.reviewStatus(), 1, operator, now);
                approved++;
            }
            return approved;
        });
    }

    public ReplayConfigPage<ReplayConfigOperationView> operations(long configId, int limit, int offset) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_error_code_ignore_config_operation WHERE config_id=?",
                Long.class, configId);
        List<ReplayConfigOperationView> items = jdbc.query(
                "SELECT * FROM dii_replay_error_code_ignore_config_operation WHERE config_id=? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapOperation, configId, limit, offset);
        return new ReplayConfigPage<>(total == null ? 0 : total, items);
    }

    private long insertConfig(String serviceCode, String oldRespCode, String newRespCode, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO dii_replay_error_code_ignore_config "
                            + "(service_code,old_resp_code,new_resp_code,enabled,review_status,created_at,"
                            + "updated_at,version) VALUES (?,?,?,1,0,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, serviceCode);
            statement.setString(2, oldRespCode);
            statement.setString(3, newRespCode);
            statement.setTimestamp(4, Timestamp.valueOf(now));
            statement.setTimestamp(5, Timestamp.valueOf(now));
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建错误码忽略配置失败");
        }
        return key.longValue();
    }

    private void insertOperation(long configId, String operationType, String serviceCode, String oldRespCode,
                                 String newRespCode, String newServiceCode, String newOldRespCode,
                                 String newNewRespCode, Integer reviewStatus, Integer newReviewStatus,
                                 ReplayConfigOperator operator, LocalDateTime now) {
        jdbc.update("INSERT INTO dii_replay_error_code_ignore_config_operation "
                        + "(config_id,operation_type,service_code,old_resp_code,new_resp_code,new_service_code,"
                        + "new_old_resp_code,new_new_resp_code,review_status,new_review_status,operator_username,"
                        + "operator_real_name,operation_source,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                configId, operationType, serviceCode, oldRespCode, newRespCode, newServiceCode,
                newOldRespCode, newNewRespCode, reviewStatus, newReviewStatus,
                operator == null ? null : operator.username(),
                operator == null ? null : operator.realName(), "MANUAL", Timestamp.valueOf(now));
    }

    private Filter filter(String serviceCode, String oldRespCode, String newRespCode,
                          Collection<String> serviceCodes, Integer reviewStatus) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (serviceCodes != null) {
            sql.append(" AND service_code IN ").append(ReplayConfigSqlSupport.appendIn(args, serviceCodes));
        }
        if (serviceCode != null && !serviceCode.isBlank()) {
            sql.append(" AND service_code LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(serviceCode));
        }
        if (oldRespCode != null && !oldRespCode.isBlank()) {
            sql.append(" AND old_resp_code LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(oldRespCode));
        }
        if (newRespCode != null && !newRespCode.isBlank()) {
            sql.append(" AND new_resp_code LIKE ?").append(ReplayConfigSqlSupport.LIKE_ESCAPE);
            args.add(ReplayConfigSqlSupport.likeContains(newRespCode));
        }
        if (reviewStatus != null) {
            sql.append(" AND review_status=?");
            args.add(reviewStatus);
        }
        return new Filter(sql.toString(), args);
    }

    private ReplayErrorCodeIgnoreRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReplayErrorCodeIgnoreRow(rs.getLong("id"), rs.getString("service_code"),
                rs.getString("old_resp_code"), rs.getString("new_resp_code"), rs.getInt("enabled"),
                ReplayConfigSqlSupport.localDateTime(rs, "created_at"),
                ReplayConfigSqlSupport.localDateTime(rs, "updated_at"), rs.getInt("version"),
                rs.getInt("review_status"), null, null, null, false, null);
    }

    private ReplayConfigOperationView mapOperation(ResultSet rs, int rowNum) throws SQLException {
        List<ReplayConfigFieldChange> changes = new ArrayList<>();
        addChange(changes, "service_code", "服务码", rs.getString("service_code"), rs.getString("new_service_code"));
        addChange(changes, "old_resp_code", "老核心错误码", rs.getString("old_resp_code"),
                rs.getString("new_old_resp_code"));
        addChange(changes, "new_resp_code", "新核心错误码", rs.getString("new_resp_code"),
                rs.getString("new_new_resp_code"));
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
