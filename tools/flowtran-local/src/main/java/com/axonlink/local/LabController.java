package com.axonlink.local;

import com.axonlink.local.SqliteSnapshots.Snapshot;
import com.axonlink.local.SqliteSnapshots.Summary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/api")
final class LabController {
    private static final Logger log = LoggerFactory.getLogger(LabController.class);

    private final SqliteSnapshots database;
    private final ReentrantLock scanLock = new ReentrantLock();
    private volatile Snapshot current;

    LabController(@Value("${lab.database}") String file, ObjectMapper json) throws IOException, SQLException {
        database = new SqliteSnapshots(Path.of(file), json);
        log.info("读取 SQLite 快照 db={}", database.file());
        current = database.load();
        Summary summary = current.summary();
        if (summary == null) {
            log.info("SQLite 中还没有扫描快照 db={}", database.file());
        } else {
            log.info("SQLite 快照已载入 db={} snapshot={} javaFiles={} edges={} issues={}",
                database.file(), summary.createdAt(), summary.javaFiles(), summary.edges(), summary.issueCount());
        }
    }

    @GetMapping("/state")
    State state() {
        return state(current);
    }

    @PostMapping(path = "/scan", consumes = MediaType.APPLICATION_JSON_VALUE)
    State scan(@RequestBody ScanSettings request) {
        ScanSettings settings;
        try {
            settings = request.normalize();
        } catch (IllegalArgumentException e) {
            log.warn("扫描参数无效 reason={}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        ScanProgress progress = new ScanProgress();
        if (!scanLock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有扫描正在运行");
        }
        try {
            progress.start(settings, database.file());
            Graph graph = new Graph(progress);
            SourceLayout layout = new SourceLayout(settings.workspacePaths());
            MetadataResourceScanner resources = new MetadataResourceScanner(progress.id());
            FlowServiceMetadataResolver services = new FlowServiceMetadataResolver(
                resources, settings.workspaceRoots(), layout, graph, progress);
            TableMetadataResolver tables = new TableMetadataResolver(resources, settings.workspaceRoots(), graph, progress);
            JavaCallScanner java = new JavaCallScanner(graph, settings, layout, services, tables, progress);
            int javaFiles = java.scan();
            new FlowXmlScanner(graph, layout, services, progress).scan(resources, settings.workspaceRoots());
            progress.begin("服务实现和 SysUtil 补边");
            int before = graph.edges().size();
            java.backfillMetadataLinks();
            progress.finish("newEdges=" + (graph.edges().size() - before));

            var summary = new Summary(Instant.now().toString(), settings, javaFiles, graph.counts(),
                graph.edges().size(), graph.issues().size(), progress.elapsedMillis());
            Snapshot snapshot = new Snapshot(summary, graph);
            progress.begin("SQLite 快照写入");
            database.replace(snapshot);
            current = snapshot;
            progress.finish("db=" + database.file());
            progress.complete(snapshot);
            return state(snapshot);
        } catch (SQLException | IOException e) {
            progress.failed(e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存 SQLite 快照失败 " + e.getMessage(), e);
        } catch (RuntimeException e) {
            progress.failed(e);
            throw e;
        } finally {
            scanLock.unlock();
        }
    }

    @GetMapping("/chain")
    ChainProjection.View chain(@RequestParam("code") String code) {
        long start = System.nanoTime();
        Snapshot snapshot = current;
        Graph graph = snapshot.graph();
        Graph.Node tx = transaction(graph, code);
        ChainProjection.View view = new ChainProjection(graph, tx).read(snapshotTime(snapshot));
        if (log.isDebugEnabled()) {
            log.debug("交易链路聚合 code={} snapshot={} nodes={} edges={} componentCalls={} tableAccesses={} elapsedMs={}", code,
                view.snapshotAt(), view.nodes().size(), view.edges().size(),
                view.componentCalls().size(), view.tableAccesses().size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
        return view;
    }

    @GetMapping(path = "/chain/export", produces = MediaType.APPLICATION_JSON_VALUE)
    OrderedChainExport.Document export(@RequestParam("code") String code,
                                       @RequestParam(name = "snapshotAt", required = false) String expectedSnapshot) {
        Snapshot snapshot = current;
        String snapshotAt = snapshotTime(snapshot);
        if (expectedSnapshot != null && !expectedSnapshot.equals(snapshotAt)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "扫描快照已更新，请重新载入交易后导出");
        }
        Graph.Node tx = transaction(snapshot.graph(), code);
        var document = new OrderedChainExport(snapshot.graph(), tx).read(snapshotAt);
        log.debug("导出交易 JSON code={} snapshot={} relations={}", code, snapshotAt, document.relations().size());
        return document;
    }

    private static String snapshotTime(Snapshot snapshot) {
        return snapshot.summary() == null ? null : snapshot.summary().createdAt();
    }

    private static Graph.Node transaction(Graph graph, String code) {
        Graph.Node tx = graph.get("Transaction", "TX:" + code);
        if (tx == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前快照中没有交易 " + code);
        }
        return tx;
    }

    @GetMapping("/node")
    NodeView node(@RequestParam("id") String id) {
        Graph graph = current.graph();
        Graph.Node node = graph.get(id);
        if (node == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前快照中没有该节点");
        }
        var outgoing = graph.outgoing(id).stream().map(edge -> new Related(edge, graph.get(edge.to()))).toList();
        var incoming = graph.incoming(id).stream().map(edge -> new Related(edge, graph.get(edge.from()))).toList();
        return new NodeView(node, outgoing, incoming, graph.issues(id));
    }

    @GetMapping("/methods")
    List<Graph.Node> methods(@RequestParam("query") String query) {
        String keyword = query.trim().toLowerCase(Locale.ROOT);
        if (keyword.isEmpty()) {
            return List.of();
        }
        return current.graph().nodes("Method").stream()
            .filter(node -> node.key().toLowerCase(Locale.ROOT).contains(keyword))
            .sorted(Comparator.comparing(Graph.Node::key)).limit(30).toList();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException error) {
        String message = Graph.firstNonBlank(error.getReason(), "请求处理失败");
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", message));
    }

    private State state(Snapshot snapshot) {
        List<Transaction> transactions = snapshot.graph().nodes("Transaction").stream()
            .map(node -> new Transaction(node.text("id"), node.text("name"), node.text("domainKey")))
            .sorted(Comparator.comparing(Transaction::code)).toList();
        return new State(snapshot.summary(), database.file().toString(), ScanSettings.DEFAULTS,
            ScanSettings.EXAMPLE_WORKSPACE, transactions, snapshot.graph().issues().stream().limit(40).toList());
    }

    record State(Summary summary, String dbFile, ScanSettings defaults, String exampleWorkspace,
                 List<Transaction> transactions, List<Graph.Issue> issues) {}
    record Transaction(String code, String name, String domain) {}
    record Related(Graph.Edge edge, Graph.Node node) {}
    record NodeView(Graph.Node node, List<Related> outgoing, List<Related> incoming, List<Graph.Issue> issues) {}
}
