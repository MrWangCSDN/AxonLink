package com.axonlink.ai.replay.dto;

import java.util.List;

public record ReplayIssueDomainPermissions(
        List<String> editableDomains,
        List<String> transferLimitBypassDomains) {
    public ReplayIssueDomainPermissions {
        editableDomains = editableDomains == null ? List.of() : List.copyOf(editableDomains);
        transferLimitBypassDomains = transferLimitBypassDomains == null
                ? List.of() : List.copyOf(transferLimitBypassDomains);
    }
}
