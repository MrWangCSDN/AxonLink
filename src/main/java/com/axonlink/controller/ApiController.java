package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.dto.FlowtranDomain;
import com.axonlink.service.BuildSyncStatusService;
import com.axonlink.service.FlowtranService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统公共 API
 *
 * <p>注意：/api/domains 和 /api/domains/{id}/transactions 已废弃，
 * 请使用 /api/flowtran/domains 和 /api/flowtran/domains/{key}/transactions。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final FlowtranService flowtranService;
    private final BuildSyncStatusService buildSyncStatusService;

    public ApiController(FlowtranService flowtranService,
                         BuildSyncStatusService buildSyncStatusService) {
        this.flowtranService = flowtranService;
        this.buildSyncStatusService = buildSyncStatusService;
    }

    /**
     * 系统统计（健康检查探针）
     * GET /api/system/stats
     */
    @GetMapping("/system/stats")
    public R<Map<String, Object>> stats() {
        List<FlowtranDomain> domains = flowtranService.listDomains();
        long totalTransactions = domains.stream().mapToLong(FlowtranDomain::getTxCount).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDomains", domains.size());
        result.put("totalTransactions", totalTransactions);
        result.put("status", "normal");
        result.put("statusText", "系统运行正常");
        return R.ok(result);
    }

    /**
     * 最近一次全量拉取+编译状态
     * GET /api/system/build-sync-status
     */
    @GetMapping("/system/build-sync-status")
    public R<Map<String, Object>> buildSyncStatus() {
        return R.ok(buildSyncStatusService.loadBuildSyncStatus());
    }
}
