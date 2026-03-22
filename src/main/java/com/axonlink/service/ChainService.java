package com.axonlink.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.axonlink.entity.*;
import com.axonlink.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 交易链路组装服务
 * 将数据库中的扁平关系组装为前端所需的嵌套结构
 */
@Service
@RequiredArgsConstructor
public class ChainService {

    private final TransactionMapper transactionMapper;
    private final DomainMapper domainMapper;

    // 通用 Mapper
    private final ServiceNodeMapper serviceNodeMapper;
    private final ComponentNodeMapper componentNodeMapper;
    private final DataTableNodeMapper dataTableNodeMapper;
    private final RelationMapper relationMapper;

    /** 获取所有领域列表（含每个领域的交易数量） */
    public List<Map<String, Object>> listDomains() {
        List<Domain> domains = domainMapper.selectList(
                new LambdaQueryWrapper<Domain>().orderByAsc(Domain::getSortOrder)
        );
        return domains.stream().map(d -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id",    d.getDomainKey());
            map.put("name",  d.getName());
            map.put("icon",  d.getIcon());
            long count = transactionMapper.selectCount(
                    new LambdaQueryWrapper<Transaction>()
                            .eq(Transaction::getDomainId, d.getId())
            );
            map.put("count", count);
            return map;
        }).collect(Collectors.toList());
    }

    /** 获取某领域下的交易列表（DB 级分页 + 交易码/名称模糊搜索，返回 list + total） */
    public Map<String, Object> listTransactions(String domainKey, int page, int size, String keyword) {
        Domain domain = domainMapper.selectOne(
                new LambdaQueryWrapper<Domain>().eq(Domain::getDomainKey, domainKey)
        );
        if (domain == null) {
            return Map.of("list", List.of(), "total", 0L);
        }

        // 构建查询条件：领域过滤 + 关键词模糊（交易码 OR 交易名称）
        LambdaQueryWrapper<Transaction> wrapper = new LambdaQueryWrapper<Transaction>()
                .eq(Transaction::getDomainId, domain.getId())
                .orderByAsc(Transaction::getSortOrder)
                .orderByAsc(Transaction::getTxCode);
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(Transaction::getTxCode, kw)
                              .or().like(Transaction::getName, kw));
        }

        // DB 级分页：只查当页数据，不再全表加载
        Page<Transaction> pageResult = transactionMapper.selectPage(
                new Page<>(page, size),
                wrapper
        );

        List<Transaction> txList = pageResult.getRecords();
        if (txList.isEmpty()) {
            return Map.of("list", List.of(), "total", pageResult.getTotal());
        }

        // 批量查 COUNT：3 次查询替代 pageSize×3 次
        List<Long> txIds = txList.stream().map(Transaction::getId).collect(Collectors.toList());
        Map<Long, Long> serviceCounts   = toCountMap(serviceNodeMapper.selectMaps(
                new QueryWrapper<ServiceNode>().select("tx_id", "COUNT(*) AS cnt")
                        .in("tx_id", txIds).eq("deleted", 0).groupBy("tx_id")));
        Map<Long, Long> componentCounts = toCountMap(componentNodeMapper.selectMaps(
                new QueryWrapper<ComponentNode>().select("tx_id", "COUNT(*) AS cnt")
                        .in("tx_id", txIds).eq("deleted", 0).groupBy("tx_id")));
        Map<Long, Long> tableCounts     = toCountMap(dataTableNodeMapper.selectMaps(
                new QueryWrapper<DataTableNode>().select("tx_id", "COUNT(*) AS cnt")
                        .in("tx_id", txIds).eq("deleted", 0).groupBy("tx_id")));

        List<Map<String, Object>> list = txList.stream()
                .map(tx -> buildTxSummary(tx, domain, serviceCounts, componentCounts, tableCounts))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list",  list);
        result.put("total", pageResult.getTotal());
        return result;
    }

    /** 获取单笔交易的完整链路 */
    public Map<String, Object> getChain(String txCode) {
        Transaction tx = transactionMapper.selectOne(
                new LambdaQueryWrapper<Transaction>().eq(Transaction::getTxCode, txCode)
        );
        if (tx == null) return null;

        Domain domain = domainMapper.selectById(tx.getDomainId());

        Map<String, Object> result = buildTxSummary(tx, domain);
        result.put("chain", buildChain(tx));
        return result;
    }

    // ────────── 私有方法 ──────────

    /** 列表页使用：count 数据已批量预取，直接从 map 中取值 */
    private Map<String, Object> buildTxSummary(Transaction tx, Domain domain,
                                               Map<Long, Long> serviceCounts,
                                               Map<Long, Long> componentCounts,
                                               Map<Long, Long> tableCounts) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",             tx.getTxCode());
        map.put("name",           tx.getName());
        map.put("domain",         domain != null ? domain.getName() : "");
        map.put("layers",         tx.getLayers());
        map.put("serviceCount",   serviceCounts.getOrDefault(tx.getId(), 0L));
        map.put("componentCount", componentCounts.getOrDefault(tx.getId(), 0L));
        map.put("tableCount",     tableCounts.getOrDefault(tx.getId(), 0L));
        return map;
    }

    /** 详情页单条使用（仅在 getChain 时调用） */
    private Map<String, Object> buildTxSummary(Transaction tx, Domain domain) {
        List<Long> ids = List.of(tx.getId());
        Map<Long, Long> sc = toCountMap(serviceNodeMapper.selectMaps(
                new QueryWrapper<ServiceNode>().select("tx_id", "COUNT(*) AS cnt")
                        .in("tx_id", ids).eq("deleted", 0).groupBy("tx_id")));
        Map<Long, Long> cc = toCountMap(componentNodeMapper.selectMaps(
                new QueryWrapper<ComponentNode>().select("tx_id", "COUNT(*) AS cnt")
                        .in("tx_id", ids).eq("deleted", 0).groupBy("tx_id")));
        Map<Long, Long> tc = toCountMap(dataTableNodeMapper.selectMaps(
                new QueryWrapper<DataTableNode>().select("tx_id", "COUNT(*) AS cnt")
                        .in("tx_id", ids).eq("deleted", 0).groupBy("tx_id")));
        return buildTxSummary(tx, domain, sc, cc, tc);
    }

    /** 将 selectMaps 返回的 [{tx_id, cnt}] 转为 Map<txId, count> */
    private Map<Long, Long> toCountMap(List<Map<String, Object>> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> ((Number) r.get("tx_id")).longValue(),
                r -> ((Number) r.get("cnt")).longValue()
        ));
    }

    private Map<String, Object> buildChain(Transaction tx) {
        Long txId = tx.getId();

        // 交易层
        List<Map<String, Object>> orchestration = List.of(
                Map.of("code", tx.getTxCode(), "name", tx.getName())
        );

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
        }        ).collect(Collectors.toList());

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
        Map<String, List<String>> s2s  = new LinkedHashMap<>();
        Map<String, List<String>> s2c  = new LinkedHashMap<>();
        Map<String, List<String>> c2c  = new LinkedHashMap<>();
        Map<String, List<String>> c2d  = new LinkedHashMap<>();

        for (Relation r : relations) {
            switch (r.getRelationType()) {
                case "SERVICE_TO_SERVICE"      -> s2s.computeIfAbsent(r.getFromCode(), k -> new ArrayList<>()).add(r.getToCode());
                case "SERVICE_TO_COMPONENT"    -> s2c.computeIfAbsent(r.getFromCode(), k -> new ArrayList<>()).add(r.getToCode());
                case "COMPONENT_TO_COMPONENT"  -> c2c.computeIfAbsent(r.getFromCode(), k -> new ArrayList<>()).add(r.getToCode());
                case "COMPONENT_TO_DATA"       -> c2d.computeIfAbsent(r.getFromCode(), k -> new ArrayList<>()).add(r.getToCode());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (!s2s.isEmpty()) result.put("serviceToService",      s2s);
        result.put("serviceToComponent",    s2c);
        if (!c2c.isEmpty()) result.put("componentToComponent",  c2c);
        result.put("componentToData",       c2d);
        return result;
    }

    /** 系统统计：从数据库动态查询领域数和交易总数（原生 SQL 绕过逻辑删除过滤） */
    public Map<String, Object> getSystemStats() {
        long totalDomains      = domainMapper.countAll();
        long totalTransactions = transactionMapper.countAll();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalDomains",      totalDomains);
        map.put("totalTransactions", totalTransactions);
        map.put("status",            "normal");
        map.put("statusText",        "系统运行正常");
        return map;
    }
}
