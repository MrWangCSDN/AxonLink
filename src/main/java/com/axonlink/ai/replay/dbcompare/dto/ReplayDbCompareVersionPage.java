package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareVersionPage(
        List<ReplayDbCompareVersionSummary> items,
        int page,
        int size,
        long total) {

    public ReplayDbCompareVersionPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
