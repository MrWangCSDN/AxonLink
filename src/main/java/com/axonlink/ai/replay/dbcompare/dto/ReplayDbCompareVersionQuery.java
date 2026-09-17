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
        List<String> whereConditionValues) {

    public ReplayDbCompareVersionQuery {
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
