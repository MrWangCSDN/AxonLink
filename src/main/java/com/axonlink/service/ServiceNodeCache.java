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
 * 服务/构件节点内存缓存。
 *
 * <p>启动时（{@link PostConstruct}）异步全量加载 service + service_detail + component + component_detail
 * 四张表，供交易链路展示时 O(1) 查找，<b>不允许在请求链路中发起 DB 查询</b>（FR-010）。
 *
 * <p>缓存 Key 规则（FR-011）：
 * <ul>
 *   <li>服务节点：{@code {service_type_id}.{service_id}}</li>
 *   <li>构件节点：{@code {component_id}.{service_id}}</li>
 * </ul>
 * 与 {@code flow_step.node_name} 一一对应，直接用于 Map 查找。
 */
@Component
public class ServiceNodeCache {

    private static final Logger log = LoggerFactory.getLogger(ServiceNodeCache.class);

    private final JdbcTemplate   jdbcTemplate;
    private final FlowtranConfig flowtranConfig;

    public ServiceNodeCache(JdbcTemplate jdbcTemplate, FlowtranConfig flowtranConfig) {
        this.jdbcTemplate    = jdbcTemplate;
        this.flowtranConfig  = flowtranConfig;
    }

    private final ConcurrentHashMap<String, NodeCacheEntry> nodeMap = new ConcurrentHashMap<>(8000);

    private volatile boolean       loaded          = false;
    private volatile LocalDateTime loadedAt        = null;
    private volatile int           serviceCount    = 0;
    private volatile int           componentCount  = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // 启动时异步加载（不阻塞 Spring 启动）
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

    /** 热重载：清空旧数据，重新加载 */
    public Map<String, Object> reload() {
        nodeMap.clear();
        loaded = false;
        serviceCount = 0;
        componentCount = 0;
        return loadAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 核心加载逻辑
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
            log.info("[ServiceNodeCache] 加载完成：service={} component={} total={} 耗时={}ms",
                     svc, comp, svc + comp, elapsed);
            return getStats();
        } catch (Exception e) {
            log.warn("[ServiceNodeCache] 加载失败: {}", e.getMessage());
            loaded = false;
            return Map.of("loaded", false, "error", e.getMessage());
        }
    }

    // ── 服务节点：service_detail JOIN service ─────────────────────────────────
    private int loadServiceNodes() {
        String sql = """
            SELECT sd.service_type_id, sd.service_id,
                   sd.service_name, sd.service_longname,
                   sd.interface_input_field_type,  sd.interface_input_field_multi,
                   sd.interface_output_field_type, sd.interface_output_field_multi,
                   s.service_type   AS node_kind,
                   s.package_path
            FROM service_detail sd
            JOIN service s ON s.id = sd.service_type_id
            """;
        return loadNodes(sql, "service_type_id", "service_id");
    }

    // ── 构件节点：component_detail JOIN component ─────────────────────────────
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
     * 通用加载逻辑：
     * 1. 执行 JOIN 查询
     * 2. 按 {typeIdCol}.{serviceIdCol} 分组聚合多行 field 为单条 NodeCacheEntry
     * 3. 写入 nodeMap
     *
     * @return 写入的 key 数量
     */
    private int loadNodes(String sql, String typeIdCol, String serviceIdCol) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.debug("[ServiceNodeCache] 表不存在或查询失败，跳过: {}", e.getMessage());
            return 0;
        }

        // 按 key 分组：key = typeId.serviceId
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String typeId  = str(row, typeIdCol);
            String svcId   = str(row, serviceIdCol);
            if (typeId == null || svcId == null) continue;
            String key = typeId + "." + svcId;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // 聚合
        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            List<Map<String, Object>> group = e.getValue();
            Map<String, Object> first = group.get(0);

            String packagePath = str(first, "package_path");
            NodeCacheEntry entry = NodeCacheEntry.builder()
                .serviceName(str(first, "service_name"))
                .serviceLongname(str(first, "service_longname"))
                .interfaceInputFieldTypes( joinNonNull(group, "interface_input_field_type"))
                .interfaceInputFieldMultis(joinNonNull(group, "interface_input_field_multi"))
                .interfaceOutputFieldTypes( joinNonNull(group, "interface_output_field_type"))
                .interfaceOutputFieldMultis(joinNonNull(group, "interface_output_field_multi"))
                .nodeKind(str(first, "node_kind"))
                .packagePath(packagePath)
                .domainKey(DomainKeyResolver.resolve(packagePath))
                .build();
            nodeMap.put(e.getKey(), entry);
        }
        return grouped.size();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 查询接口
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 按 node_name 查找缓存条目（O(1)）。
     *
     * @param nodeKey flow_step.node_name，格式 {@code TypeId.serviceId}
     * @return 缓存条目；未命中时返回 empty
     */
    public Optional<NodeCacheEntry> get(String nodeKey) {
        return Optional.ofNullable(nodeMap.get(nodeKey));
    }

    /** 缓存是否已完成首次加载 */
    public boolean isLoaded() { return loaded; }

    /** 返回缓存统计信息 */
    public Map<String, Object> getStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded",         loaded);
        m.put("serviceCount",   serviceCount);
        m.put("componentCount", componentCount);
        m.put("totalCount",     serviceCount + componentCount);
        m.put("loadedAt",       loadedAt != null ? loadedAt.toString() : null);
        m.put("datasource",     flowtranConfig.getDatasource());
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    /** 将一组 row 的某列非空值以逗号连接 */
    private static String joinNonNull(List<Map<String, Object>> rows, String col) {
        return rows.stream()
            .map(r -> str(r, col))
            .filter(v -> v != null && !v.isBlank())
            .collect(Collectors.joining(","));
    }
}
