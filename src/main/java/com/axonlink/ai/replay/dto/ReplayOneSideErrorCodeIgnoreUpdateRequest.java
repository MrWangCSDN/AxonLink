package com.axonlink.ai.replay.dto;

public record ReplayOneSideErrorCodeIgnoreUpdateRequest(
        String serviceCode, String category, String ownership, String errorCode, String ignoreReason) {
}
