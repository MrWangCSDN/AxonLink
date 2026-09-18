package com.axonlink.ai.replay.dbcompare.dto;

import java.util.List;

public record ReplayDbCompareMetadataValidation(
        ReplayDbCompareMetadataStatus status,
        List<String> missingFieldNames,
        List<String> missingConditionFieldNames,
        boolean primaryKeyChanged,
        List<String> missingPrimaryKeyNames,
        List<String> formerPrimaryKeyNames,
        boolean orderingPrimaryKeyChanged,
        List<String> savedOrderingPrimaryKeyNames,
        List<String> currentOrderingPrimaryKeyNames) {

    public ReplayDbCompareMetadataValidation {
        missingFieldNames = missingFieldNames == null ? List.of() : List.copyOf(missingFieldNames);
        missingConditionFieldNames = missingConditionFieldNames == null
                ? List.of() : List.copyOf(missingConditionFieldNames);
        missingPrimaryKeyNames = missingPrimaryKeyNames == null
                ? List.of() : List.copyOf(missingPrimaryKeyNames);
        formerPrimaryKeyNames = formerPrimaryKeyNames == null
                ? List.of() : List.copyOf(formerPrimaryKeyNames);
        savedOrderingPrimaryKeyNames = savedOrderingPrimaryKeyNames == null
                ? List.of() : List.copyOf(savedOrderingPrimaryKeyNames);
        currentOrderingPrimaryKeyNames = currentOrderingPrimaryKeyNames == null
                ? List.of() : List.copyOf(currentOrderingPrimaryKeyNames);
    }

    public static ReplayDbCompareMetadataValidation valid() {
        return of(ReplayDbCompareMetadataStatus.VALID, List.of(), List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareMetadataValidation missingFields(List<String> names) {
        return of(ReplayDbCompareMetadataStatus.MISSING_FIELDS, names, List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareMetadataValidation tableMissing() {
        return of(ReplayDbCompareMetadataStatus.TABLE_MISSING, List.of(), List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareMetadataValidation unavailable() {
        return of(ReplayDbCompareMetadataStatus.UNAVAILABLE, List.of(), List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareMetadataValidation of(
            ReplayDbCompareMetadataStatus status,
            List<String> missingFieldNames,
            List<String> missingConditionFieldNames,
            List<String> missingPrimaryKeyNames,
            List<String> formerPrimaryKeyNames) {
        return new ReplayDbCompareMetadataValidation(
                status,
                missingFieldNames,
                missingConditionFieldNames,
                false,
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of());
    }

    public ReplayDbCompareMetadataValidation withOrderingPrimaryKeyDrift(
            List<String> savedNames, List<String> currentNames) {
        boolean changed = !savedNames.isEmpty() && !savedNames.equals(currentNames);
        ReplayDbCompareMetadataStatus effectiveStatus = status == ReplayDbCompareMetadataStatus.VALID && changed
                ? ReplayDbCompareMetadataStatus.ORDERING_PRIMARY_KEY_CHANGED : status;
        return new ReplayDbCompareMetadataValidation(
                effectiveStatus, missingFieldNames, missingConditionFieldNames,
                primaryKeyChanged, missingPrimaryKeyNames, formerPrimaryKeyNames,
                changed, savedNames, currentNames);
    }

    public static ReplayDbCompareMetadataValidation of(
            ReplayDbCompareMetadataStatus status,
            List<String> missingFieldNames,
            List<String> missingPrimaryKeyNames,
            List<String> formerPrimaryKeyNames) {
        return of(status, missingFieldNames, List.of(), missingPrimaryKeyNames, formerPrimaryKeyNames);
    }
}
