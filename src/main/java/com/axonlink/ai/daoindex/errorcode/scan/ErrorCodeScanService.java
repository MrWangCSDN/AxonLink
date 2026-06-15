package com.axonlink.ai.daoindex.errorcode.scan;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.errorcode.attribution.ErrorCodeAttributionService;
import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 错误码扫描编排：启动后异步全量扫 → 重建 dii_error_code → 物化 dii_tx_error_code。
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

    private volatile boolean scanning = false;

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

    /** 启动后异步触发：晚于 ProjectIndexer，sleep(3000) 后扫。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread t = new Thread(this::runSafely, "error-code-scan");
        t.setDaemon(true);
        t.start();
    }

    /** 手动重扫入口（Controller rescan 调用，见 Task 9）。 */
    public void triggerRescan() {
        Thread t = new Thread(this::runSafely, "error-code-rescan");
        t.setDaemon(true);
        t.start();
    }

    private void runSafely() {
        try {
            Thread.sleep(3000);   // 硬编码延迟，晚于符号索引（契约 §3.1）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (scanning) {
            log.warn("[error-code] 上一轮扫描进行中，跳过本次触发");
            return;
        }
        scanning = true;
        try {
            List<ErrorCodeThrow> throwDetails = scanAllSources();
            dao.rebuildThrows(throwDetails);                    // Task 5：重建明细
            attribution.materializeTransactionErrorCodes();     // Task 6：物化
        } catch (org.springframework.dao.DataAccessException ex) {
            // 缺表/结果库异常（Spring JDBC 把 SQLException 包成 unchecked DataAccessException，
            // 缺表为 BadSqlGrammarException）→ WARN 跳过，不 crash 启动
            log.warn("[error-code] 结果库异常（可能缺表，需人工执行 daoindex/V24），本轮扫描跳过：{}",
                    ex.getMessage());
        } catch (Exception ex) {
            log.error("[error-code] 扫描异常", ex);
        } finally {
            scanning = false;
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
                    .filter(p -> p.toString().contains("/target/gen/"))
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
