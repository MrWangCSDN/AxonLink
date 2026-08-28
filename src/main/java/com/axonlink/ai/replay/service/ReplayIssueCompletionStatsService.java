package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueCompletionCategory;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionCounts;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionDashboard;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionDatePoint;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionDatePointsResponse;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionDeveloperRow;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionGroupRow;
import com.axonlink.ai.replay.dto.ReplayIssueCompletionIssuePage;
import com.axonlink.ai.replay.persistence.ReplayIssueCompletionStatsDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalizes the discrete date range and builds completion dashboard hierarchy. */
@Service
public class ReplayIssueCompletionStatsService {
    private final ReplayIssueCompletionStatsDao dao;
    private final Clock clock;

    @Autowired
    public ReplayIssueCompletionStatsService(ReplayIssueCompletionStatsDao dao) {
        this(dao, Clock.systemDefaultZone());
    }

    public ReplayIssueCompletionStatsService(ReplayIssueCompletionStatsDao dao, Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    public ReplayIssueCompletionDatePointsResponse datePoints() {
        List<ReplayIssueCompletionDatePoint> points = dao.findDatePoints();
        if (points.isEmpty()) {
            return new ReplayIssueCompletionDatePointsResponse(List.of(), null, null);
        }
        int defaultStart = Math.max(0, points.size() - 3);
        return new ReplayIssueCompletionDatePointsResponse(List.copyOf(points),
                points.get(defaultStart).date(), points.get(points.size() - 1).date());
    }

    public ReplayIssueCompletionDashboard dashboard(String startDate, String endDate) {
        List<ReplayIssueCompletionDatePoint> points = dao.findDatePoints();
        LocalDate today = LocalDate.now(clock);
        if (points.isEmpty()) {
            if (hasText(startDate) || hasText(endDate)) throw new ReplayIssueCompletionRangeException();
            return new ReplayIssueCompletionDashboard(null, null, today,
                    ReplayIssueCompletionCounts.of(0, 0, 0, 0), List.of());
        }
        NormalizedRange range = normalize(points, startDate, endDate);
        List<ReplayIssueCompletionStatsDao.CompletionAggregateRow> aggregates =
                dao.aggregate(range.startDate(), range.endDate(), today);
        List<ReplayIssueCompletionGroupRow> groups = groups(aggregates);
        ReplayIssueCompletionCounts summary = sum(groups.stream().map(ReplayIssueCompletionGroupRow::counts).toList());
        verifyReconciliation(groups, summary);
        return new ReplayIssueCompletionDashboard(range.startDate(), range.endDate(), today, summary, groups);
    }

    public ReplayIssueCompletionIssuePage issues(String startDate, String endDate, String groupName,
                                                 String matchedDeveloper, String category,
                                                 int limit, int offset) {
        if (!hasText(groupName)) throw new IllegalArgumentException("领域不能为空");
        ReplayIssueCompletionCategory parsedCategory;
        try {
            parsedCategory = ReplayIssueCompletionCategory.valueOf(category == null ? "" : category.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("完成情况分类不合法");
        }
        if (limit < 1 || limit > 200 || offset < 0) {
            throw new IllegalArgumentException("分页参数不合法");
        }
        List<ReplayIssueCompletionDatePoint> points = dao.findDatePoints();
        LocalDate today = LocalDate.now(clock);
        if (points.isEmpty()) {
            if (hasText(startDate) || hasText(endDate)) throw new ReplayIssueCompletionRangeException();
            return new ReplayIssueCompletionIssuePage(0, List.of(), limit, offset, today);
        }
        NormalizedRange range = normalize(points, startDate, endDate);
        return dao.findIssues(range.startDate(), range.endDate(), today, groupName.trim(),
                hasText(matchedDeveloper) ? matchedDeveloper.trim() : null,
                parsedCategory, limit, offset);
    }

    private static NormalizedRange normalize(List<ReplayIssueCompletionDatePoint> points,
                                             String startText, String endText) {
        LocalDate first = points.get(0).date();
        LocalDate last = points.get(points.size() - 1).date();
        if (!hasText(startText) && !hasText(endText)) {
            return new NormalizedRange(points.get(Math.max(0, points.size() - 3)).date(), last);
        }
        LocalDate requestedStart = hasText(startText) ? parse(startText) : first;
        LocalDate requestedEnd = hasText(endText) ? parse(endText) : last;
        if (requestedStart.isBefore(first) || requestedStart.isAfter(last)
                || requestedEnd.isBefore(first) || requestedEnd.isAfter(last)) {
            throw new ReplayIssueCompletionRangeException();
        }
        LocalDate effectiveStart = points.stream().map(ReplayIssueCompletionDatePoint::date)
                .filter(date -> !date.isBefore(requestedStart)).findFirst()
                .orElseThrow(ReplayIssueCompletionRangeException::new);
        LocalDate effectiveEnd = points.stream().map(ReplayIssueCompletionDatePoint::date)
                .filter(date -> !date.isAfter(requestedEnd)).reduce((left, right) -> right)
                .orElseThrow(ReplayIssueCompletionRangeException::new);
        if (effectiveStart.isAfter(effectiveEnd)) throw new ReplayIssueCompletionRangeException();
        return new NormalizedRange(effectiveStart, effectiveEnd);
    }

    private static LocalDate parse(String text) {
        String value = text == null ? "" : text.trim();
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new ReplayIssueCompletionRangeException();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ReplayIssueCompletionRangeException();
        }
    }

    private static List<ReplayIssueCompletionGroupRow> groups(
            List<ReplayIssueCompletionStatsDao.CompletionAggregateRow> aggregates) {
        Map<String, List<ReplayIssueCompletionStatsDao.CompletionAggregateRow>> byGroup = new LinkedHashMap<>();
        for (ReplayIssueCompletionStatsDao.CompletionAggregateRow row : aggregates) {
            byGroup.computeIfAbsent(row.groupName(), ignored -> new ArrayList<>()).add(row);
        }
        List<ReplayIssueCompletionGroupRow> groups = new ArrayList<>(byGroup.size());
        byGroup.forEach((groupName, rows) -> {
            List<ReplayIssueCompletionDeveloperRow> developers = rows.stream()
                    .map(row -> new ReplayIssueCompletionDeveloperRow(row.matchedDeveloper(), row.counts()))
                    .toList();
            groups.add(new ReplayIssueCompletionGroupRow(groupName,
                    sum(rows.stream().map(ReplayIssueCompletionStatsDao.CompletionAggregateRow::counts).toList()),
                    developers));
        });
        return List.copyOf(groups);
    }

    private static ReplayIssueCompletionCounts sum(List<ReplayIssueCompletionCounts> rows) {
        long onTime = 0;
        long late = 0;
        long unfinished = 0;
        long overdue = 0;
        for (ReplayIssueCompletionCounts row : rows) {
            onTime += row.onTimeFixedCount();
            late += row.lateFixedCount();
            unfinished += row.unfinishedCount();
            overdue += row.overdueUnfinishedCount();
        }
        return ReplayIssueCompletionCounts.of(onTime, late, unfinished, overdue);
    }

    private static void verifyReconciliation(List<ReplayIssueCompletionGroupRow> groups,
                                             ReplayIssueCompletionCounts summary) {
        for (ReplayIssueCompletionGroupRow group : groups) {
            ReplayIssueCompletionCounts developerTotal = sum(group.developers().stream()
                    .map(ReplayIssueCompletionDeveloperRow::counts).toList());
            if (!group.counts().equals(developerTotal)) {
                throw new IllegalStateException("计划完成情况统计口径不一致");
            }
        }
        ReplayIssueCompletionCounts groupTotal = sum(groups.stream()
                .map(ReplayIssueCompletionGroupRow::counts).toList());
        if (!summary.equals(groupTotal)) throw new IllegalStateException("计划完成情况统计口径不一致");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record NormalizedRange(LocalDate startDate, LocalDate endDate) {
    }
}
