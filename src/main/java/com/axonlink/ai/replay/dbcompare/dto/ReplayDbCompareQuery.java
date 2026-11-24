package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareQuery(
        String tableKeyword,
        String fieldKeyword,
        List<String> domains,
        List<String> reviserEmpNos,
        List<String> groupOwnerEmpNos,
        LocalDate registeredDateFrom,
        LocalDate registeredDateTo,
        int page,
        int size,
        List<ReplayDbCompareMetadataStatus> metadataStatuses,
        List<String> tableNames,
        List<String> fieldNames,
        List<LocalDate> registeredDates,
        List<String> whereConditionValues,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentUsing = ReplayDbCompareMaxPartitionNumDeserializer.class)
        List<Integer> maxPartitionNums) {

    public ReplayDbCompareQuery(
        String tableKeyword,
        String fieldKeyword,
        List<String> domains,
        List<String> reviserEmpNos,
        List<String> groupOwnerEmpNos,
        LocalDate registeredDateFrom,
        LocalDate registeredDateTo,
        int page,
        int size,
        List<ReplayDbCompareMetadataStatus> metadataStatuses,
        List<String> tableNames,
        List<String> fieldNames,
        List<LocalDate> registeredDates,
        List<String> whereConditionValues) {
        this(tableKeyword, fieldKeyword, domains, reviserEmpNos, groupOwnerEmpNos, registeredDateFrom, registeredDateTo, page, size, metadataStatuses, tableNames, fieldNames, registeredDates, whereConditionValues, List.of());
    }


    public ReplayDbCompareQuery {
        maxPartitionNums = maxPartitionNums == null ? List.of() : List.copyOf(maxPartitionNums);
        if (maxPartitionNums.stream().anyMatch(value -> value < 1 || value > 256)) {
            throw new IllegalArgumentException("最大HASH分区数必须为 1 到 256 的整数");
        }
        domains = copy(domains);
        reviserEmpNos = copy(reviserEmpNos);
        groupOwnerEmpNos = copy(groupOwnerEmpNos);
        metadataStatuses = metadataStatuses == null ? List.of() : List.copyOf(metadataStatuses);
        tableNames = copy(tableNames);
        fieldNames = copy(fieldNames);
        registeredDates = registeredDates == null ? List.of() : List.copyOf(registeredDates);
        whereConditionValues = copy(whereConditionValues);
    }

    public ReplayDbCompareQuery(
            String tableKeyword,
            String fieldKeyword,
            List<String> domains,
            List<String> reviserEmpNos,
            List<String> groupOwnerEmpNos,
            LocalDate registeredDateFrom,
            LocalDate registeredDateTo,
            int page,
            int size,
            List<ReplayDbCompareMetadataStatus> metadataStatuses,
            List<String> tableNames,
            List<String> fieldNames,
            List<LocalDate> registeredDates) {
        this(tableKeyword, fieldKeyword, domains, reviserEmpNos, groupOwnerEmpNos,
                registeredDateFrom, registeredDateTo, page, size, metadataStatuses,
                tableNames, fieldNames, registeredDates, List.of());
    }

    public ReplayDbCompareQuery(
            String tableKeyword,
            String fieldKeyword,
            List<String> domains,
            List<String> reviserEmpNos,
            List<String> groupOwnerEmpNos,
            LocalDate registeredDateFrom,
            LocalDate registeredDateTo,
            int page,
            int size,
            List<ReplayDbCompareMetadataStatus> metadataStatuses) {
        this(tableKeyword, fieldKeyword, domains, reviserEmpNos, groupOwnerEmpNos,
                registeredDateFrom, registeredDateTo, page, size, metadataStatuses,
                List.of(), List.of(), List.of(), List.of());
    }

    public ReplayDbCompareQuery(
            String tableKeyword,
            String fieldKeyword,
            List<String> domains,
            List<String> reviserEmpNos,
            List<String> groupOwnerEmpNos,
            LocalDate registeredDateFrom,
            LocalDate registeredDateTo,
            int page,
            int size) {
        this(tableKeyword, fieldKeyword, domains, reviserEmpNos, groupOwnerEmpNos,
                registeredDateFrom, registeredDateTo, page, size, List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareQuery empty(int page, int size) {
        return new ReplayDbCompareQuery(null, null, List.of(), List.of(), List.of(),
                null, null, page, size, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
