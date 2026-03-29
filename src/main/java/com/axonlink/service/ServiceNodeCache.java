package com.axonlink.service;

import com.axonlink.common.DomainKeyResolver;
import com.axonlink.config.FlowtranConfig;
import com.axonlink.dto.NodeCacheEntry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 服务/构件节点内存缓存，维护两张 Map：
 *
 * <ul>
 *   <li><b>nodeMap</b>（完整节点信息）
 *       <br>key = {@code {service_type_id}.{service_id}} / {@code {component_id}.{service_id}}
 *       <br>value = {@link NodeCacheEntry}（名称 + 接口字段 + 节点类型 + 领域）</li>
 *
 *   <li><b>longnameMap</b>（ID 存在标记）
 *       <br>key = {@code service_type_id} / {@code component_id}（主表 ID）
 *       <br>value = {@code 1}（固定值，相当于 Set，用于 O(1) 判断某 ID 是否已注册）</li>
 * </ul>
 *
 * <p>两张 Map 在启动时同步加载，不允许在请求链路中发起 DB 查询。
 */
@Component
public class ServiceNodeCache {

    private static final Logger log = LoggerFactory.getLogger(ServiceNodeCache.class);

    private final JdbcTemplate   jdbcTemplate;
    private final FlowtranConfig flowtranConfig;

    public ServiceNodeCache(JdbcTemplate jdbcTemplate, FlowtranConfig flowtranConfig) {
        this.jdbcTemplate   = jdbcTemplate;
        this.flowtranConfig = flowtranConfig;
    }

    // ── Map1：service_type_id.service_id → NodeCacheEntry（完整信息）────────────
    private final ConcurrentHashMap<String, NodeCacheEntry> nodeMap     = new ConcurrentHashMap<>(8000);

    // ── Map2：service_type_id / component_id → 1（ID 存在标记，等价于 Set）────────
    private final ConcurrentHashMap<String, Integer>        longnameMap = new ConcurrentHashMap<>(4000);

    private volatile boolean       loaded         = false;
    private volatile LocalDateTime loadedAt       = null;
    private volatile int           serviceCount   = 0;
    private volatile int           componentCount = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // 启动时异步加载
    // ─────────────────────────────────────────────────────────────────────────

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

    /** 热重载：清空两张 Map，重新加载 */
    public Map<String, Object> reload() {
        nodeMap.clear();
        longnameMap.clear();
        loaded = false;
        serviceCount = 0;
        componentCount = 0;
        return loadAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 加载逻辑
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> loadAll() {
        long t0 = System.currentTimeMillis();
        log.info("[ServiceNodeCache] 开始全量加载 service/component 四表...");
        try {
            int svc  = loadServiceNodes();
            int comp = loadComponentNodes();
            serviceCount   = svc;
            componentCount = comp;
            loadedAt = LocalDateTime.now();
            loaded   = true;
            long elapsed = System.currentTimeMillis() - t0;
            log.info("[ServiceNodeCache] 加载完成：service={} component={} total={} longnameMap={} 耗时={}ms",
                     svc, comp, svc + comp, longnameMap.size(), elapsed);
            return getStats();
        } catch (Exception e) {
            log.warn("[ServiceNodeCache] 加载失败: {}", e.getMessage());
            loaded = false;
            return Map.of("loaded", false, "error", e.getMessage());
        }
    }

    private int loadServiceNodes() {
        String sql = """
            SELECT sd.service_type_id, sd.service_id,
                   sd.service_name, sd.service_longname,
                   sd.interface_input_field_type,  sd.interface_input_field_multi,
                   sd.interface_output_field_type, sd.interface_output_field_multi,
                   s.service_type AS node_kind,
                   s.package_path
            FROM service_detail sd
            JOIN service s ON s.id = sd.service_type_id
            """;
        return loadNodes(sql, "service_type_id", "service_id");
    }

    private int loadComponentNodes() {
        String sql = """
            SELECT cd.component_id, cd.service_id,
                   cd.service_name, cd.service_longname,
                   cd.interface_input_field_type,  cd.interface_input_field_multi,
                   cd.interface_output_field_type, cd.interface_output_field_multi,
                   c.component_type AS node_kind,
                   c.package_path
            FROM component_detail cd
            JOIN component c ON c.id = cd.component_id
            """;
        return loadNodes(sql, "component_id", "service_id");
    }

    /**
     * 通用加载：同时写入 nodeMap（按 service_id）和 longnameMap（按 service_name）。
     *
     * <pre>
     * nodeMap    key = {typeIdCol}.{service_id}    value = NodeCacheEntry
     * longnameMap key = {typeIdCol}.{service_name} value = service_longname
     * </pre>
     */
    private int loadNodes(String sql, String typeIdCol, String serviceIdCol) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.debug("[ServiceNodeCache] 表不存在或查询失败，跳过: {}", e.getMessage());
            return 0;
        }

        // ── Map1：按 typeId.serviceId 分组聚合 ────────────────────────────────
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String typeId = str(row, typeIdCol);
            String svcId  = str(row, serviceIdCol);
            if (typeId == null || svcId == null) continue;
            grouped.computeIfAbsent(typeId + "." + svcId, k -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            List<Map<String, Object>> group = e.getValue();
            Map<String, Object> first = group.get(0);
            String packagePath = str(first, "package_path");

            // callerKey = typeId.serviceName，用于匹配 call_relation 的 caller_id + caller_method
            String svcName = str(first, "service_name");
            String typeIdVal = str(first, typeIdCol);
            String callerKey = (typeIdVal != null && svcName != null) ? typeIdVal + "." + svcName : null;

            NodeCacheEntry entry = NodeCacheEntry.builder()
                .serviceName(svcName)
                .serviceLongname(str(first, "service_longname"))
                .interfaceInputFieldTypes(joinNonNull(group, "interface_input_field_type"))
                .interfaceInputFieldMultis(joinNonNull(group, "interface_input_field_multi"))
                .interfaceOutputFieldTypes(joinNonNull(group, "interface_output_field_type"))
                .interfaceOutputFieldMultis(joinNonNull(group, "interface_output_field_multi"))
                .nodeKind(str(first, "node_kind"))
                .packagePath(packagePath)
                .domainKey(DomainKeyResolver.resolve(packagePath))
                .callerKey(callerKey)
                .build();
            nodeMap.put(e.getKey(), entry);
        }

        // ── Map2：typeId（service_type_id / component_id）→ 1 ────────────────
        for (Map<String, Object> row : rows) {
            String typeId = str(row, typeIdCol);
            if (typeId != null) longnameMap.put(typeId, 1);
        }

        return grouped.size();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 查询接口
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 按 {@code typeId.serviceId} 查找完整节点信息（O(1)）。
     * 对应 flow_step.node_name 格式。
     *
     * @param nodeKey 如 {@code IoDpAccLimitApsSvtp.prcAccLimitRgst}
     */
    public Optional<NodeCacheEntry> get(String nodeKey) {
        return Optional.ofNullable(nodeMap.get(nodeKey));
    }

    /**
     * 判断某个 service_type_id 或 component_id 是否存在于缓存中（O(1)）。
     *
     * @param typeId service_type_id 或 component_id，如 {@code IoDpAccLimitApsSvtp}
     * @return true = 已注册；false = 不存在
     */
    public boolean containsTypeId(String typeId) {
        return typeId != null && longnameMap.containsKey(typeId);
    }

    /**
     * 按 typeId 前缀查找第一个匹配的 NodeCacheEntry。
     * nodeMap 的 key 格式为 {@code typeId.serviceId}，此方法遍历查找以 {@code typeId.} 开头的第一条。
     *
     * @param typeId service_type_id 或 component_id
     */
    public Optional<NodeCacheEntry> findByTypeId(String typeId) {
        if (typeId == null) return Optional.empty();
        String prefix = typeId + ".";
        for (Map.Entry<String, NodeCacheEntry> e : nodeMap.entrySet()) {
            if (e.getKey().startsWith(prefix)) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    /** 缓存是否已完成首次加载 */
    public boolean isLoaded() { return loaded; }

    /** 返回缓存统计信息 */
    public Map<String, Object> getStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded",          loaded);
        m.put("serviceCount",    serviceCount);
        m.put("componentCount",  componentCount);
        m.put("totalCount",      serviceCount + componentCount);
        m.put("longnameMapSize", longnameMap.size());
        m.put("loadedAt",        loadedAt != null ? loadedAt.toString() : null);
        m.put("datasource",      flowtranConfig.getDatasource());
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static String joinNonNull(List<Map<String, Object>> rows, String col) {
        return rows.stream()
            .map(r -> str(r, col))
            .filter(v -> v != null && !v.isBlank())
            .collect(Collectors.joining(","));
    }
}
