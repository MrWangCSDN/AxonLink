package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareMetadataValidation(
        ReplayDbCompareMetadataStatus status,
        List<String> missingFieldNames,
        boolean primaryKeyChanged,
        List<String> missingPrimaryKeyNames,
        List<String> formerPrimaryKeyNames) {

    public ReplayDbCompareMetadataValidation {
        missingFieldNames = missingFieldNames == null ? List.of() : List.copyOf(missingFieldNames);
        missingPrimaryKeyNames = missingPrimaryKeyNames == null
                ? List.of() : List.copyOf(missingPrimaryKeyNames);
        formerPrimaryKeyNames = formerPrimaryKeyNames == null
                ? List.of() : List.copyOf(formerPrimaryKeyNames);
    }

    public static ReplayDbCompareMetadataValidation valid() {
        return of(ReplayDbCompareMetadataStatus.VALID, List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareMetadataValidation missingFields(List<String> names) {
        return of(ReplayDbCompareMetadataStatus.MISSING_FIELDS, names, List.of(), List.of());
    }

    public static ReplayDbCompareMetadataValidation tableMissing() {
        return of(ReplayDbCompareMetadataStatus.TABLE_MISSING, List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareMetadataValidation unavailable() {
        return of(ReplayDbCompareMetadataStatus.UNAVAILABLE, List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareMetadataValidation of(
            ReplayDbCompareMetadataStatus status,
            List<String> missingFieldNames,
            List<String> missingPrimaryKeyNames,
            List<String> formerPrimaryKeyNames) {
        return new ReplayDbCompareMetadataValidation(
                status,
                missingFieldNames,
                false,
                List.of(),
                List.of());
    }
}
