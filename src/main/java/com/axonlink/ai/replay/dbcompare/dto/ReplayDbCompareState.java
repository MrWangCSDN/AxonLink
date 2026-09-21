package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareState(
        String tableComment,
        String domainName,
        String groupOwnerEmpNo,
        String groupOwnerName,
        LocalDate registeredDate,
        boolean deleted,
        List<ReplayDbCompareField> fields,
        ReplayDbCompareConditionTree whereCondition,
        Long compareLimit,
        List<String> orderingPrimaryKeyNames) {

    public ReplayDbCompareState {
        fields = fields == null ? List.of() : List.copyOf(fields);
        orderingPrimaryKeyNames = orderingPrimaryKeyNames == null
                ? List.of() : List.copyOf(orderingPrimaryKeyNames);
    }

    public ReplayDbCompareState(
            String tableComment,
            String domainName,
            String groupOwnerEmpNo,
            String groupOwnerName,
            LocalDate registeredDate,
            boolean deleted,
            List<ReplayDbCompareField> fields,
            ReplayDbCompareConditionTree whereCondition,
            Long compareLimit) {
        this(tableComment, domainName, groupOwnerEmpNo, groupOwnerName,
                registeredDate, deleted, fields, whereCondition, compareLimit, List.of());
    }

    public ReplayDbCompareState(
            String tableComment,
            String domainName,
            String groupOwnerEmpNo,
            String groupOwnerName,
            LocalDate registeredDate,
            boolean deleted,
            List<ReplayDbCompareField> fields) {
        this(tableComment, domainName, groupOwnerEmpNo, groupOwnerName,
                registeredDate, deleted, fields, null, null, List.of());
    }
}
