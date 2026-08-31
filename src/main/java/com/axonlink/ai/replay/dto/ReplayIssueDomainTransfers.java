package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayIssueDomainTransfers(long transferCount, List<ReplayIssueDomainTransferEntry> items) {
    public ReplayIssueDomainTransfers {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
