package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.service.ChainService;
import org.springframework.web.bind.annotation.*;

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

    private final ChainService chainService;

    public ApiController(ChainService chainService) {
        this.chainService = chainService;
    }

    /**
     * 系统统计（健康检查探针）
     * GET /api/system/stats
     */
    @GetMapping("/system/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(chainService.getSystemStats());
    }
}
