package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareListPage(
        List<ReplayDbCompareListItem> items,
        int page,
        int size,
        long total) {

    public ReplayDbCompareListPage {
        items = List.copyOf(items);
    }
}
