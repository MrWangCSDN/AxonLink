package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareVersionTablePage(
        List<ReplayDbCompareVersionTableItem> items,
        int page,
        int size,
        long total) {

    public ReplayDbCompareVersionTablePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
