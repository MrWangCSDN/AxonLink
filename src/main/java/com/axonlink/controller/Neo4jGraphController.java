package com.axonlink.controller;

import com.axonlink.common.R;
import com.axonlink.service.Neo4jGraphBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Neo4j AST 图构建与查询接口。
 *
 * <ul>
 *   <li>{@code POST /api/neo4j/build}          — 全量构建（同步）</li>
 *   <li>{@code POST /api/neo4j/build/async}     — 异步构建</li>
 *   <li>{@code GET  /api/neo4j/build/progress}  — 构建进度</li>
 *   <li>{@code GET  /api/neo4j/stats}            — 图统计</li>
 *   <li>{@code GET  /api/neo4j/query/callchain}  — 调用链查询</li>
 *   <li>{@code GET  /api/neo4j/query/impact}     — 反向影响面查询</li>
 *   <li>{@code GET  /api/neo4j/query/tables}     — 表访问分析（从入口方法推导所有涉及的 DB 表）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/neo4j")
public class Neo4jGraphController {

    private final Neo4jGraphBuilder builder;

    public Neo4jGraphController(Neo4jGraphBuilder builder) {
        this.builder = builder;
    }

    /** 全量构建（同步） */
    @PostMapping("/build")
    public R<Map<String, Object>> build() {
        return R.ok(builder.buildSync());
    }

    /** 异步构建 */
    @PostMapping("/build/async")
    public R<String> buildAsync() {
        builder.startBuildAsync();
        return R.ok("Neo4j 图构建已启动，通过 GET /api/neo4j/build/progress 查看进度");
    }

    /** 构建进度 */
    @GetMapping("/build/progress")
    public R<Map<String, Object>> progress() {
        return R.ok(builder.getProgress());
    }

    /** 图统计 */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(builder.getStats());
    }

    /**
     * 调用链查询：从指定 FQN 向下穿透 N 层。
     * @param fqn   全限定类名
     * @param depth 穿透深度，默认 5，最大 10
     */
    @GetMapping("/query/callchain")
    public R<Map<String, Object>> callchain(
            @RequestParam String fqn,
            @RequestParam(defaultValue = "5") int depth) {
        return R.ok(builder.queryCallChain(fqn, depth));
    }

    /**
     * 反向影响面查询：谁调用了指定 FQN 的方法。
     * @param fqn   全限定类名
     * @param depth 反向追溯深度，默认 5，最大 10
     */
    @GetMapping("/query/impact")
    public R<Map<String, Object>> impact(
            @RequestParam String fqn,
            @RequestParam(defaultValue = "5") int depth) {
        return R.ok(builder.queryImpact(fqn, depth));
    }

    /**
     * 表访问分析：从指定方法签名出发，穿透调用链，统计所有涉及的 DAO 操作与数据库表。
     *
     * <p>示例：{@code GET /api/neo4j/query/tables?sig=com.xxx.A%23a()&depth=10}
     *
     * <p>返回字段：
     * <ul>
     *   <li>daoClass   — DAO 类名，如 KLoanInfoDao</li>
     *   <li>tableName  — 推导表名，如 KLoanInfo</li>
     *   <li>operations — 操作方法列表，如 [query, insert]</li>
     *   <li>callCount  — 链路中调用次数</li>
     * </ul>
     *
     * @param sig   入口方法签名，格式 com.pkg.ClassName#methodName(ParamType,...)
     * @param depth 穿透深度，默认 10，最大 15
     */
    @GetMapping("/query/tables")
    public R<Map<String, Object>> tables(
            @RequestParam String sig,
            @RequestParam(defaultValue = "10") int depth) {
        return R.ok(builder.queryTables(sig, depth));
    }
}
