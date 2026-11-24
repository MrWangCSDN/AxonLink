package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareHeaderFilterRequest(
        String targetColumn,
        String keyword,
        Integer limit,
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

    public ReplayDbCompareHeaderFilterRequest(
        String targetColumn,
        String keyword,
        Integer limit,
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
        this(targetColumn, keyword, limit, tableKeyword, fieldKeyword, domains, reviserEmpNos, groupOwnerEmpNos, registeredDateFrom, registeredDateTo, tableNames, fieldNames, registeredDates, whereConditionValues, List.of());
    }


    public ReplayDbCompareHeaderFilterRequest {
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

    public ReplayDbCompareHeaderFilterRequest(
            String targetColumn,
            String keyword,
            Integer limit,
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
        this(targetColumn, keyword, limit, tableKeyword, fieldKeyword, domains,
                reviserEmpNos, groupOwnerEmpNos, registeredDateFrom, registeredDateTo,
                tableNames, fieldNames, registeredDates, List.of());
    }

    public ReplayDbCompareHeaderFilterRequest(
            String targetColumn,
            String keyword,
            Integer limit,
            String tableKeyword,
            String fieldKeyword,
            List<String> domains,
            List<String> reviserEmpNos,
            List<String> groupOwnerEmpNos,
            LocalDate registeredDateFrom,
            LocalDate registeredDateTo) {
        this(targetColumn, keyword, limit, tableKeyword, fieldKeyword, domains,
                reviserEmpNos, groupOwnerEmpNos, registeredDateFrom, registeredDateTo,
                List.of(), List.of(), List.of(), List.of());
    }

    public ReplayDbCompareQuery query() {
        return new ReplayDbCompareQuery(tableKeyword, fieldKeyword, domains, reviserEmpNos,
                groupOwnerEmpNos, registeredDateFrom, registeredDateTo, 0, 1, List.of(),
                tableNames, fieldNames, registeredDates, whereConditionValues, maxPartitionNums);
    }

    public int effectiveLimit() {
        return Math.min(Math.max(limit == null ? 100 : limit, 1), 200);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
