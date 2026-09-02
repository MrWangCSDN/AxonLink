package com.axonlink.ai.replay.dto;

import java.util.List;

/** Replay issue groups and developer-owned transactions whose planned completion date the user may edit. */
public record ReplayIssuePlanDatePermissions(
        List<String> editableGroups,
        List<String> dateLimitBypassGroups,
        List<String> editableTransactionCodes) {
    public ReplayIssuePlanDatePermissions {
        editableGroups = editableGroups == null ? List.of() : List.copyOf(editableGroups);
        dateLimitBypassGroups = dateLimitBypassGroups == null
                ? List.of() : List.copyOf(dateLimitBypassGroups);
        editableTransactionCodes = editableTransactionCodes == null
                ? List.of() : List.copyOf(editableTransactionCodes);
    }
}
