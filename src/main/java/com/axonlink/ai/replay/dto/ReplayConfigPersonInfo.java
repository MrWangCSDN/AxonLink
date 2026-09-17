package com.axonlink.ai.replay.dto;

/** 由「全量交易人员清单」映射出的老核心交易码与负责人信息。 */
public record ReplayConfigPersonInfo(String oldTransactionCode, String developer, String bankOwner,
                                     String bankOwnerEmpNos) {
}
