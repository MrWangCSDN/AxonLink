package com.axonlink.ai.replay.dto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueStatusTest {

    @Test
    void exposesTheSixChineseDisplayValues() {
        assertEquals("打开", ReplayIssueStatus.OPEN.displayValue());
        assertEquals("分析中", ReplayIssueStatus.ANALYZING.displayValue());
        assertEquals("延后修复", ReplayIssueStatus.DEFERRED.displayValue());
        assertEquals("修复待验证", ReplayIssueStatus.PENDING_VERIFICATION.displayValue());
        assertEquals("重新打开", ReplayIssueStatus.REOPENED.displayValue());
        assertEquals("已修复", ReplayIssueStatus.FIXED.displayValue());
    }

    @Test
    void onlyTwoStatusesAreManuallySelectable() {
        assertFalse(ReplayIssueStatus.ANALYZING.isManuallySelectable());
        assertTrue(ReplayIssueStatus.DEFERRED.isManuallySelectable());
        assertTrue(ReplayIssueStatus.PENDING_VERIFICATION.isManuallySelectable());
        assertFalse(ReplayIssueStatus.OPEN.isManuallySelectable());
        assertFalse(ReplayIssueStatus.REOPENED.isManuallySelectable());
        assertFalse(ReplayIssueStatus.FIXED.isManuallySelectable());
        assertEquals(2, Arrays.stream(ReplayIssueStatus.values())
                .filter(ReplayIssueStatus::isManuallySelectable).count());
    }

    @Test
    void updateRequestRejectsAnalysisAndSolutionOverFiveHundredCharacters() {
        String tooLong = "x".repeat(501);
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayIssueUpdateRequest(ReplayIssueStatus.ANALYZING,
                        "代码问题", tooLong, "处理方案", "sunhy1"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayIssueUpdateRequest(ReplayIssueStatus.ANALYZING,
                        "代码问题", "初步分析", tooLong, "sunhy1"));
    }
}
