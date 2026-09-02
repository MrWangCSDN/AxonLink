package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayIssueCompletionCategory;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionCounts;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionDatePoint;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionIssueItem;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionIssuePage;
import com.axonlink.ai.replay.dto.ReplayIssueReplayType;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Read-only planned-completion statistics over the current replay issue projection. */
@Repository
public class ReplayIssueCompletionStatsDao {
    public static final String UNMATCHED_DEVELOPER = "未匹配负责人";

    private static final String CLASSIFIED_SOURCE_TEMPLATE = """
            SELECT i.id, i.issue_id, i.transaction_code, i.transaction_name, i.issue_status,
                   i.planned_completion_date, i.defect_repair_date, i.issue_key,
                   %1$s AS group_name,
                   COALESCE(NULLIF(TRIM(tp.developer), ''), '未匹配负责人') AS matched_developer,
                   CASE
                     WHEN i.defect_repair_date IS NOT NULL
                          AND i.defect_repair_date <= i.planned_completion_date THEN 'ON_TIME_FIXED'
                     WHEN i.defect_repair_date IS NOT NULL
                          AND i.defect_repair_date > i.planned_completion_date THEN 'LATE_FIXED'
                     WHEN i.defect_repair_date IS NULL
                          AND ? <= i.planned_completion_date THEN 'UNFINISHED'
                     ELSE 'OVERDUE_UNFINISHED'
                   END AS completion_category
              FROM dii_replay_issue i
              LEFT JOIN dii_replay_transaction_person tp
                ON tp.old_transaction_code = i.transaction_code
             WHERE i.planned_completion_date IS NOT NULL
               AND i.planned_completion_date BETWEEN ? AND ?
               %2$s
            """;

    private final JdbcTemplate jdbc;

    public ReplayIssueCompletionStatsDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReplayIssueCompletionDatePoint> findDatePoints() {
        return jdbc.query("""
                SELECT planned_completion_date, COUNT(*) AS planned_count
                  FROM dii_replay_issue
                 WHERE planned_completion_date IS NOT NULL
                 GROUP BY planned_completion_date
                 HAVING COUNT(*) > 0
                 ORDER BY planned_completion_date
                """, (rs, rowNum) -> new ReplayIssueCompletionDatePoint(
                rs.getDate("planned_completion_date").toLocalDate(), rs.getLong("planned_count")));
    }

    public List<CompletionAggregateRow> aggregate(LocalDate startDate, LocalDate endDate, LocalDate today) {
        return aggregate(startDate, endDate, today, "domain", ReplayIssueReplayType.ALL);
    }

    public List<CompletionAggregateRow> aggregate(LocalDate startDate, LocalDate endDate, LocalDate today,
                                                   String groupBy) {
        return aggregate(startDate, endDate, today, groupBy, ReplayIssueReplayType.ALL);
    }

    public List<CompletionAggregateRow> aggregate(LocalDate startDate, LocalDate endDate, LocalDate today,
                                                   String groupBy, ReplayIssueReplayType replayType) {
        ClassifiedSource classifiedSource = classifiedSource(groupBy, replayType);
        String sql = """
                SELECT group_name, matched_developer,
                       SUM(CASE WHEN completion_category='ON_TIME_FIXED' THEN 1 ELSE 0 END) AS on_time_count,
                       SUM(CASE WHEN completion_category='LATE_FIXED' THEN 1 ELSE 0 END) AS late_count,
                       SUM(CASE WHEN completion_category='UNFINISHED' THEN 1 ELSE 0 END) AS unfinished_count,
                       SUM(CASE WHEN completion_category='OVERDUE_UNFINISHED' THEN 1 ELSE 0 END) AS overdue_count,
                       SUM(CASE WHEN defect_repair_date IS NULL AND issue_status='修复待验证'
                                THEN 1 ELSE 0 END) AS pending_verification_count
                  FROM (
                """ + classifiedSource.sql() + """
                       ) classified
                 GROUP BY group_name, matched_developer
                 ORDER BY group_name, matched_developer
                """;
        List<Object> args = new ArrayList<>(List.of(sqlDate(today), sqlDate(startDate), sqlDate(endDate)));
        args.addAll(classifiedSource.args());
        return jdbc.query(sql, (rs, rowNum) -> new CompletionAggregateRow(
                        rs.getString("group_name"), rs.getString("matched_developer"),
                        ReplayIssueCompletionCounts.of(rs.getLong("on_time_count"), rs.getLong("late_count"),
                                rs.getLong("unfinished_count"), rs.getLong("overdue_count"),
                                rs.getLong("pending_verification_count"))),
                args.toArray());
    }

    public ReplayIssueCompletionIssuePage findIssues(LocalDate startDate, LocalDate endDate, LocalDate today,
                                                      String groupName, String matchedDeveloper,
                                                      ReplayIssueCompletionCategory category,
                                                      int limit, int offset) {
        return findIssues(startDate, endDate, today, "domain", groupName, matchedDeveloper, category, limit, offset);
    }

    public ReplayIssueCompletionIssuePage findIssues(LocalDate startDate, LocalDate endDate, LocalDate today,
                                                      String groupBy, String groupName, String matchedDeveloper,
                                                      ReplayIssueCompletionCategory category,
                                                      int limit, int offset) {
        return findIssues(startDate, endDate, today, groupBy, ReplayIssueReplayType.ALL,
                groupName, matchedDeveloper, category, limit, offset);
    }

    public ReplayIssueCompletionIssuePage findIssues(LocalDate startDate, LocalDate endDate, LocalDate today,
                                                      String groupBy, ReplayIssueReplayType replayType,
                                                      String groupName, String matchedDeveloper,
                                                      ReplayIssueCompletionCategory category,
                                                      int limit, int offset) {
        ClassifiedSource classifiedSource = classifiedSource(groupBy, replayType);
        StringBuilder predicate = new StringBuilder(" WHERE group_name=? AND completion_category=?");
        List<Object> baseArgs = new ArrayList<>(List.of(sqlDate(today), sqlDate(startDate), sqlDate(endDate)));
        baseArgs.addAll(classifiedSource.args());
        baseArgs.add(groupName);
        baseArgs.add(category.name());
        if (matchedDeveloper != null) {
            predicate.append(" AND matched_developer=?");
            baseArgs.add(matchedDeveloper);
        }

        Long totalValue = jdbc.queryForObject("SELECT COUNT(*) FROM (" + classifiedSource.sql() + ") classified"
                + predicate, Long.class, baseArgs.toArray());
        long total = totalValue == null ? 0L : totalValue;

        List<Object> pageArgs = new ArrayList<>(baseArgs);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<ReplayIssueCompletionIssueItem> items = jdbc.query("SELECT * FROM (" + classifiedSource.sql()
                        + ") classified" + predicate + " ORDER BY planned_completion_date,id LIMIT ? OFFSET ?",
                (rs, rowNum) -> new ReplayIssueCompletionIssueItem(
                        rs.getLong("id"), rs.getString("issue_id"), rs.getString("transaction_code"),
                        rs.getString("transaction_name"), ReplayIssueStatus.fromDisplayValue(rs.getString("issue_status")),
                        localDate(rs.getDate("planned_completion_date")), localDate(rs.getDate("defect_repair_date")),
                        rs.getString("matched_developer"), rs.getString("issue_key")), pageArgs.toArray());
        return new ReplayIssueCompletionIssuePage(total, items, limit, offset, today);
    }

    public static String normalizeGroupBy(String groupBy) {
        String normalized = groupBy == null || groupBy.isBlank() ? "domain" : groupBy.trim();
        if (!"domain".equals(normalized) && !"issueDomain".equals(normalized)) {
            throw new IllegalArgumentException("统计分组口径仅支持 domain 或 issueDomain");
        }
        return normalized;
    }

    private static ClassifiedSource classifiedSource(String groupBy, ReplayIssueReplayType replayType) {
        String expression = switch (normalizeGroupBy(groupBy)) {
            case "domain" -> "i.group_name";
            case "issueDomain" -> "COALESCE(NULLIF(TRIM(i.issue_domain), ''), i.group_name)";
            default -> throw new IllegalStateException("不可达的统计分组口径");
        };
        if (replayType == null || replayType == ReplayIssueReplayType.ALL) {
            return new ClassifiedSource(CLASSIFIED_SOURCE_TEMPLATE.formatted(expression, ""), List.of());
        }
        String predicate = "AND EXISTS (SELECT 1 FROM dii_replay_issue_occurrence_batch replay_type_ob "
                + "WHERE replay_type_ob.replay_issue_id=i.id AND replay_type_ob.batch_name LIKE ?)";
        return new ClassifiedSource(CLASSIFIED_SOURCE_TEMPLATE.formatted(expression, predicate),
                List.of(replayType.batchPrefix() + "%"));
    }

    private static Date sqlDate(LocalDate value) {
        return Date.valueOf(value);
    }

    private static LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    public record CompletionAggregateRow(
            String groupName,
            String matchedDeveloper,
            ReplayIssueCompletionCounts counts) {
    }

    private record ClassifiedSource(String sql, List<Object> args) {
    }
}
