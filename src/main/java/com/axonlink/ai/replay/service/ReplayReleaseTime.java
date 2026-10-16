package com.axonlink.ai.replay.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** Local application release cutoff used only for reopening pending verification issues. */
public final class ReplayReleaseTime {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private ReplayReleaseTime() {}

    public static LocalDateTime resolve(String value, LocalDate batchDate) {
        if (value == null) {
            return batchDate.minusDays(1).atTime(12, 0);
        }
        String normalized = value.trim();
        if (!normalized.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}")) {
            throw invalid();
        }
        try {
            return LocalDateTime.parse(normalized.replace('T', ' '), FORMAT);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    public static boolean canReopen(LocalDateTime lastManualSaveAt, LocalDateTime releaseAt) {
        return lastManualSaveAt == null || !lastManualSaveAt.isAfter(releaseAt);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("发版时间不合法，请填写完整年月日时分秒，如2026-10-14 12:00:00");
    }
}
