package com.axonlink.ai.replay.dbcompare.dto;

public record ReplayDbComparePrimaryKeySyncResult(
        int scannedCount,
        int updatedCount,
        int addedFieldCount,
        int conflictCount) {
}
