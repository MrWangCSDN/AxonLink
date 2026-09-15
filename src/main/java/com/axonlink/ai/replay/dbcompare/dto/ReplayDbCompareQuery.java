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
        List<ReplayDbCompareMetadataStatus> metadataStatuses) {

    public ReplayDbCompareQuery {
        domains = copy(domains);
        reviserEmpNos = copy(reviserEmpNos);
        groupOwnerEmpNos = copy(groupOwnerEmpNos);
        metadataStatuses = metadataStatuses == null ? List.of() : List.copyOf(metadataStatuses);
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
                registeredDateFrom, registeredDateTo, page, size, List.of());
    }

    public static ReplayDbCompareQuery empty(int page, int size) {
        return new ReplayDbCompareQuery(null, null, List.of(), List.of(), List.of(),
                null, null, page, size, List.of());
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
