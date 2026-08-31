package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record ReplayIssueDomainTransferEntry(
        String fromDomain,
        String toDomain,
        String operatorUsername,
        String operatorRealName,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime transferredAt) {
}
