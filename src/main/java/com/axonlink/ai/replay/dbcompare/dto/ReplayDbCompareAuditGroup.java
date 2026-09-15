package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ReplayDbCompareAuditGroup(
        String schemaName,
        String tableName,
        String tableComment,
        long matchedEventCount,
        String latestOperatorEmpNo,
        String latestOperatorUsername,
        String latestOperatorName,
        LocalDateTime latestOperatedAt,
        List<ReplayDbCompareAuditEvent> events) {

    public ReplayDbCompareAuditGroup {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public ReplayDbCompareAuditGroup(
            String schemaName, String tableName, String tableComment, long matchedEventCount,
            String latestOperatorEmpNo, String latestOperatorName, LocalDateTime latestOperatedAt,
            List<ReplayDbCompareAuditEvent> events) {
        this(schemaName, tableName, tableComment, matchedEventCount, latestOperatorEmpNo, null,
                latestOperatorName, latestOperatedAt, events);
    }
}
