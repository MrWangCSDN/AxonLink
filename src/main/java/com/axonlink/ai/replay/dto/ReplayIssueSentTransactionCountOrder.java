package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Supported numeric ordering for sent transaction totals; nulls always sort last. */
public enum ReplayIssueSentTransactionCountOrder {
    ASC,
    DESC;

    @JsonCreator
    public static ReplayIssueSentTransactionCountOrder parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("发送交易量排序仅支持 ASC 或 DESC");
        }
    }
}
