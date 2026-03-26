package com.axonlink.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.axonlink.entity.*;
import com.axonlink.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 链路组装服务（保留 t_service_node / t_component_node / t_data_table_node / t_relation 查询能力）。
 *
 * <p><b>注意</b>：t_domain / t_transaction 已废弃，不再使用；
 * 领域和交易数据统一由 {@link FlowtranService} 从 flowtran 表获取。
 */
@Service
public class ChainService {

    private static final Logger log = LoggerFactory.getLogger(ChainService.class);

    private final JdbcTemplate        jdbcTemplate;
    private final FlowtranService     flowtranService;
    private final ServiceNodeMapper   serviceNodeMapper;
    private final ComponentNodeMapper componentNodeMapper;
    private final DataTableNodeMapper dataTableNodeMapper;
    private final RelationMapper      relationMapper;

    public ChainService(JdbcTemplate jdbcTemplate,
                        FlowtranService flowtranService,
                        ServiceNodeMapper serviceNodeMapper,
                        ComponentNodeMapper componentNodeMapper,
                        DataTableNodeMapper dataTableNodeMapper,
                        RelationMapper relationMapper) {
        this.jdbcTemplate        = jdbcTemplate;
        this.flowtranService     = flowtranService;
        this.serviceNodeMapper   = serviceNodeMapper;
        this.componentNodeMapper = componentNodeMapper;
        this.dataTableNodeMapper = dataTableNodeMapper;
        this.relationMapper      = relationMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 系统统计（统一来源：flowtran 表）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 系统统计：领域数与 listDomains() 保持一致（合并后的 AxonLink domain_key 数量），
     * 避免「5 个领域」与侧边栏「4 个领域」不一致的问题。
     */
    public Map<String, Object> getSystemStats() {
        long totalDomains      = 0L;
        long totalTransactions = 0L;
        try {
            // 领域数：直接使用 listDomains() 合并后的数量，与侧边栏完全一致
            totalDomains = flowtranService.listDomains().size();
            Long t = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flowtran", Long.class);
            totalTransactions = t != null ? t : 0L;
        } catch (Exception e) {
            log.warn("[ChainService] getSystemStats 查询失败: {}", e.getMessage());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalDomains",      totalDomains);
        map.put("totalTransactions", totalTransactions);
        map.put("status",    "normal");
        map.put("statusText","系统运行正常");
        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 以下方法保留，供链路明细展示使用（t_service_node 等非废弃表）
    // ─────────────────────────────────────────────────────────────────────────

    /** 将 selectMaps 返回的 [{tx_id, cnt}] 转为 Map<txId, count> */
    private Map<Long, Long> toCountMap(List<Map<String, Object>> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> ((Number) r.get("tx_id")).longValue(),
                r -> ((Number) r.get("cnt")).longValue()
        ));
    }

    private Map<String, Object> buildChain(Long txId) {
        // 交易层（占位）
        List<Map<String, Object>> orchestration = List.of();

        // 服务层
        List<ServiceNode> services = serviceNodeMapper.selectList(
                new LambdaQueryWrapper<ServiceNode>()
                        .eq(ServiceNode::getTxId, txId)
                        .orderByAsc(ServiceNode::getSortOrder)
        );
        List<Map<String, Object>> serviceList = services.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prefix", s.getPrefix());
            m.put("code",   s.getServiceCode());
            m.put("name",   s.getName());
            if (s.getCrossDomain() != null) m.put("domain", s.getCrossDomain());
            return m;
        }).collect(Collectors.toList());

        // 构件层
        List<ComponentNode> components = componentNodeMapper.selectList(
                new LambdaQueryWrapper<ComponentNode>()
                        .eq(ComponentNode::getTxId, txId)
                        .orderByAsc(ComponentNode::getSortOrder)
        );
        List<Map<String, Object>> componentList = components.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prefix", c.getPrefix());
            m.put("code",   c.getComponentCode());
            m.put("name",   c.getName());
            if (c.getCrossDomain() != null) m.put("domain", c.getCrossDomain());
            return m;
        }).collect(Collectors.toList());

        // 数据层
        List<DataTableNode> dataTables = dataTableNodeMapper.selectList(
                new LambdaQueryWrapper<DataTableNode>()
                        .eq(DataTableNode::getTxId, txId)
                        .orderByAsc(DataTableNode::getSortOrder)
        );
        List<Map<String, Object>> dataList = dataTables.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", d.getTableCode());
            m.put("name", d.getName());
            return m;
        }).collect(Collectors.toList());

        // 关联关系
        List<Relation> relations = relationMapper.selectList(
                new LambdaQueryWrapper<Relation>().eq(Relation::getTxId, txId)
        );
        Map<String, Object> relationsMap = buildRelations(relations);

        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("orchestration", orchestration);
        chain.put("service",       serviceList);
        chain.put("component",     componentList);
        chain.put("data",          dataList);
        chain.put("relations",     relationsMap);
        return chain;
    }

    private Map<String, Object> buildRelations(List<Relation> relations) {
        Map<String, List<String>> s2s = new LinkedHashMap<>();
        Map<String, List<String>> s2c = new LinkedHashMap<>();
        Map<String, List<String>> c2c = new LinkedHashMap<>();
        Map<String, List<String>> c2d = new LinkedHashMap<>();

        for (Relation r : relations) {
            switch (r.getRelationType()) {
                case "SERVICE_TO_SERVICE"     -> s2s.computeIfAbsent(r.getFromCode(), k -> new ArrayList<>()).add(r.getToCode());
                case "SERVICE_TO_COMPONENT"   -> s2c.computeIfAbsent(r.getFromCode(), k -> new ArrayList<>()).add(r.getToCode());
                case "COMPONENT_TO_COMPONENT" -> c2c.computeIfAbsent(r.getFromCode(), k -> new ArrayList<>()).add(r.getToCode());
                case "COMPONENT_TO_DATA"      -> c2d.computeIfAbsent(r.getFromCode(), k -> new ArrayList<>()).add(r.getToCode());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (!s2s.isEmpty()) result.put("serviceToService",     s2s);
        result.put("serviceToComponent",   s2c);
        if (!c2c.isEmpty()) result.put("componentToComponent", c2c);
        result.put("componentToData",      c2d);
        return result;
    }
}
