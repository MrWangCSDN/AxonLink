package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.config.FlowtranConfig;
import com.axonlink.dto.FlowtranDomain;
import com.axonlink.service.FlowtranService;
import com.axonlink.service.ServiceNodeCache;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * flowtran 数据驱动接口。
 *
 * <p>接口列表：
 * <ul>
 *   <li>{@code GET  /api/flowtran/domains}                       — 领域列表（替代 /api/domains）</li>
 *   <li>{@code GET  /api/flowtran/domains/{domainKey}/transactions} — 分页交易列表</li>
 *   <li>{@code GET  /api/flowtran/transactions/{txId}/chain}     — 交易完整链路</li>
 *   <li>{@code POST /api/flowtran/cache/refresh}                 — ServiceNodeCache 热重载</li>
 *   <li>{@code GET  /api/flowtran/cache/stats}                   — 缓存统计</li>
 *   <li>{@code GET  /api/flowtran/env}                           — 当前激活数据源环境</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/flowtran")
public class FlowtranController {

    private final FlowtranService  flowtranService;
    private final ServiceNodeCache serviceNodeCache;
    private final FlowtranConfig   flowtranConfig;

    public FlowtranController(FlowtranService flowtranService,
                              ServiceNodeCache serviceNodeCache,
                              FlowtranConfig flowtranConfig) {
        this.flowtranService  = flowtranService;
        this.serviceNodeCache = serviceNodeCache;
        this.flowtranConfig   = flowtranConfig;
    }

    /**
     * 领域列表（完全替代 {@code GET /api/domains}）。
     * 来源：flowtran 表 GROUP BY domain_key，实时查询。
     */
    @GetMapping("/domains")
    public R<List<FlowtranDomain>> listDomains() {
        try {
            return R.ok(flowtranService.listDomains());
        } catch (Exception e) {
            return R.fail("flowtran 数据源不可用：" + e.getMessage());
        }
    }

    /**
     * 分页交易列表（完全替代 {@code GET /api/domains/{id}/transactions}）。
     *
     * @param domainKey AxonLink 领域标识，如 deposit
     * @param page      页码，从 1 开始，默认 1
     * @param size      每页条数，默认 20
     * @param keyword   模糊搜索关键词（可选）
     */
    @GetMapping("/domains/{domainKey}/transactions")
    public R<Map<String, Object>> listTransactions(
            @PathVariable String domainKey,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String keyword) {
        return R.ok(flowtranService.listTransactions(domainKey, page, size, keyword));
    }

    /**
     * 交易完整链路（flow_step + ServiceNodeCache 富化）。
     *
     * @param txId flowtran.id，如 TC0033
     */
    @GetMapping("/transactions/{txId}/chain")
    public R<Map<String, Object>> getChain(@PathVariable String txId) {
        Map<String, Object> chain = flowtranService.getChain(txId);
        if (chain == null) return R.fail("交易不存在：" + txId);
        return R.ok(chain);
    }

    /**
     * 热重载 ServiceNodeCache（不重启服务）。
     * 可由 Webhook 或运维人员手动触发。
     */
    @PostMapping("/cache/refresh")
    public R<Map<String, Object>> refreshCache() {
        return R.ok(serviceNodeCache.reload());
    }

    /**
     * 查询 ServiceNodeCache 当前统计状态。
     */
    @GetMapping("/cache/stats")
    public R<Map<String, Object>> cacheStats() {
        return R.ok(serviceNodeCache.getStats());
    }

    /**
     * 查询当前激活的数据源环境标识（FR-009）。
     */
    @GetMapping("/env")
    public R<Map<String, Object>> env() {
        String ds = flowtranConfig.getDatasource();
        String name = "intranet".equals(ds) ? "内网（benchmarkdb）" : "外网本地（mall_admin）";
        return R.ok(Map.of("datasource", ds, "name", name));
    }
}
