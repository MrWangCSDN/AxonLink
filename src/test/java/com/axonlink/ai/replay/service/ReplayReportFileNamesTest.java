package com.axonlink.ai.replay.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayReportFileNamesTest {

    @Test
    void formatsDailyNamesFromBatchFamilyAndDate() {
        assertEquals("查询日报-20260916.xlsx",
                ReplayReportFileNames.daily("RPT20260916-102753-6470"));
        assertEquals("账务日报-20260916.xlsx",
                ReplayReportFileNames.daily("DZ20260916-01"));
    }

    @Test
    void formatsWeeklyNamesFromStartAndEndBatchDates() {
        assertEquals("查询周报(20260909-20260916).xlsx",
                ReplayReportFileNames.weekly("RPT20260909-01", "RPT20260916-102753-6470"));
        assertEquals("账务周报(20260909-20260916).xlsx",
                ReplayReportFileNames.weekly("DZ20260909-01", "DZ20260916-01"));
    }

    @Test
    void rejectsMalformedOrCrossFamilyBatches() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplayReportFileNames.daily("BATCH-20260916"));
        assertThrows(IllegalArgumentException.class,
                () -> ReplayReportFileNames.weekly("RPT20260909-01", "DZ20260916-01"));
    }
}
