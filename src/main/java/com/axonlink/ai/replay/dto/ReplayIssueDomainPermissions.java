package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayIssueDomainPermissions(List<String> editableDomains) {
    public ReplayIssueDomainPermissions {
        editableDomains = editableDomains == null ? List.of() : List.copyOf(editableDomains);
    }
}
