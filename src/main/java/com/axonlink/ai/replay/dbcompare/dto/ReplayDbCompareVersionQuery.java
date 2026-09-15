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
        LocalDate registeredDateTo) {

    public ReplayDbCompareVersionQuery {
        domains = copy(domains);
        reviserEmpNos = copy(reviserEmpNos);
        groupOwnerEmpNos = copy(groupOwnerEmpNos);
    }

    public static ReplayDbCompareVersionQuery empty(int page, int size) {
        return new ReplayDbCompareVersionQuery(
                page, size, null, null, List.of(), List.of(), List.of(), null, null);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
