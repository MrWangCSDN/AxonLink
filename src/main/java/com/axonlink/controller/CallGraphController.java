package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.service.CallGraphScanner;
import com.axonlink.service.CallGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 方法调用关系网 REST 接口
 *
 * POST /api/callgraph/scan          全量扫描（同步，返回统计）
 * POST /api/callgraph/scan/async    全量扫描（异步，立即返回）
 * GET  /api/callgraph/scan/progress 查询扫描进度
 * POST /api/callgraph/refresh       增量刷新（传入变更文件列表）
 *
 * GET  /api/callgraph/impact?class=DpAccLimitBcsImpl&depth=8
 *      反向影响面：被哪些 PBS 调用，影响哪些交易
 *
 * GET  /api/callgraph/downstream?sig=DpAccLimitBcsImpl&depth=5
 *      正向调用链展开
 *
 * GET  /api/callgraph/sysutil?class=IoDpAccLimitSvtp
 *      查询通过 SysUtil 调用该接口/类的所有调用方
 *
 * GET  /api/callgraph/stats
 *      统计概览（方法数、边数、热点构件）
 */
@RestController
@RequestMapping("/api/callgraph")
@RequiredArgsConstructor
public class CallGraphController {

    private final CallGraphScanner  scanner;
    private final CallGraphService  service;

    /** 全量扫描（同步）—— 扫描 4000+ 文件约 20-35 秒 */
    @PostMapping("/scan")
    public R<Map<String, Object>> fullScan() {
        return R.ok(scanner.fullScanSync());
    }

    /** 全量扫描（异步）—— 立即返回，通过 /progress 轮询进度 */
    @PostMapping("/scan/async")
    public R<String> fullScanAsync() {
        scanner.startFullScan();
        return R.ok("扫描已启动，通过 GET /api/callgraph/scan/progress 查询进度");
    }

    /** 查询扫描进度 */
    @GetMapping("/scan/progress")
    public R<Map<String, Object>> scanProgress() {
        return R.ok(scanner.getProgress());
    }

    /**
     * 增量刷新
     * Body: { "files": ["/path/to/DpAccLimitBcsImpl.java", ...] }
     */
    @PostMapping("/refresh")
    public R<Map<String, Object>> incrementalRefresh(@RequestBody Map<String, List<String>> body) {
        List<String> files = body.getOrDefault("files", List.of());
        if (files.isEmpty()) return R.fail("files 参数不能为空");
        return R.ok(scanner.incrementalRefresh(files));
    }

    /**
     * 反向影响面分析
     * @param cls   类名关键词，如 DpAccLimitBcsImpl 或 DpAccLimit
     * @param depth 向上追溯的最大深度，默认 8
     */
    @GetMapping("/impact")
    public R<Map<String, Object>> impact(
            @RequestParam("class") String cls,
            @RequestParam(value = "depth", defaultValue = "8") int depth) {
        return R.ok(service.analyzeImpact(cls, depth));
    }

    /**
     * 正向调用链展开
     * @param sig   方法签名关键词
     * @param depth 向下追踪最大深度，默认 5
     */
    @GetMapping("/downstream")
    public R<Map<String, Object>> downstream(
            @RequestParam("sig") String sig,
            @RequestParam(value = "depth", defaultValue = "5") int depth) {
        return R.ok(service.traceDownstream(sig, depth));
    }

    /**
     * 查询通过 SysUtil 调用某接口/类的所有调用方
     * @param cls 类名关键词
     */
    @GetMapping("/sysutil")
    public R<Object> sysutilCallers(@RequestParam("class") String cls) {
        return R.ok(service.querySysUtilCallers(cls));
    }

    /** 统计概览 */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(service.getStats());
    }
}
