package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayReportSummaryView(
        int schemaVersion,
        ReplayReportPeriod period,
        String family,
        String startBatchNo,
        String endBatchNo,
        String reportName,
        List<ReplayReportSummaryColumn> columns,
        List<ReplayReportSummaryRow> rows,
        ReplayReportSummaryRow totalRow) {

    public ReplayReportSummaryView {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
