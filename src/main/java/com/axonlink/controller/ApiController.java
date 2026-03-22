package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.service.ChainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 交易链路 API
 *
 * 所有接口以 /api 开头，与前端静态资源隔离
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final ChainService chainService;

    /**
     * 获取所有领域列表
     * GET /api/domains
     */
    @GetMapping("/domains")
    public R<List<Map<String, Object>>> listDomains() {
        return R.ok(chainService.listDomains());
    }

    /**
     * 获取某领域下的交易列表（滚动分页 + 关键词模糊搜索）
     * GET /api/domains/{domainId}/transactions?page=1&size=5&keyword=贷款
     */
    @GetMapping("/domains/{domainId}/transactions")
    public R<Map<String, Object>> listTransactions(
            @PathVariable String domainId,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "5")  int size,
            @RequestParam(defaultValue = "")   String keyword) {
        return R.ok(chainService.listTransactions(domainId, page, size, keyword));
    }

    /**
     * 获取单笔交易的完整链路
     * GET /api/transactions/{txCode}/chain
     */
    @GetMapping("/transactions/{txCode}/chain")
    public R<Map<String, Object>> getChain(@PathVariable String txCode) {
        Map<String, Object> chain = chainService.getChain(txCode);
        if (chain == null) return R.fail("交易不存在：" + txCode);
        return R.ok(chain);
    }

    /**
     * 系统统计（动态查询，同时作为健康检查探针）
     * GET /api/system/stats
     */
    @GetMapping("/system/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(chainService.getSystemStats());
    }
}
