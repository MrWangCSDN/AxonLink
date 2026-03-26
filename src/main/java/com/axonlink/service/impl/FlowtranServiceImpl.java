package com.axonlink.service.impl;

import com.axonlink.common.DomainKeyResolver;
import com.axonlink.dto.FlowtranDomain;
import com.axonlink.dto.FlowtranTransaction;
import com.axonlink.service.FlowtranService;
import com.axonlink.service.ServiceNodeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * flowtran 数据服务实现。
 *
 * <p>领域归属统一从 {@code package_path} 推断：
 * {@code com.spdb.ccbs.{领域}.xxx.xxx} → 取第4段作为领域标识，
 * 不依赖 {@code domain_key} 列（该列在原始 benchmarkdb 中不存在）。
 */
@Service
public class FlowtranServiceImpl implements FlowtranService {

    private static final Logger log = LoggerFactory.getLogger(FlowtranServiceImpl.class);

    private final JdbcTemplate    jdbcTemplate;
    private final ServiceNodeCache serviceNodeCache;

    public FlowtranServiceImpl(JdbcTemplate jdbcTemplate, ServiceNodeCache serviceNodeCache) {
        this.jdbcTemplate     = jdbcTemplate;
        this.serviceNodeCache = serviceNodeCache;
    }

    // ── 静态映射 ───────────────────────────────────────────────────────────────
    private static final Map<String, String> DOMAIN_NAME_MAP = Map.of(
        "deposit",    "存款领域",
        "loan",       "贷款领域",
        "settlement", "结算领域",
        "public",     "公共领域",
        "unvr",       "通联领域",
        "aggr",       "综合领域",
        "inbu",       "境内业务领域",
        "medu",       "中间业务领域",
        "stmt",       "对账领域"
    );
    private static final Map<String, String> DOMAIN_ICON_MAP = Map.of(
        "deposit",    "bank",
        "loan",       "credit-card",
        "settlement", "exchange",
        "public",     "globe"
    );

    /**
     * AxonLink domain_key → package_path 第4段关键字
     * （用于 WHERE package_path LIKE 过滤）
     */
    private static final Map<String, String> AXON_TO_RAW = Map.of(
        "deposit",    "dept",
        "loan",       "loan",
        "settlement", "sett",
        "public",     "comm",
        "unvr",       "unvr",
        "aggr",       "aggr",
        "inbu",       "inbu",
        "medu",       "medu",
        "stmt",       "stmt"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // 领域列表：从 package_path 第4段推断，不读 domain_key 列
    // MySQL: SUBSTRING_INDEX(SUBSTRING_INDEX(package_path, '.', 4), '.', -1)
    //   com.spdb.ccbs.dept.pbf.trans.qryMnt  →  dept
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public List<FlowtranDomain> listDomains() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT SUBSTRING_INDEX(SUBSTRING_INDEX(package_path, '.', 4), '.', -1) AS raw_dk, " +
                "       COUNT(*) AS tx_count " +
                "FROM flowtran " +
                "WHERE package_path IS NOT NULL AND package_path != '' " +
                "GROUP BY raw_dk " +
                "ORDER BY raw_dk"
            );

            // 按 AxonLink domain_key 聚合（多个 raw_dk 可能映射同一个 axonDk，如 comm→public）
            Map<String, Long> merged = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String rawDk = (String) row.get("raw_dk");
                if (rawDk == null || rawDk.isBlank()) continue;
                String axonDk = DomainKeyResolver.resolve("com.spdb.ccbs." + rawDk + ".x");
                long   count  = ((Number) row.get("tx_count")).longValue();
                merged.merge(axonDk, count, Long::sum);
            }

            List<FlowtranDomain> result = new ArrayList<>();
            for (Map.Entry<String, Long> e : merged.entrySet()) {
                String axonDk = e.getKey();
                FlowtranDomain d = new FlowtranDomain();
                d.setDomainKey(axonDk);
                d.setDomainName(DOMAIN_NAME_MAP.getOrDefault(axonDk, axonDk));
                d.setTxCount(e.getValue());
                d.setIcon(DOMAIN_ICON_MAP.getOrDefault(axonDk, "folder"));
                result.add(d);
            }
            return result;
        } catch (Exception e) {
            log.warn("[FlowtranService] listDomains 失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 交易分页：通过 package_path LIKE '%.<raw_dk>.%' 过滤领域
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public Map<String, Object> listTransactions(String domainKey, int page, int size, String keyword) {
        try {
            String rawDk = AXON_TO_RAW.getOrDefault(domainKey, domainKey);
            // 匹配 package_path 第4段，如 com.spdb.ccbs.dept.xxx
            String domainPattern = "%.%" + rawDk + ".%";

            String baseWhere = "WHERE package_path LIKE ?";
            List<Object> params = new ArrayList<>();
            params.add(domainPattern);

            if (keyword != null && !keyword.isBlank()) {
                baseWhere += " AND (id LIKE ? OR longname LIKE ?)";
                String kw = "%" + keyword.trim() + "%";
                params.add(kw);
                params.add(kw);
            }

            Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flowtran " + baseWhere, Long.class, params.toArray()
            );

            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(size);
            pageParams.add((page - 1) * size);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, longname, package_path, txn_mode, from_jar FROM flowtran "
                + baseWhere + " ORDER BY id LIMIT ? OFFSET ?",
                pageParams.toArray()
            );

            List<FlowtranTransaction> list = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                FlowtranTransaction tx = new FlowtranTransaction();
                tx.setId((String) row.get("id"));
                tx.setLongname((String) row.get("longname"));
                tx.setDomainKey(domainKey);
                tx.setTxnMode((String) row.get("txn_mode"));
                tx.setFromJar((String) row.get("from_jar"));
                list.add(tx);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list",  list);
            result.put("total", total != null ? total : 0L);
            result.put("page",  page);
            result.put("size",  size);
            return result;
        } catch (Exception e) {
            log.warn("[FlowtranService] listTransactions 失败: {}", e.getMessage());
            return Map.of("list", List.of(), "total", 0L, "page", page, "size", size);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 交易链路：从 package_path 推断领域，不读 domain_key 列
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public Map<String, Object> getChain(String txId) {
        try {
            // 1. 查 flowtran，用 package_path 推断领域
            List<Map<String, Object>> ftRows = jdbcTemplate.queryForList(
                "SELECT id, longname, package_path, txn_mode, from_jar FROM flowtran WHERE id = ?", txId
            );
            if (ftRows.isEmpty()) return null;
            Map<String, Object> ft = ftRows.get(0);

            String packagePath  = (String) ft.get("package_path");
            String txDomainKey  = DomainKeyResolver.resolve(packagePath);
            String txDomainName = DOMAIN_NAME_MAP.getOrDefault(txDomainKey, txDomainKey);

            // 2. 查 flow_step 按 step 升序
            List<Map<String, Object>> steps = jdbcTemplate.queryForList(
                "SELECT step, node_type, node_name, node_longname, incorrect_calls " +
                "FROM flow_step WHERE flow_id = ? ORDER BY step ASC", txId
            );

            boolean cacheReady = serviceNodeCache.isLoaded();

            // 3. 分层组装
            List<Map<String, Object>> orchestration  = new ArrayList<>();
            List<Map<String, Object>> serviceLayer   = new ArrayList<>();
            List<Map<String, Object>> componentLayer = new ArrayList<>();

            orchestration.add(Map.of("code", txId, "name", ft.getOrDefault("longname", txId)));

            for (Map<String, Object> s : steps) {
                String nodeType = (String) s.get("node_type");
                String nodeName = (String) s.get("node_name");
                String nodeLong = (String) s.get("node_longname");

                if ("method".equals(nodeType)) {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("prefix", "method");
                    n.put("code",   nodeName != null ? nodeName : "");
                    n.put("name",   nodeLong != null ? nodeLong : (nodeName != null ? nodeName : ""));
                    serviceLayer.add(n);

                } else if ("service".equals(nodeType) && nodeName != null) {
                    Optional<com.axonlink.dto.NodeCacheEntry> entryOpt = serviceNodeCache.get(nodeName);
                    String  nodeKind      = null;
                    String  nodeDomainKey = txDomainKey;
                    boolean crossDomain   = false;

                    if (entryOpt.isPresent()) {
                        com.axonlink.dto.NodeCacheEntry entry = entryOpt.get();
                        nodeKind      = entry.getNodeKind();
                        nodeDomainKey = entry.getDomainKey();
                        crossDomain   = !txDomainKey.equals(nodeDomainKey);
                    }

                    String prefix = nodeKind != null ? nodeKind : "pbs";

                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("prefix", prefix);
                    n.put("code",   nodeName);
                    n.put("name",   nodeLong != null ? nodeLong : nodeName);
                    if (crossDomain) n.put("domain", DOMAIN_NAME_MAP.getOrDefault(nodeDomainKey, nodeDomainKey));

                    if (prefix.startsWith("pbc")) {
                        componentLayer.add(n);
                    } else {
                        serviceLayer.add(n);
                    }
                }
            }

            // 4. 组装 chain
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("orchestration", orchestration);
            chain.put("service",       serviceLayer);
            chain.put("component",     componentLayer);
            chain.put("data",          List.of());
            chain.put("relations",     Map.of());

            // 5. 顶层（与 TransactionCard.vue 格式兼容）
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id",             txId);
            result.put("name",           ft.getOrDefault("longname", txId));
            result.put("domain",         txDomainName);
            result.put("serviceCount",   (long) serviceLayer.size());
            result.put("componentCount", (long) componentLayer.size());
            result.put("tableCount",     0L);
            result.put("layers",         4);
            result.put("chain",          chain);
            if (!cacheReady) result.put("cacheStatus", "loading");
            return result;

        } catch (Exception e) {
            log.warn("[FlowtranService] getChain 失败 txId={}: {}", txId, e.getMessage());
            return null;
        }
    }
}
