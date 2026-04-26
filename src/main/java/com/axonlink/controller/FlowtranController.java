package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.config.FlowtranConfig;
import com.axonlink.dto.FlowtranDomain;
import com.axonlink.service.FlowtranImpactExportService;
import com.axonlink.service.FlowtranImpactService;
import com.axonlink.service.FlowtranImpactStatsService;
import com.axonlink.service.FlowtranService;
import com.axonlink.service.ServiceNodeCache;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * flowtran 数据驱动接口。
 *
 * <p>接口列表：
 * <ul>
 *   <li>{@code GET  /api/flowtran/domains}                       — 领域列表（替代 /api/domains）</li>
 *   <li>{@code GET  /api/flowtran/domains/{domainKey}/transactions} — 分页交易列表</li>
 *   <li>{@code GET  /api/flowtran/transactions/{txId}/chain}     — 交易完整链路</li>
 *   <li>{@code GET  /api/flowtran/impact/table/{tableId}}         — 表级全领域影响分析</li>
 *   <li>{@code GET  /api/flowtran/impact/component/{componentId}} — 构件级全领域影响分析</li>
 *   <li>{@code GET  /api/flowtran/impact/components}              — 构件方法全量目录</li>
 *   <li>{@code GET  /api/flowtran/impact/service/{serviceId}}     — 服务级全领域影响分析</li>
 *   <li>{@code GET  /api/flowtran/impact/services}                — 服务方法全量目录</li>
 *   <li>{@code GET  /api/flowtran/impact/export/{mode}/{id}}      — 单个影响分析 Excel 导出</li>
 *   <li>{@code GET  /api/flowtran/impact/export/{mode}/all}       — 当前模式全量 Excel 导出</li>
 *   <li>{@code GET  /api/flowtran/impact/cache/status}            — 影响图投影缓存状态</li>
 *   <li>{@code POST /api/flowtran/cache/refresh}                 — 元数据缓存热重载</li>
 *   <li>{@code GET  /api/flowtran/cache/stats}                   — 元数据缓存统计</li>
 *   <li>{@code GET  /api/flowtran/env}                           — 当前运行环境标识</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/flowtran")
public class FlowtranController {

    private final FlowtranService  flowtranService;
    private final FlowtranImpactService flowtranImpactService;
    private final FlowtranImpactExportService flowtranImpactExportService;
    private final FlowtranImpactStatsService flowtranImpactStatsService;
    private final ServiceNodeCache serviceNodeCache;
    private final FlowtranConfig   flowtranConfig;

    public FlowtranController(FlowtranService flowtranService,
                              FlowtranImpactService flowtranImpactService,
                              FlowtranImpactExportService flowtranImpactExportService,
                              FlowtranImpactStatsService flowtranImpactStatsService,
                              ServiceNodeCache serviceNodeCache,
                              FlowtranConfig flowtranConfig) {
        this.flowtranService  = flowtranService;
        this.flowtranImpactService = flowtranImpactService;
        this.flowtranImpactExportService = flowtranImpactExportService;
        this.flowtranImpactStatsService = flowtranImpactStatsService;
        this.serviceNodeCache = serviceNodeCache;
        this.flowtranConfig   = flowtranConfig;
    }

    /**
     * 领域列表（完全替代 {@code GET /api/domains}）。
     * 来源：Neo4j 交易图实时查询。
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
     * 交易完整链路（交易编排 + Neo4j 调用关系 + 元数据缓存富化）。
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
     * 表级全领域影响分析。
     *
     * @param tableId 表英文名，如 DpAccQuery
     */
    @GetMapping("/impact/table/{tableId}")
    public R<Map<String, Object>> getTableImpact(@PathVariable String tableId) {
        return R.ok(flowtranImpactService.getTableImpact(tableId));
    }

    /**
     * 构件级全领域影响分析。
     *
     * @param componentId 构件方法标识，如 DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp
     */
    @GetMapping("/impact/component/{componentId:.+}")
    public R<Map<String, Object>> getComponentImpact(@PathVariable String componentId) {
        return R.ok(flowtranImpactService.getComponentImpact(componentId));
    }

    /**
     * 全量构件方法目录。
     */
    @GetMapping("/impact/components")
    public R<Map<String, Object>> listComponentCatalog(@RequestParam(required = false) String keyword) {
        return R.ok(flowtranImpactService.listComponentCatalog(keyword));
    }

    /**
     * 服务级全领域影响分析。
     *
     * @param serviceId 服务方法标识，如 DpCheckAffrTwcSubmitBcs.DpPrcAffrTwcSubmitBcsSvtp
     */
    @GetMapping("/impact/service/{serviceId:.+}")
    public R<Map<String, Object>> getServiceImpact(@PathVariable String serviceId) {
        return R.ok(flowtranImpactService.getServiceImpact(serviceId));
    }

    /**
     * 全量服务方法目录。
     */
    @GetMapping("/impact/services")
    public R<Map<String, Object>> listServiceCatalog(@RequestParam(required = false) String keyword) {
        return R.ok(flowtranImpactService.listServiceCatalog(keyword));
    }

    /**
     * 影响图投影缓存状态。
     */
    @GetMapping("/impact/cache/status")
    public R<Map<String, Object>> getImpactCacheStatus() {
        return R.ok(flowtranImpactService.getImpactCacheStatus());
    }

    /**
     * 影响分析侧边栏统计。
     */
    @GetMapping("/impact/stats")
    public R<Map<String, Object>> getImpactStats() {
        return R.ok(flowtranImpactStatsService.getImpactStats());
    }

    /**
     * 导出当前选中的影响分析结果。
     */
    @GetMapping("/impact/export/{mode}/{id:.+}")
    public ResponseEntity<?> exportSingleImpact(@PathVariable String mode,
                                                @PathVariable String id) {
        try {
            return asExcel(flowtranImpactExportService.exportSingle(mode, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    /**
     * 导出当前模式下的全部影响分析结果。
     */
    @GetMapping("/impact/export/{mode}/all")
    public ResponseEntity<?> exportAllImpact(@PathVariable String mode) {
        try {
            return asExcel(flowtranImpactExportService.exportAll(mode));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * 热重载元数据缓存（不重启服务）。
     * 可由 Webhook 或运维人员手动触发。
     */
    @PostMapping("/cache/refresh")
    public R<Map<String, Object>> refreshCache() {
        return R.ok(serviceNodeCache.reload());
    }

    /**
     * 查询元数据缓存当前统计状态。
     */
    @GetMapping("/cache/stats")
    public R<Map<String, Object>> cacheStats() {
        return R.ok(serviceNodeCache.getStats());
    }

    /**
     * 查询当前运行环境标识（FR-009）。
     */
    @GetMapping("/env")
    public R<Map<String, Object>> env() {
        String ds = flowtranConfig.getDatasource();
        String name = "intranet".equals(ds) ? "内网（benchmarkdb）" : "外网本地（mall_admin）";
        return R.ok(Map.of("datasource", ds, "name", name));
    }

    private ResponseEntity<byte[]> asExcel(FlowtranImpactExportService.ExportFile exportFile) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(exportFile.getFileName(), StandardCharsets.UTF_8)
            .build());
        return new ResponseEntity<>(exportFile.getContent(), headers, HttpStatus.OK);
    }
}
