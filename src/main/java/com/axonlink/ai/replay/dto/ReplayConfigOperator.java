package com.axonlink.ai.replay.dto;

/** 回放配置操作人身份：登录账号、姓名与工号。 */
public record ReplayConfigOperator(String username, String realName, String empNo) {

    public ReplayConfigOperator(String username, String realName) {
        this(username, realName, null);
    }
}
