package com.axonlink.ai.replay.dto;

import java.util.List;

/** Replay issue groups whose planned completion date the current user may edit. */
public record ReplayIssuePlanDatePermissions(List<String> editableGroups) {
    public ReplayIssuePlanDatePermissions {
        editableGroups = editableGroups == null ? List.of() : List.copyOf(editableGroups);
    }
}
