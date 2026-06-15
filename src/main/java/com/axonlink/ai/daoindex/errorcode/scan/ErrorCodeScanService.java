package com.axonlink.ai.daoindex.errorcode.scan;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.errorcode.attribution.ErrorCodeAttributionService;
import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.service.Neo4jGraphBuildCompletedEvent;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * 复用 JavaParser(JAVA_17) + ThreadLocal；不挂入 ProjectIndexer（单一职责）。
 * 缺表（SQLException）→ WARN 跳过，不 crash 启动（契约 §3.1/§4.6）。
 */
@Component
public class ErrorCodeScanService {

    private static final Logger log = LoggerFactory.getLogger(ErrorCodeScanService.class);

    /** JavaParser 非线程安全，每线程独立实例。 */
    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() ->
            new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)));

    private final DaoIndexAnalysisProperties props;
    private final DiiErrorCodeDao dao;                          // Task 5
    private final ErrorCodeAttributionService attribution;     // Task 6

    /**
     * 单飞守卫：用 AtomicBoolean.compareAndSet 原子地「检查并置位」，
     * 避免 volatile boolean 的 check-then-set 竞态——否则并发触发（如 onReady 与
     * triggerRescan 同时进入、或两次快速重扫）会同时读到 false 都放行，导致两个扫描
     * 交错执行整表 DELETE+INSERT（rebuildThrows / materializeTransactionErrorCodes），
     * 破坏数据完整性（契约 §4.6）。
     */
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public ErrorCodeScanService(DaoIndexAnalysisProperties props,
                                DiiErrorCodeDao dao,
                                ErrorCodeAttributionService attribution) {
        this.props = props;
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
            List<ErrorCodeThrow> throwDetails = scanAllSources();
            dao.rebuildThrows(throwDetails);                        // 重建明细 dii_error_code
            if (materialize) {
                attribution.materializeTransactionErrorCodes();    // 物化交易维度 dii_tx_error_code（需图就绪）
            }
        } catch (org.springframework.dao.DataAccessException ex) {
            // 缺表/结果库异常（Spring JDBC 把 SQLException 包成 unchecked DataAccessException，
            // 缺表为 BadSqlGrammarException）→ WARN 跳过，不 crash 启动
            log.warn("[error-code] 结果库异常（可能缺表，需人工执行 daoindex/V24），本轮扫描跳过：{}",
                    ex.getMessage());
        } catch (Exception ex) {
            log.error("[error-code] 扫描异常", ex);
        } finally {
            scanning.set(false);
        }
    }

    /** 遍历所有源码根，AST 扫描 throw 错误码。 */
    private List<ErrorCodeThrow> scanAllSources() {
        DaoIndexAnalysisProperties.Scan scan = props.getScan();
        List<ErrorCodeThrow> all = new ArrayList<>();
        AtomicLong seq = new AtomicLong(0);
        long maxBytes = 500L * 1024;   // 与 ProjectIndexer 一致：>500KB 跳过
        for (String root : scan.getProjectRoots()) {
            Path base = Path.of(root);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/" + scan.getSourceRootRelative() + "/"))
                    .forEach(p -> scanOneFile(p, maxBytes, seq, all));
            } catch (IOException e) {
                log.warn("[error-code] 遍历源码根失败 root={}", root, e);
            }
        }
        return all;
    }

    private void scanOneFile(Path file, long maxBytes, AtomicLong seq, List<ErrorCodeThrow> sink) {
        try {
            if (Files.size(file) > maxBytes) {
                return;   // 大文件跳过深扫
            }
            CompilationUnit cu = PARSER.get().parse(file).getResult().orElse(null);
            if (cu == null) {
                return;
            }
            String module = detectModule(file);
            ThrowStmtVisitor v = new ThrowStmtVisitor(cu, file.toString(), module, seq);
            v.visit(cu, null);
            synchronized (sink) {
                sink.addAll(v.getResults());
            }
        } catch (Exception e) {
            log.warn("[error-code] 解析文件失败 file={}", file, e);
        }
    }
}
