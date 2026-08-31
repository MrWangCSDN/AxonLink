package com.axonlink.ai.replay.dto;

import java.util.List;

/** Replay issue groups and developer-owned transactions whose planned completion date the user may edit. */
public record ReplayIssuePlanDatePermissions(
        List<String> editableGroups,
        List<String> editableTransactionCodes) {
    public ReplayIssuePlanDatePermissions {
        editableGroups = editableGroups == null ? List.of() : List.copyOf(editableGroups);
        editableTransactionCodes = editableTransactionCodes == null
                ? List.of() : List.copyOf(editableTransactionCodes);
    }
}
