package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditDetail;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConditionTree;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ReplayDatabaseComparisonLegacyAuditDetailAdapter {

    private static final String CONDITION_CODE = "whereCondition";
    private static final String LIMIT_CODE = "compareLimit";

    private final ReplayDatabaseComparisonConditionCodec conditionCodec;
    private final ReplayDatabaseComparisonAuditQueryConditionFormatter formatter;

    public ReplayDatabaseComparisonLegacyAuditDetailAdapter() {
        this.conditionCodec = new ReplayDatabaseComparisonConditionCodec();
        this.formatter = new ReplayDatabaseComparisonAuditQueryConditionFormatter(
                new ReplayDatabaseComparisonConditionLabeler());
    }

    public List<ReplayDbCompareAuditDetail> adapt(List<ReplayDbCompareAuditDetail> details) {
        if (details == null || details.isEmpty()
                || details.stream().noneMatch(this::isLegacyScopeDetail)) {
            return details == null ? List.of() : details;
        }
        ReplayDbCompareAuditDetail first = details.stream()
                .filter(this::isLegacyScopeDetail)
                .findFirst()
                .orElseThrow();
        ReplayDbCompareAuditDetail condition = details.stream()
                .filter(detail -> CONDITION_CODE.equals(detail.fieldCode()))
                .findFirst()
                .orElse(null);
        ReplayDbCompareAuditDetail limit = details.stream()
                .filter(detail -> LIMIT_CODE.equals(detail.fieldCode()))
                .findFirst()
                .orElse(null);
        ReplayDbCompareAuditDetail merged = new ReplayDbCompareAuditDetail(
                first.id(), first.auditEventId(), first.detailOrder(), first.changeType(),
                "queryCondition", "查询条件",
                legacyScope(condition == null ? null : condition.beforeValue(),
                        limit == null ? null : limit.beforeValue()),
                legacyScope(condition == null ? null : condition.afterValue(),
                        limit == null ? null : limit.afterValue()),
                first.createdAt());

        List<ReplayDbCompareAuditDetail> adapted = new ArrayList<>();
        boolean inserted = false;
        for (ReplayDbCompareAuditDetail detail : details) {
            if (!isLegacyScopeDetail(detail)) {
                adapted.add(detail);
            } else if (!inserted) {
                adapted.add(merged);
                inserted = true;
            }
        }
        return List.copyOf(adapted);
    }

    private boolean isLegacyScopeDetail(ReplayDbCompareAuditDetail detail) {
        return CONDITION_CODE.equals(detail.fieldCode()) || LIMIT_CODE.equals(detail.fieldCode());
    }

    private String legacyScope(String conditionValue, String limitValue) {
        List<String> lines = new ArrayList<>();
        String condition = readableCondition(conditionValue);
        if (condition != null) {
            lines.add(condition);
        }
        String limit = readableLimit(limitValue);
        if (limit != null) {
            lines.add(limit);
        }
        return lines.isEmpty() ? "全表" : String.join("\n", lines);
    }

    private String readableCondition(String value) {
        String normalized = normalizedValue(value);
        if (normalized == null) {
            return null;
        }
        try {
            ReplayDbCompareConditionTree condition = conditionCodec.decode(normalized);
            String readable = formatter.format(condition, List.of(), null);
            return "全表".equals(readable) ? null : readable;
        } catch (RuntimeException exception) {
            return normalized.toLowerCase(Locale.ROOT).startsWith("where ")
                    ? normalized
                    : "where " + normalized;
        }
    }

    private String readableLimit(String value) {
        String normalized = normalizedValue(value);
        if (normalized == null) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT).startsWith("limit ")
                ? normalized
                : "limit " + normalized;
    }

    private String normalizedValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return "未配置".equals(normalized) || "全表".equals(normalized)
                ? null
                : normalized;
    }
}
