package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayIssuePlanDateChanges(long changeCount, List<ReplayIssuePlanDateChangeEntry> items) {
    public ReplayIssuePlanDateChanges {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
