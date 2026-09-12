package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareQuery(
        String tableKeyword,
        String fieldKeyword,
        List<String> domains,
        List<String> ownerEmpNos,
        List<String> groups,
        LocalDate registeredDateFrom,
        LocalDate registeredDateTo,
        boolean deleted,
        int page,
        int size) {

    public ReplayDbCompareQuery {
        domains = copy(domains);
        ownerEmpNos = copy(ownerEmpNos);
        groups = copy(groups);
    }

    public static ReplayDbCompareQuery empty(int page, int size) {
        return new ReplayDbCompareQuery(null, null, List.of(), List.of(), List.of(),
                null, null, false, page, size);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
