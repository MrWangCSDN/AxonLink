package com.axonlink.ai.replay.dbissue.report;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An immutable batch report with the registration version used for its historical table metrics. */
public record ReplayDatabaseIssueReport(
        String batchNo,
        List<String> issueTypes,
        List<Row> rows,
        Row total,
        List<String> notes,
        boolean tableMetricsComplete,
        String registrationVersionNo) {
    public ReplayDatabaseIssueReport(String batchNo, List<String> issueTypes, List<Row> rows, Row total, List<String> notes) {
        this(batchNo, issueTypes, rows, total, notes, true, null);
    }

    public ReplayDatabaseIssueReport(String batchNo, List<String> issueTypes, List<Row> rows, Row total,
                                     List<String> notes, boolean tableMetricsComplete) {
        this(batchNo, issueTypes, rows, total, notes, tableMetricsComplete, null);
    }

    public ReplayDatabaseIssueReport {
        issueTypes = List.copyOf(issueTypes);
        rows = List.copyOf(rows);
        notes = List.copyOf(notes);
    }

    /** Keeps field observations separate from original differing records. A null row count denotes missing source facts. */
    public record Row(
            String domain,
            long total528,
            long totalCcbs,
            long only528Count,
            long onlyCcbsCount,
            long fieldDifferenceCount,
            long comparedTableCount,
            Long fieldCount,
            long identicalTableCount,
            long issueCount,
            long unprocessedCount,
            Map<String, Long> typeCounts,
            Long sameKeyDifferentRowCount) {
        public Row(String domain, long total528, long totalCcbs, long only528Count, long onlyCcbsCount,
                   long fieldDifferenceCount, long comparedTableCount, Long fieldCount, long identicalTableCount,
                   long issueCount, long unprocessedCount, Map<String, Long> typeCounts) {
            this(domain, total528, totalCcbs, only528Count, onlyCcbsCount, fieldDifferenceCount, comparedTableCount,
                    fieldCount, identicalTableCount, issueCount, unprocessedCount, typeCounts, null);
        }

        public Row {
            typeCounts = Collections.unmodifiableMap(new LinkedHashMap<>(typeCounts));
        }
    }
}
