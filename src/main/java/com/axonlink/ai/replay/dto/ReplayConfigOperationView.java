package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 操作历史投影：不直接暴露宽操作表结构。 */
public record ReplayConfigOperationView(long id, String operationType, String operatorUsername,
                                        String operatorRealName, String operationSource,
                                        LocalDateTime createdAt, List<ReplayConfigFieldChange> changes) {
}
