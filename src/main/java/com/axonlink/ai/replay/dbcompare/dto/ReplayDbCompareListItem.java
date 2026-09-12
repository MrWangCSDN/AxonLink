package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareListItem(
        long id,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String ownerEmpNo,
        String ownerName,
        String groupName,
        LocalDate registeredDate,
        long version,
        int fieldCount,
        List<String> fieldPreview) {

    public ReplayDbCompareListItem {
        fieldPreview = fieldPreview == null ? List.of() : List.copyOf(fieldPreview);
    }
}
