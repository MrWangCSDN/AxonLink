package com.axonlink.ai.replay.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ReplayReportSummaryRow(
        String domain,
        String rowType,
        Map<String, Object> values) {

    public ReplayReportSummaryRow {
        values = values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
