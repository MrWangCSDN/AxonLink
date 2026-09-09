package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

public record ReplayDailyBatch(
        String batchNo,
        String family,
        LocalDateTime importedAt,
        String previousBatchNo,
        boolean generated,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime generatedAt,
        String mailStatus,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime mailSentAt,
        String mailFailureMessage) {

    private static final Pattern STANDARD_BATCH = Pattern.compile("^(RPT|DZ).+");

    public ReplayDailyBatch(String batchNo, String family, LocalDateTime importedAt, String previousBatchNo) {
        this(batchNo, family, importedAt, previousBatchNo, false, null, "UNSENT", null, null);
    }

    public ReplayDailyBatch(String batchNo, String family, LocalDateTime importedAt, String previousBatchNo,
                            boolean generated, LocalDateTime generatedAt) {
        this(batchNo, family, importedAt, previousBatchNo, generated, generatedAt, "UNSENT", null, null);
    }

    public static boolean isStandardBatchNo(String batchNo) {
        return batchNo != null && STANDARD_BATCH.matcher(batchNo).matches();
    }

    @JsonProperty("canGenerate")
    public boolean canGenerate() {
        return previousBatchNo != null;
    }
}
