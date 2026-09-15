package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareListItem(
        long id,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerName,
        LocalDate registeredDate,
        long version,
        int fieldCount,
        List<String> fieldPreview,
        ReplayDbCompareMetadataValidation metadataValidation) {

    public ReplayDbCompareListItem {
        fieldPreview = fieldPreview == null ? List.of() : List.copyOf(fieldPreview);
    }

    public ReplayDbCompareListItem(
            long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserUsername, String reviserName,
            String groupOwnerEmpNo, String groupOwnerName, LocalDate registeredDate,
            long version, int fieldCount, List<String> fieldPreview) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, reviserUsername,
                reviserName, groupOwnerEmpNo, groupOwnerName, registeredDate, version,
                fieldCount, fieldPreview, null);
    }

    public ReplayDbCompareListItem(
            long id, String schemaName, String tableName, String tableComment, String domainName,
            String reviserEmpNo, String reviserName, String groupOwnerEmpNo, String groupOwnerName,
            LocalDate registeredDate, long version, int fieldCount, List<String> fieldPreview) {
        this(id, schemaName, tableName, tableComment, domainName, reviserEmpNo, null, reviserName,
                groupOwnerEmpNo, groupOwnerName, registeredDate, version, fieldCount, fieldPreview, null);
    }
}
