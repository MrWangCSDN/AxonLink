package com.axonlink.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 方法调用关系网查询服务
 *
 * 核心查询：
 *   1. 给定类名（BCS/构件），反向查找所有 PBS 服务调用方
 *   2. 从 PBS 服务再向上，关联 t_transaction 得出受影响的交易
 *   3. 正向查找：某方法最终调用了哪些下游方法（调用链展开）
 */
@Service
public class CallGraphService {

    private final JdbcTemplate jdbc;

    public CallGraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. 反向影响面：classSimpleName 被哪些 PBS/APS 服务调用
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 输入简单类名（如 DpAccLimitBcsImpl）或 FQN 的一部分，
     * 递归向上，返回：
     *   - 直接调用方列表
     *   - 经过的 APS 服务层
     *   - 最终关联到的 FlowtTran 交易列表
     */
    public Map<String, Object> analyzeImpact(String classKeyword, int maxDepth) {
        // Step1：找到目标类的所有方法签名
        List<String> targetSigs = jdbc.queryForList(
            "SELECT signature FROM cg_method_node WHERE class_fqn LIKE ? OR class_name LIKE ?",
            String.class,
            "%" + classKeyword + "%", "%" + classKeyword + "%"
        );

        if (targetSigs.isEmpty()) {
            return Map.of("message", "未找到匹配类：" + classKeyword, "targetSigs", List.of());
        }

        // Step2：BFS 向上遍历调用方
        Set<String>              visited     = new LinkedHashSet<>();
        List<Map<String, Object>> callers    = new ArrayList<>();
        List<String>             queue       = new ArrayList<>(targetSigs);
        int depth = 0;

        while (!queue.isEmpty() && depth < maxDepth) {
            depth++;
            List<String> nextQueue = new ArrayList<>();
            for (String sig : queue) {
                if (visited.contains(sig)) continue;
                visited.add(sig);

                // 查直接调用方（仅 SYS_UTIL 和 IMPL 类型，核心跨层边）
                List<Map<String, Object>> rows = jdbc.queryForList(
                    """
                    SELECT e.caller_sig, e.call_type, e.line_no,
                           n.class_fqn, n.class_name, n.layer, n.module
                    FROM cg_call_edge e
                    LEFT JOIN cg_method_node n ON n.signature = e.caller_sig
                    WHERE e.callee_sig = ?
                      AND e.call_type IN ('SYS_UTIL','IMPL','STATIC','INSTANCE')
                    LIMIT 200
                    """,
                    sig
                );

                for (Map<String, Object> row : rows) {
                    String callerSig = (String) row.get("caller_sig");
                    if (!visited.contains(callerSig)) {
                        Map<String, Object> caller = new LinkedHashMap<>(row);
                        caller.put("depth", depth);
                        caller.put("callee_sig", sig);
                        callers.add(caller);
                        nextQueue.add(callerSig);
                    }
                }
            }
            queue = nextQueue;
        }

        // Step3：从调用方中提取 APS 层服务
        List<Map<String, Object>> apsCallers = callers.stream()
            .filter(c -> "APS".equals(c.get("layer")))
            .toList();

        // Step4：将 APS 服务的 class_name 与 t_service_node 关联，找到交易
        List<Map<String, Object>> impactedTransactions = findImpactedTransactions(apsCallers);

        // 汇总结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword",              classKeyword);
        result.put("targetMethodCount",    targetSigs.size());
        result.put("totalCallerCount",     callers.size());
        result.put("apsServiceCount",      apsCallers.size());
        result.put("impactedTxCount",      impactedTransactions.size());
        result.put("targetSignatures",     targetSigs);
        result.put("callers",              callers);
        result.put("apsCallers",           apsCallers);
        result.put("impactedTransactions", impactedTransactions);
        return result;
    }

    /**
     * 通过 APS 服务类名，关联业务库中的 t_service_node + t_transaction，
     * 找出受影响的交易列表。
     *
     * 匹配策略：APS 类名 contains service_code（不区分大小写），
     * 或 service_code contains 类名关键部分。
     */
    private List<Map<String, Object>> findImpactedTransactions(List<Map<String, Object>> apsCallers) {
        if (apsCallers.isEmpty()) return List.of();

        Set<String> apsClassNames = new LinkedHashSet<>();
        for (Map<String, Object> c : apsCallers) {
            String name = (String) c.get("class_name");
            if (name != null) apsClassNames.add(name.toLowerCase());
        }

        // 查 t_service_node，做模糊匹配
        List<Map<String, Object>> serviceNodes = jdbc.queryForList(
            "SELECT id, tx_id, service_code, name FROM t_service_node WHERE deleted = 0"
        );

        Set<Long> matchedTxIds = new LinkedHashSet<>();
        for (Map<String, Object> sn : serviceNodes) {
            String code = ((String) sn.get("service_code")).toLowerCase();
            for (String apsName : apsClassNames) {
                // 取 APS 类名前段关键词做匹配（如 IoDpAccLimitSetApsImpl → iodpacclimitset）
                String keyword = apsName.replace("apsimpl", "").replace("svtp", "");
                if (code.contains(keyword) || keyword.contains(code)) {
                    matchedTxIds.add(((Number) sn.get("tx_id")).longValue());
                    break;
                }
            }
        }

        if (matchedTxIds.isEmpty()) return List.of();

        String inClause = String.join(",", matchedTxIds.stream().map(String::valueOf).toList());
        return jdbc.queryForList(
            "SELECT t.id, t.tx_code, t.name, d.name AS domain_name " +
            "FROM t_transaction t JOIN t_domain d ON d.id = t.domain_id " +
            "WHERE t.id IN (" + inClause + ") AND t.deleted = 0 ORDER BY t.tx_code"
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. 正向追踪：某方法最终调用了哪些下游（调用链展开）
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> traceDownstream(String sigKeyword, int maxDepth) {
        List<String> startSigs = jdbc.queryForList(
            "SELECT signature FROM cg_method_node WHERE signature LIKE ? LIMIT 20",
            String.class, "%" + sigKeyword + "%"
        );
        if (startSigs.isEmpty()) return Map.of("message", "未找到：" + sigKeyword);

        Set<String>              visited = new LinkedHashSet<>();
        List<Map<String, Object>> chain  = new ArrayList<>();
        List<String>             queue   = new ArrayList<>(startSigs);
        int depth = 0;

        while (!queue.isEmpty() && depth < maxDepth) {
            depth++;
            List<String> next = new ArrayList<>();
            for (String sig : queue) {
                if (visited.contains(sig)) continue;
                visited.add(sig);

                List<Map<String, Object>> rows = jdbc.queryForList(
                    """
                    SELECT e.callee_sig, e.call_type, e.line_no,
                           n.class_name, n.layer, n.module
                    FROM cg_call_edge e
                    LEFT JOIN cg_method_node n ON n.signature = e.callee_sig
                    WHERE e.caller_sig = ?
                      AND e.call_type IN ('SYS_UTIL','IMPL','STATIC')
                    LIMIT 100
                    """,
                    sig
                );
                for (Map<String, Object> row : rows) {
                    String callee = (String) row.get("callee_sig");
                    if (callee != null && !visited.contains(callee) && !callee.startsWith("?#")) {
                        Map<String, Object> item = new LinkedHashMap<>(row);
                        item.put("depth", depth);
                        item.put("caller_sig", sig);
                        chain.add(item);
                        next.add(callee);
                    }
                }
            }
            queue = next;
        }

        return Map.of("startSigs", startSigs, "chain", chain, "depth", depth);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. SysUtil 调用汇总：某接口/类被哪些地方通过 SysUtil 调用
    // ─────────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> querySysUtilCallers(String classKeyword) {
        return jdbc.queryForList(
            """
            SELECT e.caller_sig, e.callee_sig, e.line_no,
                   n.class_name AS caller_class, n.layer AS caller_layer, n.module AS caller_module
            FROM cg_call_edge e
            LEFT JOIN cg_method_node n ON n.signature = e.caller_sig
            WHERE e.call_type = 'SYS_UTIL'
              AND e.callee_sig LIKE ?
            ORDER BY n.layer, e.caller_sig
            LIMIT 500
            """,
            "%" + classKeyword + "%"
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. 统计概览
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("methodNodeCount", jdbc.queryForObject("SELECT COUNT(*) FROM cg_method_node", Long.class));
        stats.put("callEdgeCount",   jdbc.queryForObject("SELECT COUNT(*) FROM cg_call_edge",   Long.class));
        stats.put("interfaceImplCount", jdbc.queryForObject("SELECT COUNT(*) FROM cg_interface_impl", Long.class));
        stats.put("sysUtilEdgeCount",   jdbc.queryForObject(
            "SELECT COUNT(*) FROM cg_call_edge WHERE call_type = 'SYS_UTIL'", Long.class));

        // 按层统计
        List<Map<String, Object>> byLayer = jdbc.queryForList(
            "SELECT layer, COUNT(*) AS cnt FROM cg_method_node GROUP BY layer ORDER BY cnt DESC"
        );
        stats.put("byLayer", byLayer);

        // 被调用最多的 BCS 构件（热点）
        List<Map<String, Object>> hotBcs = jdbc.queryForList(
            """
            SELECT n.class_name, n.module, COUNT(e.id) AS caller_count
            FROM cg_call_edge e
            JOIN cg_method_node n ON n.signature = e.callee_sig
            WHERE n.layer = 'BCS'
            GROUP BY n.class_name, n.module
            ORDER BY caller_count DESC
            LIMIT 20
            """
        );
        stats.put("hotBcsComponents", hotBcs);

        return stats;
    }
}
