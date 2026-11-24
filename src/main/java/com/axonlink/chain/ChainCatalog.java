package com.axonlink.chain;

import com.axonlink.config.Neo4jConfig;
import com.axonlink.service.Neo4jGraphBuilder;
import com.axonlink.service.Neo4jGraphBuildCompletedEvent;
import com.axonlink.service.ServiceNodeCache;
import org.neo4j.driver.Driver;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
final class ChainCatalog {
    private static final Logger log = LoggerFactory.getLogger(ChainCatalog.class);
    private static final long MAX_AGE = Duration.ofMinutes(15).toNanos();
    private final ObjectProvider<Driver> drivers;
    private final Neo4jConfig config;
    private final Neo4jGraphBuilder builder;
    private final ServiceNodeCache metadata;
    private volatile String buildTime;
    private volatile Loaded current;
    private record Loaded(ChainGraph graph, long loadedAt, String buildTime) {}

    ChainCatalog(ObjectProvider<Driver> drivers, Neo4jConfig config, Neo4jGraphBuilder builder,
                 ServiceNodeCache metadata) {
        this.drivers = drivers;
        this.config = config;
        this.builder = builder;
        this.metadata = metadata;
    }

    @EventListener
    void rebuilt(Neo4jGraphBuildCompletedEvent event) {
        buildTime = Instant.now().toString();
        current = null;
    }

    ChainGraph get(String requestId) {
        checkReady();
        Loaded existing = current;
        if (fresh(existing)) return existing.graph;
        return load(requestId);
    }

    private boolean fresh(Loaded item) {
        return item != null && java.util.Objects.equals(item.buildTime, buildTime)
            && System.nanoTime() - item.loadedAt < MAX_AGE;
    }

    private synchronized ChainGraph load(String requestId) {
        Loaded existing = current;
        if (fresh(existing)) return existing.graph;
        checkReady();
        String before = buildTime;
        // 定时复读也覆盖其它进程导入的图。只有观察到本进程完成构建时才能给出对应时间。
        String knownSnapshot = existing == null || !java.util.Objects.equals(existing.buildTime,before) ? before : null;
        Driver driver = drivers.getIfAvailable();
        if (driver == null) throw notReady();
        long start = System.nanoTime();
        log.info("链路查询索引开始装载 requestId={}",requestId);
        try (var session = driver.session()) {
            ChainGraph graph = session.executeRead(tx -> read(tx, knownSnapshot));
            checkReady();
            if (!java.util.Objects.equals(before, buildTime)) throw notReady();
            if (graph.nodes.isEmpty()) throw notReady();
            current = new Loaded(graph, System.nanoTime(), before);
            log.info("链路查询索引就绪 requestId={} nodes={} snapshotAt={} elapsedMs={}",requestId,graph.nodes.size(),
                graph.snapshotAt,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            return graph;
        }
    }

    private ChainGraph read(TransactionContext tx, String snapshotAt) {
        ChainGraph graph = new ChainGraph(snapshotAt);
        Map<Long, ChainGraph.Node> dbIds = new HashMap<>();
        tx.run("""
            MATCH (n) WHERE any(label IN labels(n) WHERE label IN $labels)
            RETURN id(n) AS dbId, head([l IN labels(n) WHERE l IN $labels]) AS label, properties(n) AS props
            ORDER BY dbId
            """, Values.parameters("labels", ChainGraph.LABELS)).forEachRemaining(row -> {
                var node = new ChainGraph.Node(row.get("label").asString(), row.get("props").asMap());
                var canonical = graph.nodes.putIfAbsent(node.id, node);
                dbIds.put(row.get("dbId").asLong(), canonical == null ? node : canonical);
            });
        tx.run("""
            MATCH (a)-[r:HAS_FLOW|HAS_STEP|HAS_BRANCH|EXECUTES|NEXT|CALLS_SERVICE|RESOLVES_TO_METHOD|
                         IMPLEMENTS_BY|DECLARES_OPERATION|CALLS|SELF_CALLS|SYS_UTIL_CALLS|DAO_CALLS|
                         EXPOSES_DAO|IMPLEMENTS|EXTENDS]->(b)
            RETURN id(a) AS a, id(b) AS b, type(r) AS rel, coalesce(r.lineNo, 0) AS line, r.methodName AS method
            """).forEachRemaining(row -> {
                var from = dbIds.get(row.get("a").asLong());
                var to = dbIds.get(row.get("b").asLong());
                if (from != null && to != null) graph.raw(new ChainGraph.Edge(from, row.get("rel").asString(), to,
                    row.get("line").asInt(), row.get("method").asString(null), null));
            });
        graph.finish(metadata);
        return graph;
    }

    private void checkReady() {
        var progress = builder.getProgress();
        if (!config.isEnabled() || Boolean.TRUE.equals(progress.get("building")) || "error".equals(progress.get("phase"))) {
            throw notReady();
        }
    }

    private static ChainResult.Problem notReady() {
        return new ChainResult.Problem("GRAPH_NOT_READY", "交易图尚未就绪或正在重建");
    }
}
