package com.axonlink.service;

import java.util.Map;

/**
 * 影响分析投影缓存构建结果。
 */
public record FlowtranImpactProjectionData(
    int txCount,
    Map<String, Map<String, Object>> tableImpacts,
    Map<String, Map<String, Object>> componentImpacts,
    Map<String, Map<String, Object>> serviceImpacts
) {
}
