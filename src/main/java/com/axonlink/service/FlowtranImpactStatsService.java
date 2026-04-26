package com.axonlink.service;

import java.util.Map;

/**
 * 影响分析侧边栏统计服务。
 */
public interface FlowtranImpactStatsService {

    Map<String, Object> getImpactStats();
}
