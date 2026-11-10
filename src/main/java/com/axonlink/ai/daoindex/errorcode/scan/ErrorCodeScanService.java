package com.axonlink.ai.daoindex.errorcode.scan;

import com.axonlink.ai.daoindex.errorcode.attribution.ErrorCodeAttributionService;
import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.service.Neo4jGraphBuildCompletedEvent;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 错误码扫描编排：
 * <ul>
 *   <li>启动（ApplicationReadyEvent）→ 只重建 dii_error_code 明细（不依赖 Neo4j 图）；</li>
 *   <li>Neo4j 图构建完成（{@link Neo4jGraphBuildCompletedEvent}）→ 全量重建：明细 + 交易维度
 *       物化 dii_tx_error_code（此刻图已就绪，避免与图构建的时序竞态）；</li>
 *   <li>手动 rescan → 全量。</li>
 * </ul>
 * 每轮构建带类型解析的 JavaParser，扫描完成即释放；不挂入 ProjectIndexer。
 * 缺表（SQLException）→ WARN 跳过，不 crash 启动（契约 §3.1/§4.6）。
 */
@Component
public class ErrorCodeScanService {

    private static final Logger log = LoggerFactory.getLogger(ErrorCodeScanService.class);

    private final DiiErrorCodeDao dao;                          // Task 5
    private final ErrorCodeAttributionService attribution;     // Task 6

    // 与建图（Neo4jGraphBuilder）同一套源码根：错误码必须扫「和建图完全相同的源文件」，
    // 否则 classFqn 对不上、物化 join 不到（业务 throw 在 src/main/java，不在 target/gen）。
    @Value("${project.source-roots:}")
    private String sourceRoots;
    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    /**
     * 单飞守卫：用 AtomicBoolean.compareAndSet 原子地「检查并置位」，
     * 避免 volatile boolean 的 check-then-set 竞态——否则并发触发（如 onReady 与
     * triggerRescan 同时进入、或两次快速重扫）会同时读到 false 都放行，导致两个扫描
     * 交错执行整表 DELETE+INSERT（rebuildThrows / materializeTransactionErrorCodes），
     * 破坏数据完整性（契约 §4.6）。
     */
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public ErrorCodeScanService(DiiErrorCodeDao dao,
                                ErrorCodeAttributionService attribution) {
        this.dao = dao;
        this.attribution = attribution;
    }

    /**
     * 模块名解析：取路径中最近一层 *-bcc 段；无则 null。
     * 自带等价实现，不依赖 ProjectIndexer/Neo4jGraphBuilder 的 private detectModule。
     */
    public static String detectModule(Path file) {
        String module = null;
        for (Path seg : file) {
            String name = seg.toString();
            if (name.endsWith("-bcc")) {
                module = name;   // 持续覆盖 → 最终保留最近（最深）一层
            }
        }
        return module;
    }

    /**
     * 启动后异步触发：**只重建 dii_error_code 明细**（不依赖 Neo4j 图，启动即反映源码）。
     * 交易维度物化交给 {@link #onGraphBuilt}，避免在图就绪前物化扫到空图、把
     * dii_tx_error_code 清空（图构建开头会清空旧图、全程数分钟，与本回调并发）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        startAsync("error-code-scan", true, false);   // delay=true（晚于符号索引），materialize=false（不物化）
    }

    /**
     * Neo4j 图构建流水线完成 → **全量重建**：明细 + 交易维度物化（此刻图已就绪）。
     * 覆盖「图被重建」的全部场景：启动自动构建 / benchmark webhook 重建 / REST 手动构建。
     */
    @EventListener(Neo4jGraphBuildCompletedEvent.class)
    public void onGraphBuilt(Neo4jGraphBuildCompletedEvent event) {
        log.info("[error-code] 收到 Neo4j 图构建完成事件 op={}，触发全量重建（含交易维度物化）",
                event.getOperationId());
        startAsync("error-code-graph-built", false, true);   // delay=false，materialize=true
    }

    /** 手动重扫入口（Controller rescan 调用，见 Task 9）：全量（明细 + 物化）。 */
    public void triggerRescan() {
        startAsync("error-code-rescan", false, true);
    }

    private void startAsync(String threadName, boolean delay, boolean materialize) {
        Thread t = new Thread(() -> runSafely(delay, materialize), threadName);
        t.setDaemon(true);
        t.start();
    }

    /**
     * @param delay       是否先 sleep(3000)（仅启动期需要，晚于符号索引）
     * @param materialize 是否在重建明细后物化交易维度（需 Neo4j 图就绪）
     *
     * <p>包级可见（非 private）：供同包单测验证「明细总重建、物化按 materialize 门控」。
     */
    void runSafely(boolean delay, boolean materialize) {
        if (delay) {
            try {
                Thread.sleep(3000);   // 硬编码延迟，晚于符号索引（契约 §3.1）
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // CAS 原子「检查+置位」：只有把 false→true 成功的线程进入工作段，其余触发直接跳过，
        // 避免并发触发（onReady / 图构建完成 / 手动重扫）交错执行整表 DELETE+INSERT（契约 §4.6）。
        if (!scanning.compareAndSet(false, true)) {
            log.warn("[error-code] 上一轮扫描进行中，跳过本次触发");
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            ScanResult scan = scanAllSources();
            // ── 空结果拒绝覆盖（2026-07-15 内网事故守卫）──
            // ① 收集到 0 个源文件（scanAllSources 返回 null）：源码目录大概率正被 benchmark
            //    全量替换，此时 DELETE 整表会把好数据抹成 0 行 → 保留旧数据，等下轮触发。
            if (scan == null) {
                log.error("[error-code] 本轮扫描中止：源码根为空/不可达（疑似源码替换窗口），"
                        + "保留 dii_error_code / dii_tx_error_code 旧数据");
                return;
            }
            // 原始候选为零且旧明细非空时沿用源码缺失保护，初次空库仍保持原有扫描行为。
            if (scan.rawThrowCount() == 0 && dao.countThrows() > 0) {
                log.error("[error-code] 本轮扫描中止：原始 throw 候选为 0 且库中现存明细非空，"
                                + "保留旧数据 parsedFiles={} skippedLargeFiles={}",
                        scan.parsedFiles(), scan.skippedLargeFiles());
                return;
            }
            List<ErrorCodeThrow> throwDetails = scan.throwsToKeep();
            boolean allowEmpty = scan.rawThrowCount() > 0 && throwDetails.isEmpty();
            long t1 = System.currentTimeMillis();
            dao.rebuildThrows(throwDetails);                        // 重建明细 dii_error_code
            long t2 = System.currentTimeMillis();
            log.info("[error-code] 明细已就绪 rawThrows={} effectiveThrows={} parsedFiles={} "
                            + "skippedLargeFiles={} 扫描耗时={}ms 写库耗时={}ms",
                    scan.rawThrowCount(), throwDetails.size(), scan.parsedFiles(),
                    scan.skippedLargeFiles(), t1 - t0, t2 - t1);
            if (materialize) {
                // 物化交易维度 dii_tx_error_code（需图就绪）。比明细慢——每交易跑 getChain 级解析，
                // 已并行化（见 ErrorCodeAttributionService）。物化耗时由其内部「物化完成 ... 耗时=」日志体现。
                ErrorCodeAttributionService.MaterializeOutcome outcome =
                        attribution.materializeTransactionErrorCodes(allowEmpty);
                if (outcome.isComplete()) {
                    log.info("[error-code] 本轮全量重建结束（明细+物化）总耗时={}ms", System.currentTimeMillis() - t0);
                } else {
                    log.warn("[error-code] 本轮明细已更新，交易物化未完成并保留旧结果，总耗时={}ms",
                            System.currentTimeMillis() - t0);
                }
            }
        } catch (org.springframework.dao.DataAccessException ex) {
            // 缺表/结果库异常（Spring JDBC 把 SQLException 包成 unchecked DataAccessException，
            // 缺表为 BadSqlGrammarException；也可能是写入失败如唯一键冲突/列截断——
            // 2026-07-15 曾把 uk_tx_throw 冲突误读成缺表）→ ERROR 跳过，不 crash 启动。
            // 写方法已事务化，此处到达时旧数据未被破坏。
            log.error("[error-code] 结果库异常（缺表需人工执行 daoindex/V24~V26；或写入失败，"
                    + "事务已回滚保留旧数据），本轮扫描跳过：{}", ex.getMessage());
        } catch (Exception ex) {
            log.error("[error-code] 扫描异常", ex);
        } finally {
            ErrorCodeExceptionTypeResolver.releaseCaches();
            scanning.set(false);
        }
    }

    /** 仅完整扫描返回结果；目录遍历、读取或语法解析失败时中止，拒绝覆盖旧数据。 */
    private ScanResult scanAllSources() throws IOException {
        List<ErrorCodeThrow> all = new ArrayList<>();
        AtomicLong seq = new AtomicLong(0);
        long maxBytes = 500L * 1024;   // 与 ProjectIndexer 一致：>500KB 跳过
        List<Path> roots = collectSourceRoots();
        List<Path> files = collectSourceFiles(roots);
        log.info("[error-code] 收集到 {} 个 .java 源文件待扫描", files.size());
        if (files.isEmpty()) {
            return null;   // 源码根为空/不可达 → 交由 runSafely 中止本轮，保留旧数据
        }
        JavaParser parser = ErrorCodeExceptionTypeResolver.createParser(roots);
        long rawThrowCount = 0;
        int parsedFiles = 0;
        int skippedLargeFiles = 0;
        for (Path file : files) {
            if (Files.size(file) > maxBytes) {
                skippedLargeFiles++;
                log.debug("[error-code] 文件超过 {} bytes，沿用大文件跳过规则 file={}", maxBytes, file);
                continue;
            }
            ThrowStmtVisitor visitor = scanOneFile(file, parser, seq);
            all.addAll(visitor.getResults());
            rawThrowCount += visitor.getRawThrowCount();
            parsedFiles++;
        }
        return new ScanResult(all, rawThrowCount, parsedFiles, skippedLargeFiles);
    }

    private record ScanResult(List<ErrorCodeThrow> throwsToKeep, long rawThrowCount,
                              int parsedFiles, int skippedLargeFiles) {}

    /**
     * 收集 .java 源文件，<b>与建图 {@code Neo4jGraphBuilder.collectFiles} 完全同口径</b>：
     * <ul>
     *   <li>{@code project.source-roots}：显式 src 目录（逗号分隔）；</li>
     *   <li>{@code project.workspace-roots}：递归（深度 8）发现所有以 {@code src/main/java}
     *       或 {@code target/gen} 结尾的目录；</li>
     *   <li>收 {@code .java}、排除路径含 {@code /test/}。</li>
     * </ul>
     * 必须与建图扫的是同一批源文件——否则 classFqn 对不上、物化 join 不到
     * （业务 throw 在 src/main/java，旧实现只过滤 target/gen 故 throw=0）。
     */
    private List<Path> collectSourceRoots() throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        if (sourceRoots != null && !sourceRoots.isBlank()) {
            for (String r : sourceRoots.split(",")) {
                if (!r.isBlank()) roots.add(Path.of(r.trim()).toAbsolutePath().normalize());
            }
        }
        if (workspaceRoots != null && !workspaceRoots.isBlank()) {
            for (String w : workspaceRoots.split(",")) {
                String ws = w.trim();
                if (ws.isEmpty()) continue;
                Path wsPath = Path.of(ws).toAbsolutePath().normalize();
                if (!Files.isDirectory(wsPath)) {
                    throw new IOException("工作区源码目录不存在或不可访问：" + wsPath);
                }
                try (Stream<Path> walk = Files.walk(wsPath, 8)) {
                    walk.filter(Files::isDirectory)
                        .filter(p -> {
                            String rel = wsPath.relativize(p).toString().replace('\\', '/');
                            return rel.endsWith("src/main/java") || rel.endsWith("target/gen");
                        })
                        .forEach(roots::add);
                }
            }
        }
        return new ArrayList<>(roots);
    }

    private List<Path> collectSourceFiles(List<Path> roots) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IOException("源码目录不存在或不可访问：" + root);
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().replace('\\', '/').contains("/test/"))
                    .forEach(files::add);
            }
        }
        return new ArrayList<>(files);
    }

    private ThrowStmtVisitor scanOneFile(Path file, JavaParser parser, AtomicLong seq) throws IOException {
        ParseResult<CompilationUnit> parsed = parser.parse(file);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            throw new IOException("Java 源文件解析失败，停止本轮扫描：" + file + " " + parsed.getProblems());
        }
        CompilationUnit cu = parsed.getResult().get();
        ThrowStmtVisitor visitor = new ThrowStmtVisitor(cu, file.toString(), detectModule(file), seq);
        visitor.visit(cu, null);
        return visitor;
    }
}
