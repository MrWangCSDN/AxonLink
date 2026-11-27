package com.axonlink.ai.replay.dto;

/** 服务映射表的交易码及「全量交易人员清单」中的人员信息。 */
public record ReplayConfigPersonInfo(String oldTransactionCode, String domain, String developer, String bankOwner,
                                     String bankOwnerEmpNos, boolean personMapped) {
}
