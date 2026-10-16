package com.axonlink.ai.replay.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class ReplayReleaseTimeTest {
    @Test void defaultsToNoonOfPreviousBatchDayIncludingCalendarBoundaries() {
        assertEquals(LocalDateTime.of(2026,10,14,12,0),ReplayReleaseTime.resolve(null,LocalDate.of(2026,10,15)));
        assertEquals(LocalDateTime.of(2025,12,31,12,0),ReplayReleaseTime.resolve(null,LocalDate.of(2026,1,1)));
        assertEquals(LocalDateTime.of(2024,2,29,12,0),ReplayReleaseTime.resolve(null,LocalDate.of(2024,3,1)));
    }
    @Test void acceptsSecondPrecisionAndProtectsOnlySavesAfterRelease() {
        var release=ReplayReleaseTime.resolve("2026-10-14T12:00:00",LocalDate.of(2026,10,15));
        assertEquals(release,ReplayReleaseTime.resolve("2026-10-14 12:00:00",LocalDate.of(2026,10,15)));
        assertTrue(ReplayReleaseTime.canReopen(null,release));
        assertTrue(ReplayReleaseTime.canReopen(release.minusSeconds(1),release));
        assertTrue(ReplayReleaseTime.canReopen(release,release));
        assertFalse(ReplayReleaseTime.canReopen(release.plusSeconds(1),release));
    }
    @Test void rejectsIncompleteOrImpossibleDates() {
        for(var input:new String[]{"", "2026-02-30 12:00:00", "2026-10-14 12:00", "2026-10-14 24:00:00", "2026-10-14T12:00:00Z"}) {
            assertThrows(IllegalArgumentException.class,()->ReplayReleaseTime.resolve(input,LocalDate.of(2026,10,15)));
        }
    }
}
