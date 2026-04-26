package com.axonlink.service;

import com.axonlink.config.FlowtranConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务启动完成后自动触发一次 Neo4j 异步构建。
 */
@Component
public class FlowtranStartupBuildTrigger {

    private static final Logger log = LoggerFactory.getLogger(FlowtranStartupBuildTrigger.class);

    private final Neo4jGraphBuilder neo4jGraphBuilder;
    private final FlowtranConfig flowtranConfig;
    private final AtomicBoolean triggered = new AtomicBoolean(false);

    public FlowtranStartupBuildTrigger(Neo4jGraphBuilder neo4jGraphBuilder,
                                       FlowtranConfig flowtranConfig) {
        this.neo4jGraphBuilder = neo4jGraphBuilder;
        this.flowtranConfig = flowtranConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!flowtranConfig.isStartupAutoBuild()) {
            log.info("[FlowtranStartupBuildTrigger] 启动自动构建已关闭");
            return;
        }
        if (!triggered.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> result = neo4jGraphBuilder.startBuildAsync(null, null);
        log.info("[FlowtranStartupBuildTrigger] 启动自动构建已触发: accepted={} status={} message={}",
                result.get("accepted"),
                result.get("asyncBuildStatus"),
                result.get("message"));
    }
}
