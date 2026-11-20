package com.axonlink.ai.replay.dto;

public record ReplayOneSideErrorCodeIgnoreCreateRequest(
        String serviceCode, String category, String ownership, String errorCodes, String ignoreReason) {
}
