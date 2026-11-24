package com.axonlink.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class ScanProgress {
    private static final Logger log = LoggerFactory.getLogger(ScanProgress.class);
    private static final int FILE_LOG_INTERVAL = 100;
    private static final long FILE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int WARN_EXAMPLES_PER_RULE = 3;

    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final long startedAt = System.nanoTime();
    private final Map<String, Integer> issuesByRule = new LinkedHashMap<>();
    private String phase = "初始化";
    private long phaseStartedAt;
    private long lastFileLogAt;

    String id() {
        return id;
    }

    void start(ScanSettings settings, Path database) {
        log.info("[scan={}] 开始扫描 workspaceRoots={} sourceRoots={} includePackages={} maxFileKb={} db={}",
            id, settings.workspaceRoots(), settings.sourceRoots(), settings.includePackages(),
            settings.maxFileKb(), database);
    }

    void begin(String phase) {
        this.phase = phase;
        phaseStartedAt = System.nanoTime();
        lastFileLogAt = phaseStartedAt;
        log.info("[scan={}] {}开始", id, phase);
    }

    void file(Path file, int index, int total) {
        long now = System.nanoTime();
        if (index == 1 || index == total || index % FILE_LOG_INTERVAL == 0
                || now - lastFileLogAt >= FILE_LOG_INTERVAL_NANOS) {
            log.info("[scan={}] {}正在处理文件 {}/{} file={}", id, phase, index, total, file);
            lastFileLogAt = now;
        } else {
            log.debug("[scan={}] {}正在处理文件 {}/{} file={}", id, phase, index, total, file);
        }
    }

    void finish(String details) {
        log.info("[scan={}] {}完成 elapsedMs={} {}", id, phase, elapsedSince(phaseStartedAt), details);
    }

    void skip(Path file, int line, String reason) {
        log.debug("[scan={}] {}跳过 file={} line={} reason={}", id, phase, file, line, reason);
    }

    void issue(Graph.Issue issue) {
        int count = issuesByRule.merge(issue.rule(), 1, Integer::sum);
        String format = "[scan={}] {}诊断 rule={} file={} line={} node={} reason={}";
        if (count <= WARN_EXAMPLES_PER_RULE) {
            log.warn(format, id, phase, issue.rule(), issue.file(), issue.line(), issue.nodeId(), issue.message());
        } else {
            log.debug(format, id, phase, issue.rule(), issue.file(), issue.line(), issue.nodeId(), issue.message());
        }
    }

    void edge(Graph.Edge edge, Graph.Node source) {
        if (log.isDebugEnabled()) {
            log.debug("[scan={}] {}建边 type={} from={} to={} file={} line={} method={}",
                id, phase, edge.type(), edge.from(), edge.to(), source.text("filePath"), edge.line(), edge.methodName());
        }
    }

    void complete(SqliteSnapshots.Snapshot snapshot) {
        var summary = snapshot.summary();
        Map<String, Integer> edgesByType = new LinkedHashMap<>();
        for (Graph.Edge edge : snapshot.graph().edges()) {
            edgesByType.merge(edge.type(), 1, Integer::sum);
        }
        log.info("[scan={}] 扫描完成 snapshot={} totalMs={} javaFiles={} nodesByKind={} edges={} edgesByType={} issues={}",
            id, summary.createdAt(), elapsedMillis(), summary.javaFiles(), summary.nodes(), summary.edges(),
            edgesByType, summary.issueCount());
        if (!issuesByRule.isEmpty()) {
            log.warn("[scan={}] 扫描诊断汇总 issuesByRule={}", id, issuesByRule);
        }
    }

    void failed(Throwable error) {
        log.error("[scan={}] 扫描失败 phase={} totalMs={} issuesByRule={}", id, phase, elapsedMillis(), issuesByRule, error);
    }

    long elapsedMillis() {
        return elapsedSince(startedAt);
    }

    private static long elapsedSince(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
