package com.axonlink.service;

import java.util.Map;

/**
 * flowtran 全领域影响分析服务。
 */
public interface FlowtranImpactService {

    /**
     * 从指定表出发，查询全领域影响图。
     *
     * @param tableId 表标识，如 {@code DpAccQuery}
     * @return 影响分析结果，结构直接对齐前端 ImpactResult
     */
    Map<String, Object> getTableImpact(String tableId);

    /**
     * 从指定构件方法出发，查询全领域向上影响图。
     *
     * @param componentId 构件方法标识，如 {@code DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp}
     * @return 影响分析结果，结构直接对齐前端 ImpactResult
     */
    Map<String, Object> getComponentImpact(String componentId);

    /**
     * 查询全量构件方法目录。
     *
     * @param keyword 关键词，可按构件方法编码/中文名过滤
     * @return 目录项
     */
    Map<String, Object> listComponentCatalog(String keyword);

    /**
     * 从指定服务方法出发，查询全领域向上影响图。
     *
     * @param serviceId 服务方法标识，如 {@code DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp}
     * @return 影响分析结果，结构直接对齐前端 ImpactResult
     */
    Map<String, Object> getServiceImpact(String serviceId);

    /**
     * 查询全量服务方法目录。
     *
     * @param keyword 关键词，可按服务方法编码/中文名过滤
     * @return 目录项
     */
    Map<String, Object> listServiceCatalog(String keyword);

    /**
     * 查询影响图投影缓存状态。
     */
    Map<String, Object> getImpactCacheStatus();
}
