package com.axonlink.service;

import com.axonlink.config.FlowtranConfig;
import com.axonlink.config.Neo4jConfig;
import com.axonlink.dto.NodeCacheEntry;
import com.axonlink.service.FlowServiceMetadataResolver.ServiceTypeFileMeta;
import jakarta.annotation.PostConstruct;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务/构件节点元数据缓存。
 *
 * <p>不再依赖关系型数据库，数据来源统一改为：
 * <ul>
 *   <li>XML 元数据：{@link FlowServiceMetadataResolver}</li>
 *   <li>Neo4j 元数据：{@code ServiceType}/{@code ServiceOperation}</li>
 * </ul>
 */
@Component
public class ServiceNodeCache {

    private static final Logger log = LoggerFactory.getLogger(ServiceNodeCache.class);

    private final Driver driver;
    private final Neo4jConfig neo4jConfig;
    private final FlowtranConfig flowtranConfig;
    private final FlowServiceMetadataResolver flowServiceMetadataResolver;

    public ServiceNodeCache(Driver driver,
                            Neo4jConfig neo4jConfig,
                            FlowtranConfig flowtranConfig,
                            FlowServiceMetadataResolver flowServiceMetadataResolver) {
        this.driver = driver;
        this.neo4jConfig = neo4jConfig;
        this.flowtranConfig = flowtranConfig;
        this.flowServiceMetadataResolver = flowServiceMetadataResolver;
    }

    private final ConcurrentHashMap<String, NodeCacheEntry> nodeMap = new ConcurrentHashMap<>(8000);
    private final ConcurrentHashMap<String, NodeCacheEntry> typeIdMap = new ConcurrentHashMap<>(4000);
    private final ConcurrentHashMap<String, Integer> longnameMap = new ConcurrentHashMap<>(4000);

    private volatile boolean loaded = false;
    private volatile LocalDateTime loadedAt = null;
    private volatile int serviceCount = 0;
    private volatile int componentCount = 0;

    @PostConstruct
    public void init() {
        if (!flowtranConfig.getCache().isEnabled()) {
            log.info("[ServiceNodeCache] flowtran.cache.enabled=false，跳过缓存加载");
            return;
        }
        Thread t = new Thread(this::loadAll, "service-node-cache-loader");
        t.setDaemon(true);
        t.start();
    }

    public Map<String, Object> reload() {
        nodeMap.clear();
        typeIdMap.clear();
        longnameMap.clear();
        loaded = false;
        serviceCount = 0;
        componentCount = 0;
        loadedAt = null;
        return loadAll();
    }

    public Optional<NodeCacheEntry> get(String nodeKey) {
        if (nodeKey == null || nodeKey.isBlank()) {
            return Optional.empty();
        }
        NodeCacheEntry entry = nodeMap.get(nodeKey);
        if (entry != null) {
            return Optional.of(entry);
        }
        int split = nodeKey.indexOf('.');
        if (split > 0) {
            return Optional.ofNullable(typeIdMap.get(nodeKey.substring(0, split)));
        }
        return Optional.ofNullable(typeIdMap.get(nodeKey));
    }

    public boolean containsTypeId(String typeId) {
        return typeId != null && typeIdMap.containsKey(typeId);
    }

    public Optional<NodeCacheEntry> findByTypeId(String typeId) {
        return Optional.ofNullable(typeId == null ? null : typeIdMap.get(typeId));
    }

    public boolean isLoaded() {
        return loaded;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", loaded);
        m.put("serviceCount", serviceCount);
        m.put("componentCount", componentCount);
        m.put("totalCount", serviceCount + componentCount);
        m.put("longnameMapSize", longnameMap.size());
        m.put("loadedAt", loadedAt != null ? loadedAt.toString() : null);
        m.put("datasource", flowtranConfig.getDatasource());
        m.put("mode", "neo4j+xml");
        return m;
    }

    private Map<String, Object> loadAll() {
        long start = System.currentTimeMillis();
        log.info("[ServiceNodeCache] 开始加载 XML/Neo4j 元数据缓存...");
        try {
            flowServiceMetadataResolver.warmUp();
            Map<String, ServiceTypeFileMeta> metadataMap = new LinkedHashMap<>();
            for (ServiceTypeFileMeta meta : flowServiceMetadataResolver.allMetadata()) {
                metadataMap.put(meta.getTypeId(), meta);
                registerTypeEntry(meta);
            }

            loadOperationsFromNeo4j(metadataMap);

            serviceCount = countByKind(false);
            componentCount = countByKind(true);
            loadedAt = LocalDateTime.now();
            loaded = true;
            log.info("[ServiceNodeCache] 加载完成：service={} component={} total={} mode=neo4j+xml 耗时={}ms",
                serviceCount,
                componentCount,
                serviceCount + componentCount,
                System.currentTimeMillis() - start);
            return getStats();
        } catch (Exception e) {
            loaded = false;
            log.warn("[ServiceNodeCache] 加载失败: {}", e.getMessage());
            return Map.of("loaded", false, "error", e.getMessage());
        }
    }

    private void loadOperationsFromNeo4j(Map<String, ServiceTypeFileMeta> metadataMap) {
        if (!neo4jConfig.isEnabled() || driver == null) {
            log.info("[ServiceNodeCache] Neo4j 不可用，仅加载 XML 类型元数据");
            return;
        }

        try (Session session = driver.session()) {
            List<Record> rows = session.run(
                "MATCH (stype:ServiceType) " +
                "OPTIONAL MATCH (stype)-[:DECLARES_OPERATION]->(op:ServiceOperation) " +
                "RETURN stype.id AS typeId, " +
                "       stype.longname AS typeLongname, " +
                "       stype.nodeKind AS nodeKind, " +
                "       stype.packagePath AS packagePath, " +
                "       stype.domainKey AS domainKey, " +
                "       op.serviceId AS serviceId, " +
                "       op.methodName AS serviceName, " +
                "       op.longname AS serviceLongname " +
                "ORDER BY typeId, serviceId")
                .list();

            for (Record row : rows) {
                String typeId = stringValue(row, "typeId");
                if (isBlank(typeId)) {
                    continue;
                }
                ServiceTypeFileMeta fileMeta = metadataMap.get(typeId);
                registerTypeEntry(typeId, fileMeta, row);

                String serviceId = stringValue(row, "serviceId");
                if (isBlank(serviceId)) {
                    continue;
                }

                String serviceName = firstNonBlank(
                    stringValue(row, "serviceName"),
                    serviceId
                );
                String serviceLongname = firstNonBlank(
                    stringValue(row, "serviceLongname"),
                    stringValue(row, "typeLongname"),
                    serviceName,
                    typeId
                );

                String nodeKind = normalizeNodeKind(firstNonBlank(
                    stringValue(row, "nodeKind"),
                    fileMeta != null ? fileMeta.getNodeKind() : null
                ));
                String packagePath = firstNonBlank(
                    stringValue(row, "packagePath"),
                    fileMeta != null ? fileMeta.getPackagePath() : null
                );
                String domainKey = firstNonBlank(
                    stringValue(row, "domainKey"),
                    fileMeta != null ? fileMeta.getDomainKey() : null
                );

                NodeCacheEntry entry = NodeCacheEntry.builder()
                    .serviceName(serviceName)
                    .serviceLongname(serviceLongname)
                    .nodeKind(nodeKind)
                    .packagePath(packagePath)
                    .domainKey(domainKey)
                    .callerKey(typeId + "." + serviceName)
                    .build();
                nodeMap.put(typeId + "." + serviceId, entry);
                typeIdMap.put(typeId, mergeTypeEntry(typeIdMap.get(typeId), entry, typeId));
                longnameMap.put(typeId, 1);
            }
        } catch (Exception e) {
            log.warn("[ServiceNodeCache] 从 Neo4j 加载 ServiceOperation 失败，将仅使用 XML 元数据: {}", e.getMessage());
        }
    }

    private void registerTypeEntry(ServiceTypeFileMeta meta) {
        registerTypeEntry(meta.getTypeId(), meta, null);
    }

    private void registerTypeEntry(String typeId, ServiceTypeFileMeta meta, Record row) {
        NodeCacheEntry entry = NodeCacheEntry.builder()
            .serviceName(typeId)
            .serviceLongname(typeId)
            .nodeKind(normalizeNodeKind(firstNonBlank(
                row != null ? stringValue(row, "nodeKind") : null,
                meta != null ? meta.getNodeKind() : null
            )))
            .packagePath(firstNonBlank(
                row != null ? stringValue(row, "packagePath") : null,
                meta != null ? meta.getPackagePath() : null
            ))
            .domainKey(firstNonBlank(
                row != null ? stringValue(row, "domainKey") : null,
                meta != null ? meta.getDomainKey() : null
            ))
            .callerKey(typeId)
            .build();
        typeIdMap.put(typeId, mergeTypeEntry(typeIdMap.get(typeId), entry, typeId));
        longnameMap.put(typeId, 1);
    }

    private NodeCacheEntry mergeTypeEntry(NodeCacheEntry existing, NodeCacheEntry incoming, String typeId) {
        if (existing == null) {
            return incoming;
        }
        return NodeCacheEntry.builder()
            .serviceName(firstNonBlank(existing.getServiceName(), incoming.getServiceName(), typeId))
            .serviceLongname(firstNonBlank(existing.getServiceLongname(), incoming.getServiceLongname(), typeId))
            .interfaceInputFieldTypes(firstNonBlank(existing.getInterfaceInputFieldTypes(), incoming.getInterfaceInputFieldTypes()))
            .interfaceInputFieldMultis(firstNonBlank(existing.getInterfaceInputFieldMultis(), incoming.getInterfaceInputFieldMultis()))
            .interfaceOutputFieldTypes(firstNonBlank(existing.getInterfaceOutputFieldTypes(), incoming.getInterfaceOutputFieldTypes()))
            .interfaceOutputFieldMultis(firstNonBlank(existing.getInterfaceOutputFieldMultis(), incoming.getInterfaceOutputFieldMultis()))
            .nodeKind(firstNonBlank(existing.getNodeKind(), incoming.getNodeKind()))
            .packagePath(firstNonBlank(existing.getPackagePath(), incoming.getPackagePath()))
            .domainKey(firstNonBlank(existing.getDomainKey(), incoming.getDomainKey()))
            .callerKey(firstNonBlank(existing.getCallerKey(), incoming.getCallerKey(), typeId))
            .build();
    }

    private int countByKind(boolean component) {
        return (int) nodeMap.values().stream()
            .filter(entry -> {
                String kind = normalizeNodeKind(entry.getNodeKind());
                if (component) {
                    return kind != null && kind.startsWith("pbc");
                }
                return kind != null && !kind.startsWith("pbc");
            })
            .count();
    }

    private String normalizeNodeKind(String value) {
        return isBlank(value) ? null : value.trim().toLowerCase();
    }

    private String stringValue(Record record, String key) {
        if (record == null) {
            return null;
        }
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
