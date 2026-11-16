package com.axonlink.ai.replay.dto;

public record ReplayMessageSequenceIgnoreUpdateRequest(
        String serviceCode, String category, String messageSequence, String ignoreReason) {
}
