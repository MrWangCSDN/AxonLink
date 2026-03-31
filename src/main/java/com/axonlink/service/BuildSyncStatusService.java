package com.axonlink.service;

import java.util.Map;

/**
 * 构建同步状态服务。
 *
 * <p>通过调用 sunline-benchmark 提供的免鉴权接口，
 * 汇总最近一次全量拉取+编译任务的版本号与当前状态。
 */
public interface BuildSyncStatusService {

    /**
     * 查询 axon-link-server 对应的最近一次构建同步状态。
     */
    Map<String, Object> loadBuildSyncStatus();
}
