package com.axonlink.ai.replay.dto;

public record ReplayMessageSequenceIgnoreCreateRequest(
        String serviceCode, String category, String messageSequences, String ignoreReason) {
}
