package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareVersionQuery(
        int page,
        int size,
        String tableKeyword,
        String fieldKeyword,
        List<String> domains,
        List<String> reviserEmpNos,
        List<String> groupOwnerEmpNos,
        LocalDate registeredDateFrom,
        LocalDate registeredDateTo,
        List<String> tableNames,
        List<String> fieldNames,
        List<LocalDate> registeredDates,
        List<String> whereConditionValues,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentUsing = ReplayDbCompareMaxPartitionNumDeserializer.class)
        List<Integer> maxPartitionNums) {

    public ReplayDbCompareVersionQuery(
        int page,
        int size,
        String tableKeyword,
        String fieldKeyword,
        List<String> domains,
        List<String> reviserEmpNos,
        List<String> groupOwnerEmpNos,
        LocalDate registeredDateFrom,
        LocalDate registeredDateTo,
        List<String> tableNames,
        List<String> fieldNames,
        List<LocalDate> registeredDates,
        List<String> whereConditionValues) {
        this(page, size, tableKeyword, fieldKeyword, domains, reviserEmpNos, groupOwnerEmpNos, registeredDateFrom, registeredDateTo, tableNames, fieldNames, registeredDates, whereConditionValues, List.of());
    }


    public ReplayDbCompareVersionQuery {
        maxPartitionNums = maxPartitionNums == null ? List.of() : List.copyOf(maxPartitionNums);
        if (maxPartitionNums.stream().anyMatch(value -> value < 1 || value > 256)) {
            throw new IllegalArgumentException("最大HASH分区数必须为 1 到 256 的整数");
        }
        domains = copy(domains);
        reviserEmpNos = copy(reviserEmpNos);
        groupOwnerEmpNos = copy(groupOwnerEmpNos);
        tableNames = copy(tableNames);
        fieldNames = copy(fieldNames);
        registeredDates = registeredDates == null ? List.of() : List.copyOf(registeredDates);
        whereConditionValues = copy(whereConditionValues);
    }

    public ReplayDbCompareVersionQuery(
            int page,
            int size,
            String tableKeyword,
            String fieldKeyword,
            List<String> domains,
            List<String> reviserEmpNos,
            List<String> groupOwnerEmpNos,
            LocalDate registeredDateFrom,
            LocalDate registeredDateTo,
            List<String> tableNames,
            List<String> fieldNames,
            List<LocalDate> registeredDates) {
        this(page, size, tableKeyword, fieldKeyword, domains, reviserEmpNos, groupOwnerEmpNos,
                registeredDateFrom, registeredDateTo, tableNames, fieldNames, registeredDates, List.of());
    }

    public ReplayDbCompareVersionQuery(
            int page,
            int size,
            String tableKeyword,
            String fieldKeyword,
            List<String> domains,
            List<String> reviserEmpNos,
            List<String> groupOwnerEmpNos,
            LocalDate registeredDateFrom,
            LocalDate registeredDateTo) {
        this(page, size, tableKeyword, fieldKeyword, domains, reviserEmpNos, groupOwnerEmpNos,
                registeredDateFrom, registeredDateTo, List.of(), List.of(), List.of(), List.of());
    }

    public static ReplayDbCompareVersionQuery empty(int page, int size) {
        return new ReplayDbCompareVersionQuery(
                page, size, null, null, List.of(), List.of(), List.of(), null, null,
                List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
