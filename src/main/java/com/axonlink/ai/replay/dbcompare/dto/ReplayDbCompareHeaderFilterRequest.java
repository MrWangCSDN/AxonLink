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
        LocalDate registeredDateTo) {

    public ReplayDbCompareHeaderFilterRequest {
        domains = copy(domains);
        reviserEmpNos = copy(reviserEmpNos);
        groupOwnerEmpNos = copy(groupOwnerEmpNos);
    }

    public ReplayDbCompareQuery query() {
        return new ReplayDbCompareQuery(tableKeyword, fieldKeyword, domains, reviserEmpNos,
                groupOwnerEmpNos, registeredDateFrom, registeredDateTo, 0, 1);
    }

    public int effectiveLimit() {
        return Math.min(Math.max(limit == null ? 100 : limit, 1), 200);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
