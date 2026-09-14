package com.axonlink.ai.replay.dto;

/** 回放配置操作人身份：登录账号与姓名。 */
public record ReplayConfigOperator(String username, String realName) {
}
