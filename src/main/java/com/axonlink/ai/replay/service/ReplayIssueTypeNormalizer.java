package com.axonlink.ai.replay.service;

public final class ReplayIssueTypeNormalizer {

    private static final String LEGACY_RULE_DIFFERENCE = "规则差异问题";
    private static final String RULE_DIFFERENCE = "规则性差异问题";

    private ReplayIssueTypeNormalizer() {
    }

    public static String normalize(String value) {
        return value != null && LEGACY_RULE_DIFFERENCE.equals(value.trim()) ? RULE_DIFFERENCE : value;
    }
}
