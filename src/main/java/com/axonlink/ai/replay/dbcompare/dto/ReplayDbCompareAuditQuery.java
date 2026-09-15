package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ReplayDbCompareAuditQuery(
        Long registrationId,
        String tableKeyword,
        String operatorKeyword,
        List<String> operatorEmpNos,
        List<ReplayDbCompareAuditOperation> operations,
        LocalDateTime operatedFrom,
        LocalDateTime operatedTo,
        int page,
        int size) {

    public ReplayDbCompareAuditQuery {
        operatorEmpNos = operatorEmpNos == null ? List.of() : List.copyOf(operatorEmpNos);
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    public ReplayDbCompareAuditQuery(
            String tableKeyword,
            List<String> operatorEmpNos,
            List<ReplayDbCompareAuditOperation> operations,
            LocalDateTime operatedAtFrom,
            LocalDateTime operatedAtTo,
            int page,
            int size) {
        this(null, tableKeyword, null, operatorEmpNos, operations,
                operatedAtFrom, operatedAtTo, page, size);
    }

    public static ReplayDbCompareAuditQuery empty(int page, int size) {
        return new ReplayDbCompareAuditQuery(null, null, null, List.of(), List.of(), null, null, page, size);
    }
}
