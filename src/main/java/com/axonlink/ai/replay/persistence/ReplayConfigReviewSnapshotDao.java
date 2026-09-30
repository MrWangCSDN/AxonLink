package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayConfigReviewSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 忽略配置「最后一次审核」查询。
 *
 * <p>业务表只存 {@code review_status}，审核人与审核时间在操作表的 REVIEW 记录里，
 * 这里按 config_id 批量取每行 <strong>最新一条</strong>（按审计 id 倒序，等价于 created_at 倒序）。
 */
@Repository
public class ReplayConfigReviewSnapshotDao {

    /** 配置类型 → 操作表名（白名单，防止表名拼接注入） */
    private static final Map<String, String> OPERATION_TABLES = Map.of(
            "unconditional-ignores", "dii_replay_unconditional_ignore_operation",
            "conditional-ignores", "dii_replay_conditional_rmove_operation",
            "error-code-ignores", "dii_replay_error_code_ignore_config_operation",
            "sort-fields", "dii_replay_sort_field_operation");

    private final JdbcTemplate jdbc;

    public ReplayConfigReviewSnapshotDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /**
     * 批量查询每行的最后一次审核快照。
     *
     * @param type      配置类型（unconditional-ignores / …）
     * @param configIds 业务记录 id 集合
     * @return config_id → 最后一次审核快照；没有 REVIEW 记录的 id 不会出现在结果里
     */
    public Map<Long, ReplayConfigReviewSnapshot> latestReviewByConfigIds(String type, Collection<Long> configIds) {
        Map<Long, ReplayConfigReviewSnapshot> result = new LinkedHashMap<>();
        if (configIds == null || configIds.isEmpty()) {
            return result;
        }
        String table = OPERATION_TABLES.get(type);
        if (table == null) {
            throw new IllegalArgumentException("未知的配置类型：" + type);
        }
        String placeholders = String.join(",", Collections.nCopies(configIds.size(), "?"));
        List<Object> args = new ArrayList<>(configIds);
        jdbc.query("SELECT o.config_id, o.operator_username, o.operator_real_name, o.created_at "
                        + "FROM " + table + " o "
                        + "JOIN (SELECT config_id, MAX(id) AS max_id FROM " + table
                        + " WHERE operation_type = 'REVIEW' AND config_id IN (" + placeholders + ")"
                        + " GROUP BY config_id) latest ON latest.max_id = o.id",
                rs -> {
                    result.put(rs.getLong("config_id"), new ReplayConfigReviewSnapshot(
                            rs.getString("operator_username"),
                            rs.getString("operator_real_name"),
                            ReplayConfigSqlSupport.localDateTime(rs, "created_at")));
                },
                args.toArray());
        return result;
    }
}
