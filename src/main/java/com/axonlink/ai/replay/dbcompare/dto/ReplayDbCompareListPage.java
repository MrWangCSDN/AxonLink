package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareListPage(
        List<ReplayDbCompareListItem> items,
        int page,
        int size,
        long total,
        long globalTableCount,
        long globalFieldCount,
        long globalCustomSqlCount) {

    public ReplayDbCompareListPage(List<ReplayDbCompareListItem> items, int page, int size,
            long total, long globalTableCount, long globalFieldCount) {
        this(items, page, size, total, globalTableCount, globalFieldCount, 0);
    }

    public ReplayDbCompareListPage {
        items = List.copyOf(items);
    }
}
