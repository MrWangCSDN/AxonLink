package com.axonlink.service;

import com.axonlink.config.FlowtranConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 影响分析投影缓存。
 *
 * <p>请求期只读内存索引；异步 build 完成后通过快照原子替换。
 */
@Component
public class FlowtranImpactProjectionCache {

    private static final Logger log = LoggerFactory.getLogger(FlowtranImpactProjectionCache.class);
    private static final String SNAPSHOT_FILE_NAME = "impact-projection-v1.json.gz";

    private final ObjectMapper objectMapper;
    private final FlowtranConfig flowtranConfig;

    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    private final AtomicReference<SnapshotState> snapshotRef = new AtomicReference<>(SnapshotState.empty());

    public FlowtranImpactProjectionCache(ObjectMapper objectMapper,
                                         FlowtranConfig flowtranConfig) {
        this.objectMapper = objectMapper;
        this.flowtranConfig = flowtranConfig;
    }

    @PostConstruct
    public void init() {
        if (!isProjectionEnabled()) {
            log.info("[ImpactProjectionCache] flowtran.cache.impact-projection-enabled=false，跳过影响图投影缓存");
            return;
        }
        if (!flowtranConfig.getCache().isImpactProjectionLoadOnStartup()) {
            log.info("[ImpactProjectionCache] 启动加载关闭，等待异步 build 生成投影缓存");
            return;
        }
        loadSnapshotIfExists();
    }

    public boolean isProjectionEnabled() {
        return flowtranConfig.getCache().isImpactProjectionEnabled();
    }

    public Map<String, Object> getTableImpact(String tableId) {
        return snapshotRef.get().tableImpacts.get(tableId);
    }

    public Map<String, Object> getComponentImpact(String componentId) {
        return snapshotRef.get().componentImpacts.get(componentId);
    }

    public Map<String, Object> getServiceImpact(String serviceId) {
        return snapshotRef.get().serviceImpacts.get(serviceId);
    }

    public boolean isReady() {
        return snapshotRef.get().ready;
    }

    public Map<String, Map<String, Object>> getAllTableImpacts() {
        return safeCopy(snapshotRef.get().tableImpacts);
    }

    public Map<String, Map<String, Object>> getAllComponentImpacts() {
        return safeCopy(snapshotRef.get().componentImpacts);
    }

    public Map<String, Map<String, Object>> getAllServiceImpacts() {
        return safeCopy(snapshotRef.get().serviceImpacts);
    }

    public Map<String, Object> publish(FlowtranImpactProjectionData projectionData) {
        if (!isProjectionEnabled()) {
            log.info("[ImpactProjectionCache] 投影缓存已关闭，跳过发布");
            return getStatus();
        }
        Objects.requireNonNull(projectionData, "projectionData");

        SnapshotState next = SnapshotState.ready(
            LocalDateTime.now().toString(),
            currentDatasource(),
            normalizedWorkspaceRoots(),
            projectionData.txCount(),
            safeCopy(projectionData.tableImpacts()),
            safeCopy(projectionData.componentImpacts()),
            safeCopy(projectionData.serviceImpacts())
        );
        try {
            writeSnapshot(next);
            snapshotRef.set(next);
            log.info("[ImpactProjectionCache] 发布完成：table={} component={} service={} snapshot={}",
                next.tableImpacts.size(),
                next.componentImpacts.size(),
                next.serviceImpacts.size(),
                snapshotPath());
        } catch (Exception e) {
            log.warn("[ImpactProjectionCache] 快照写入失败，保持旧缓存: {}", e.getMessage());
            throw new IllegalStateException("impact projection snapshot write failed", e);
        }
        return getStatus();
    }

    public Map<String, Object> getStatus() {
        SnapshotState snapshot = snapshotRef.get();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", isProjectionEnabled());
        status.put("ready", snapshot.ready);
        status.put("builtAt", snapshot.builtAt);
        status.put("datasource", snapshot.datasource);
        status.put("workspaceRoots", snapshot.workspaceRoots);
        status.put("txCount", snapshot.txCount);
        status.put("tableKeys", snapshot.tableImpacts.size());
        status.put("componentKeys", snapshot.componentImpacts.size());
        status.put("serviceKeys", snapshot.serviceImpacts.size());
        status.put("snapshotPath", snapshotPath().toString());
        return status;
    }

    private void loadSnapshotIfExists() {
        Path snapshotPath = snapshotPath();
        if (!Files.exists(snapshotPath)) {
            log.info("[ImpactProjectionCache] 未发现快照文件：{}", snapshotPath);
            return;
        }
        try (InputStream raw = Files.newInputStream(snapshotPath);
             InputStream gzip = new GZIPInputStream(raw)) {
            SnapshotFile snapshotFile = objectMapper.readValue(gzip, SnapshotFile.class);
            if (snapshotFile == null || snapshotFile.meta == null) {
                log.warn("[ImpactProjectionCache] 快照内容为空，忽略：{}", snapshotPath);
                return;
            }
            String currentDatasource = currentDatasource();
            if (!Objects.equals(currentDatasource, snapshotFile.meta.datasource)) {
                log.warn("[ImpactProjectionCache] 快照数据源不匹配，当前={} 快照={}，忽略 {}",
                    currentDatasource, snapshotFile.meta.datasource, snapshotPath);
                return;
            }
            String currentWorkspaceRoots = normalizedWorkspaceRoots();
            if (!currentWorkspaceRoots.isBlank()
                && snapshotFile.meta.workspaceRoots != null
                && !snapshotFile.meta.workspaceRoots.isBlank()
                && !Objects.equals(currentWorkspaceRoots, snapshotFile.meta.workspaceRoots)) {
                log.warn("[ImpactProjectionCache] 快照工作区不匹配，当前={} 快照={}，忽略 {}",
                    currentWorkspaceRoots, snapshotFile.meta.workspaceRoots, snapshotPath);
                return;
            }

            snapshotRef.set(SnapshotState.from(snapshotFile));
            log.info("[ImpactProjectionCache] 已加载快照：table={} component={} service={} snapshot={}",
                snapshotRef.get().tableImpacts.size(),
                snapshotRef.get().componentImpacts.size(),
                snapshotRef.get().serviceImpacts.size(),
                snapshotPath);
        } catch (Exception e) {
            log.warn("[ImpactProjectionCache] 加载快照失败，忽略 {}: {}", snapshotPath, e.getMessage());
        }
    }

    private void writeSnapshot(SnapshotState snapshot) throws IOException {
        Path snapshotPath = snapshotPath();
        Path parent = snapshotPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempPath = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tempPath);
             OutputStream gzip = new GZIPOutputStream(raw)) {
            objectMapper.writeValue(gzip, SnapshotFile.from(snapshot));
        }

        try {
            Files.move(tempPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path snapshotPath() {
        String configuredDir = flowtranConfig.getCache().getImpactProjectionDir();
        String dir = configuredDir == null || configuredDir.isBlank() ? "data/cache" : configuredDir.trim();
        return Path.of(dir).resolve(SNAPSHOT_FILE_NAME);
    }

    private String currentDatasource() {
        return flowtranConfig.getDatasource();
    }

    private String normalizedWorkspaceRoots() {
        return workspaceRoots == null ? "" : workspaceRoots.trim();
    }

    private Map<String, Map<String, Object>> safeCopy(Map<String, Map<String, Object>> source) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> copy.put(key, value == null ? Map.of() : new LinkedHashMap<>(value)));
        return copy;
    }

    private static final class SnapshotState {
        private final boolean ready;
        private final String builtAt;
        private final String datasource;
        private final String workspaceRoots;
        private final int txCount;
        private final Map<String, Map<String, Object>> tableImpacts;
        private final Map<String, Map<String, Object>> componentImpacts;
        private final Map<String, Map<String, Object>> serviceImpacts;

        private SnapshotState(boolean ready,
                              String builtAt,
                              String datasource,
                              String workspaceRoots,
                              int txCount,
                              Map<String, Map<String, Object>> tableImpacts,
                              Map<String, Map<String, Object>> componentImpacts,
                              Map<String, Map<String, Object>> serviceImpacts) {
            this.ready = ready;
            this.builtAt = builtAt;
            this.datasource = datasource;
            this.workspaceRoots = workspaceRoots;
            this.txCount = txCount;
            this.tableImpacts = tableImpacts;
            this.componentImpacts = componentImpacts;
            this.serviceImpacts = serviceImpacts;
        }

        private static SnapshotState empty() {
            return new SnapshotState(false, null, null, null, 0, Map.of(), Map.of(), Map.of());
        }

        private static SnapshotState ready(String builtAt,
                                           String datasource,
                                           String workspaceRoots,
                                           int txCount,
                                           Map<String, Map<String, Object>> tableImpacts,
                                           Map<String, Map<String, Object>> componentImpacts,
                                           Map<String, Map<String, Object>> serviceImpacts) {
            return new SnapshotState(
                true,
                builtAt,
                datasource,
                workspaceRoots,
                txCount,
                tableImpacts,
                componentImpacts,
                serviceImpacts
            );
        }

        private static SnapshotState from(SnapshotFile snapshotFile) {
            SnapshotMeta meta = snapshotFile.meta;
            return new SnapshotState(
                true,
                meta.builtAt,
                meta.datasource,
                meta.workspaceRoots,
                meta.txCount,
                snapshotFile.tables == null ? Map.of() : snapshotFile.tables,
                snapshotFile.components == null ? Map.of() : snapshotFile.components,
                snapshotFile.services == null ? Map.of() : snapshotFile.services
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class SnapshotFile {
        public SnapshotMeta meta;
        public Map<String, Map<String, Object>> tables = Map.of();
        public Map<String, Map<String, Object>> components = Map.of();
        public Map<String, Map<String, Object>> services = Map.of();

        private static SnapshotFile from(SnapshotState snapshot) {
            SnapshotFile file = new SnapshotFile();
            file.meta = new SnapshotMeta();
            file.meta.version = 1;
            file.meta.builtAt = snapshot.builtAt;
            file.meta.datasource = snapshot.datasource;
            file.meta.workspaceRoots = snapshot.workspaceRoots;
            file.meta.txCount = snapshot.txCount;
            file.tables = snapshot.tableImpacts;
            file.components = snapshot.componentImpacts;
            file.services = snapshot.serviceImpacts;
            return file;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class SnapshotMeta {
        public int version;
        public String builtAt;
        public String datasource;
        public String workspaceRoots;
        public int txCount;
    }
}
