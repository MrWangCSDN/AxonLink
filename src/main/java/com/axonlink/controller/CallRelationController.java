package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.service.CallRelationScanner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用关系扫描 REST 接口。
 *
 * <ul>
 *   <li>{@code POST /api/callrelation/scan}          — 全量扫描（同步）</li>
 *   <li>{@code POST /api/callrelation/scan/async}     — 全量扫描（异步）</li>
 *   <li>{@code GET  /api/callrelation/scan/progress}  — 扫描进度</li>
 *   <li>{@code GET  /api/callrelation/stats}           — 统计概览</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/callrelation")
public class CallRelationController {

    private final CallRelationScanner scanner;
    private final JdbcTemplate        jdbcTemplate;

    public CallRelationController(CallRelationScanner scanner, JdbcTemplate jdbcTemplate) {
        this.scanner      = scanner;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 全量扫描（同步，扫描完成后返回结果） */
    @PostMapping("/scan")
    public R<Map<String, Object>> fullScan() {
        return R.ok(scanner.fullScanSync());
    }

    /** 全量扫描（异步，立即返回） */
    @PostMapping("/scan/async")
    public R<String> fullScanAsync() {
        scanner.startFullScan();
        return R.ok("调用关系扫描已启动，通过 GET /api/callrelation/scan/progress 查询进度");
    }

    /** 查询扫描进度 */
    @GetMapping("/scan/progress")
    public R<Map<String, Object>> scanProgress() {
        return R.ok(scanner.getProgress());
    }

    /** 统计概览 */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        try {
            s.put("totalEdges", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM call_relation", Long.class));
            s.put("violations", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM call_relation WHERE rule_violation = 1", Long.class));
            s.put("crossDomain", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM call_relation WHERE cross_domain = 1", Long.class));
            s.put("sysUtilEdges", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM call_relation WHERE callee_type != 'bcc'", Long.class));
            s.put("daoEdges", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM call_relation WHERE callee_type = 'bcc'", Long.class));

            List<Map<String, Object>> byCallerType = jdbcTemplate.queryForList(
                "SELECT caller_type, COUNT(*) AS cnt FROM call_relation GROUP BY caller_type ORDER BY cnt DESC"
            );
            s.put("byCallerType", byCallerType);

            List<Map<String, Object>> byCalleeType = jdbcTemplate.queryForList(
                "SELECT callee_type, COUNT(*) AS cnt FROM call_relation GROUP BY callee_type ORDER BY cnt DESC"
            );
            s.put("byCalleeType", byCalleeType);
        } catch (Exception e) {
            s.put("error", "call_relation 表不存在或查询失败: " + e.getMessage());
        }
        return R.ok(s);
    }
}
