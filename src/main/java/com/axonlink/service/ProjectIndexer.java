package com.axonlink.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 项目源码全量索引器
 *
 * Spring Boot 启动时扫描配置的所有源码根目录，构建三张内存表：
 *   classIndex   : 简单类名   → 文件绝对路径   (用于 Ctrl+Click 跳转)
 *   fqnIndex     : 完全限定名 → 文件绝对路径   (精确查找)
 *   symbolIndex  : 文件路径   → [方法符号列表]  (加速 buildResponse)
 *   typeMapIndex : 文件路径   → {字段名→类型}   (加速跨文件类型推断)
 */
@Component
public class ProjectIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndexer.class);

    /**
     * 多个源码根目录，逗号分隔。支持三类路径：
     *   src/main/java                    ← 业务源码，无需编译即可索引
     *   target/generated-sources/...     ← 注解处理器生成的 .java，需编译后才有
     */
    @Value("${project.source-roots:}")
    private String sourceRootsConfig;

    /**
     * 并行索引线程数。
     *   0（默认）= 自动：取 CPU 核数的一半，最少 2 线程
     *   N       = 固定 N 个线程
     * 建议值：对于 SSD 机器可设为 CPU 核数；机械硬盘建议 2~4。
     */
    @Value("${project.index-threads:0}")
    private int indexThreads;

    // 简单类名 → Path（同名取最先扫描到的）
    private final Map<String, Path> classIndex   = new ConcurrentHashMap<>();
    // 完全限定类名 → Path
    private final Map<String, Path> fqnIndex     = new ConcurrentHashMap<>();
    // 文件绝对路径 → 符号列表（方法 + 类/接口/枚举声明）
    private final Map<String, List<Map<String, Object>>> symbolIndex  = new ConcurrentHashMap<>();
    // 文件绝对路径 → 字段类型映射（用于跨文件类型推断）
    private final Map<String, Map<String, String>>       typeMapIndex = new ConcurrentHashMap<>();

    // JavaParser 不是线程安全的：每个线程持有独立实例，通过 ThreadLocal 管理
    private static final ThreadLocal<JavaParser> PARSER_LOCAL = ThreadLocal.withInitial(() -> {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        return new JavaParser(cfg);
    });

    public ProjectIndexer() {
        // 无额外初始化；JavaParser 实例通过 PARSER_LOCAL 按需创建
    }

    /** 根据配置或 CPU 核数决定线程数 */
    private int resolveThreads() {
        if (indexThreads > 0) return indexThreads;
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    // ── 启动时执行索引 ────────────────────────────────────────────────────────
    @PostConstruct
    public void buildIndex() {
        doIndex(false);
    }

    /**
     * 热重载：清空旧索引后重新扫描所有配置的源码根目录。
     * 无需重启服务，适合编译后刷新 generated-sources 等场景。
     * @return 统计信息
     */
    public Map<String, Object> refresh() {
        log.info("[ProjectIndexer] 触发热重载，清空旧索引…");
        classIndex.clear();
        fqnIndex.clear();
        symbolIndex.clear();
        typeMapIndex.clear();
        return doIndex(true);
    }

    /**
     * 实际执行索引扫描。
     *
     * 策略：
     *   1. 顺序遍历各 source-root，收集所有 .java 文件路径（I/O）
     *   2. 用固定线程池并行解析（CPU-bound），每个线程用独立 JavaParser 实例
     *   3. 每个工程单独打印耗时，方便定位瓶颈
     */
    private Map<String, Object> doIndex(boolean startedByRefresh) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sourceRootsConfig == null || sourceRootsConfig.isBlank()) {
            log.info("[ProjectIndexer] 未配置 project.source-roots，跳过全量索引");
            result.put("skipped", true);
            return result;
        }

        int threads = resolveThreads();
        log.info("[ProjectIndexer] 启动索引，并行线程数 = {}（CPU核数 = {}）",
                threads, Runtime.getRuntime().availableProcessors());

        long totalStart = System.currentTimeMillis();
        AtomicInteger totalScanned = new AtomicInteger();
        AtomicInteger totalParsed  = new AtomicInteger();
        List<Map<String, Object>> rootStats = new ArrayList<>();

        // ── Step 1：按工程收集 .java 文件（顺序 I/O，避免并发文件系统竞争） ────
        // key = rootLabel, value = 该工程下的文件列表
        List<Map.Entry<String, List<Path>>> rootFiles = new ArrayList<>();

        for (String raw : sourceRootsConfig.split(",")) {
            String root = raw.trim();
            if (root.isEmpty()) continue;
            Path rootPath = Path.of(root);
            if (!Files.exists(rootPath)) {
                log.warn("[ProjectIndexer] 目录不存在，跳过：{}", root);
                continue;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(files::add);
            } catch (IOException e) {
                log.error("[ProjectIndexer] 遍历失败：{}", root, e);
            }
            if (!files.isEmpty()) rootFiles.add(Map.entry(root, files));
        }

        // ── Step 2：用线程池并行解析所有文件 ──────────────────────────────────
        // 使用自定义 ForkJoinPool，不污染公共池
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            for (Map.Entry<String, List<Path>> entry : rootFiles) {
                String root = entry.getKey();
                List<Path> files = entry.getValue();
                long rootStart = System.currentTimeMillis();
                AtomicInteger rootParsed = new AtomicInteger();

                pool.submit(() ->
                    files.parallelStream().forEach(p -> {
                        totalScanned.incrementAndGet();
                        if (indexFile(p)) {
                            totalParsed.incrementAndGet();
                            rootParsed.incrementAndGet();
                        }
                    })
                ).get(); // 等待该工程全部解析完成，确保日志顺序清晰

                long rootElapsed = System.currentTimeMillis() - rootStart;
                log.info("[ProjectIndexer] ✓ {} 个文件 | {} ms | {}",
                        rootParsed.get(), rootElapsed, root);
                rootStats.add(Map.of("root", root, "parsed", rootParsed.get(), "ms", rootElapsed));
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("[ProjectIndexer] 并行索引异常", e);
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        log.info("[ProjectIndexer] 全部完成：{} 文件 / {} 类（含内部类）/ {} ms（{} 线程）",
                totalParsed.get(), classIndex.size(), totalElapsed, threads);

        result.put("threads",    threads);
        result.put("scanned",    totalScanned.get());
        result.put("parsed",     totalParsed.get());
        result.put("classCount", classIndex.size());
        result.put("fqnCount",   fqnIndex.size());
        result.put("elapsedMs",  totalElapsed);
        result.put("roots",      rootStats);
        return result;
    }

    /**
     * 解析单个 .java 文件并写入索引。
     * 使用统一的递归 visitor，一次遍历即可处理：
     *   - 顶层类/接口/枚举 → classIndex / fqnIndex
     *   - 内部类/静态嵌套类/枚举  → classIndex（文件路径同外部类）
     *   - 方法定义              → symbolIndex
     *   - 字段声明              → typeMapIndex（用于跨文件类型推断）
     * @return 是否成功解析
     */
    private boolean indexFile(Path file) {
        try {
            String source = Files.readString(file);
            // 使用 ThreadLocal 实例，避免多线程共用同一 JavaParser 导致竞态
            ParseResult<CompilationUnit> result = PARSER_LOCAL.get().parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return false;

            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString()).orElse("");

            List<Map<String, Object>> symbols = new ArrayList<>();
            Map<String, String>       typeMap = new LinkedHashMap<>();

            cu.accept(new VoidVisitorAdapter<Void>() {

                // ── 类 / 接口 ──────────────────────────────────────────────────
                @Override
                public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                    registerType(n, n.isInterface() ? "interface" : "class", pkg, file, symbols);
                    super.visit(n, arg); // 递归处理嵌套类型、方法、字段
                }

                // ── 枚举 ────────────────────────────────────────────────────────
                @Override
                public void visit(EnumDeclaration n, Void arg) {
                    registerType(n, "enum", pkg, file, symbols);
                    super.visit(n, arg);
                }

                // ── 方法 ────────────────────────────────────────────────────────
                @Override
                public void visit(MethodDeclaration n, Void arg) {
                    super.visit(n, arg);
                    Map<String, Object> sym = new LinkedHashMap<>();
                    sym.put("name", n.getNameAsString());
                    sym.put("line", n.getName().getBegin().map(p -> p.line).orElse(-1));
                    sym.put("type", "method");
                    symbols.add(sym);
                }

                // ── 字段（typeMap 用于跨文件类型推断） ──────────────────────────
                @Override
                public void visit(FieldDeclaration n, Void arg) {
                    super.visit(n, arg);
                    for (VariableDeclarator v : n.getVariables()) {
                        typeMap.put(v.getNameAsString(),
                                v.getTypeAsString().replaceAll("<.*", "").trim());
                    }
                }
            }, null);

            symbolIndex.put(file.toString(), symbols);
            typeMapIndex.put(file.toString(), typeMap);
            return true;

        } catch (Exception e) {
            log.debug("[ProjectIndexer] 解析失败：{}，原因：{}", file, e.getMessage());
            return false;
        }
    }

    /**
     * 将类型声明注册到索引并添加到符号列表。
     * 顶层类同时写入 fqnIndex；内部类/嵌套类只写入 classIndex（以便按简单名查找）。
     */
    private void registerType(TypeDeclaration<?> n, String declType,
                               String pkg, Path file,
                               List<Map<String, Object>> symbols) {
        String simpleName = n.getNameAsString();

        // 仅顶层类才注册完全限定名（父节点为 CompilationUnit）
        boolean isTopLevel = n.getParentNode()
                .map(p -> p instanceof CompilationUnit).orElse(false);
        classIndex.putIfAbsent(simpleName, file);
        if (isTopLevel && !pkg.isEmpty()) {
            fqnIndex.put(pkg + "." + simpleName, file);
        }

        Map<String, Object> sym = new LinkedHashMap<>();
        sym.put("name", simpleName);
        sym.put("line", n.getName().getBegin().map(p -> p.line).orElse(-1));
        sym.put("type", declType);
        symbols.add(sym);
    }

    // ── 查询接口 ──────────────────────────────────────────────────────────────

    /**
     * 按简单类名查找文件路径（最常用）。
     * 例：findBySimpleName("EschemaDetailService") → /path/.../EschemaDetailService.java
     */
    public Optional<Path> findBySimpleName(String simpleName) {
        Path p = classIndex.get(simpleName);
        return Optional.ofNullable(p);
    }

    /**
     * 按完全限定名查找文件路径。
     */
    public Optional<Path> findByFqn(String fqn) {
        Path p = fqnIndex.get(fqn);
        return Optional.ofNullable(p);
    }

    /**
     * 获取已缓存的符号列表（避免重复解析）。
     */
    public List<Map<String, Object>> getSymbols(Path file) {
        return symbolIndex.getOrDefault(file.toString(), List.of());
    }

    /**
     * 获取已缓存的字段类型映射（避免重复解析）。
     */
    public Map<String, String> getTypeMap(Path file) {
        return typeMapIndex.getOrDefault(file.toString(), Map.of());
    }

    /**
     * 返回所有已索引的类（简单名 → 路径），用于调试。
     */
    public Map<String, Path> getAllClasses() {
        return Collections.unmodifiableMap(classIndex);
    }

    /**
     * 返回索引状态摘要。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("classCount",   classIndex.size());
        stats.put("fqnCount",     fqnIndex.size());
        stats.put("indexedFiles", symbolIndex.size());
        return stats;
    }

    /** 是否已完成索引 */
    public boolean isIndexed() {
        return !classIndex.isEmpty();
    }
}
