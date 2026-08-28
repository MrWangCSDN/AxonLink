package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;

/** Lifecycle states for a replay issue. */
public enum ReplayIssueStatus {
    NEW("新建", false),
    OPEN("打开", true),
    NO_ACTION("无需处理", true),
    /** Legacy value retained so historical rows can still be read, but it is no longer selectable. */
    ANALYZING("分析中", false),
    DEFERRED("延后修复", true),
    PENDING_VERIFICATION("修复待验证", true),
    REOPENED("重新打开", false),
    FIXED("已修复", false);

    private final String displayValue;
    private final boolean manuallySelectable;

    ReplayIssueStatus(String displayValue, boolean manuallySelectable) {
        this.displayValue = displayValue;
        this.manuallySelectable = manuallySelectable;
    }

    @JsonValue
    public String displayValue() {
        return displayValue;
    }

    public String getDisplayValue() {
        return displayValue;
    }

    /** Alias useful to callers that model enum values as a named value. */
    public String value() {
        return displayValue;
    }

    public String getValue() {
        return displayValue;
    }

    public boolean isManuallySelectable() {
        return manuallySelectable;
    }

    public boolean isManualSelectable() {
        return manuallySelectable;
    }

    public boolean isUserSelectable() {
        return manuallySelectable;
    }

    public static List<ReplayIssueStatus> manuallySelectableValues() {
        return Arrays.stream(values()).filter(ReplayIssueStatus::isManuallySelectable).toList();
    }

    @JsonCreator
    public static ReplayIssueStatus fromDisplayValue(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.displayValue.equals(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知问题状态：" + value));
    }
}
