package com.axonlink.ai.replay.dto;

import java.util.List;

/** 回放配置统一分页响应。 */
public record ReplayConfigPage<T>(long total, List<T> items) {
}
