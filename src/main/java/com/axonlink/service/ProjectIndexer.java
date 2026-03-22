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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 项目源码全量索引器
 *
 * 支持两种配置方式：
 *   project.source-roots    : 直接指定 src/main/java 或 target/gen 目录（逗号分隔）
 *   project.workspace-roots : 指定工作区根目录，自动发现所有子模块的
 *                             src/main/java 和 target/gen 目录
 *
 * 同名类冲突策略：
 *   classIndex 使用 putIfAbsent（简单名首次写入优先）
 *   fqnIndex   使用 put（全限定名全局唯一，精确匹配）
 *   → NODE_FILE_REGISTRY 中对于有冲突的类应使用 fqn 字段代替 className，
 *     后端通过 /api/source/find?fqn=... 精确命中，不会跳转错误。
 *
 * 大文件策略：
 *   超过 max-file-kb 阈值的文件只登记类名路径，跳过 AST 解析，
 *   避免 JavaParser 对超大枚举/常量文件产生数百 MB 的 AST 峰值内存。
 */
@Component
public class ProjectIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndexer.class);

    /** 直接指定源码根目录列表（逗号分隔） */
    @Value("${project.source-roots:}")
    private String sourceRootsConfig;

    /**
     * 工作区根目录列表（逗号分隔）。
     * 自动发现其下所有子模块的 src/main/java 和 target/gen 目录（最多深入 6 层）。
     * 例：D:/code/sunline → 自动找到 dept-parent/dept-aps/src/main/java 等所有子目录
     */
    @Value("${project.workspace-roots:}")
    private String workspaceRootsConfig;

    /** 并行索引线程数（0=自动） */
    @Value("${project.index-threads:0}")
    private int indexThreads;

    /** 超过此大小（KB）的文件跳过 AST 深度解析，只登记类名路径 */
    @Value("${project.max-file-kb:500}")
    private int maxFileKb;

    // ── 四张索引表 ─────────────────────────────────────────────────────────────
    /** 简单类名 → 文件路径（同名取首次，putIfAbsent） */
    private final Map<String, Path> classIndex   = new ConcurrentHashMap<>();
    /** 全限定名 → 文件路径（唯一，put 覆盖） */
    private final Map<String, Path> fqnIndex     = new ConcurrentHashMap<>();
    /** 文件路径 → 符号列表（方法 + 类型声明） */
    private final Map<String, List<Map<String, Object>>> symbolIndex  = new ConcurrentHashMap<>();
    /** 文件路径 → 字段类型映射 */
    private final Map<String, Map<String, String>>       typeMapIndex = new ConcurrentHashMap<>();

    // ── 进度跟踪 ───────────────────────────────────────────────────────────────
    private final AtomicInteger progressTotal   = new AtomicInteger(0);
    private final AtomicInteger progressParsed  = new AtomicInteger(0);
    private final AtomicInteger progressSkipped = new AtomicInteger(0);
    private final AtomicLong    progressBytes   = new AtomicLong(0);
    private volatile boolean    indexing        = false;
    private volatile String     indexPhase      = "idle";
    private volatile long       indexStartMs    = 0;
    private volatile long       indexElapsedMs  = 0;
    private volatile String     indexError      = null;

    // JavaParser 非线程安全，每线程独立实例
    private static final ThreadLocal<JavaParser> PARSER_LOCAL = ThreadLocal.withInitial(() -> {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        return new JavaParser(cfg);
    });

    private int resolveThreads() {
        if (indexThreads > 0) return indexThreads;
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    // ── 启动时异步建索引（不阻塞 Spring 启动） ───────────────────────────────────
    @PostConstruct
    public void buildIndex() {
        boolean hasSource    = sourceRootsConfig    != null && !sourceRootsConfig.isBlank();
        boolean hasWorkspace = workspaceRootsConfig != null && !workspaceRootsConfig.isBlank();
        if (!hasSource && !hasWorkspace) {
            log.info("[ProjectIndexer] 未配置 project.source-roots / project.workspace-roots，跳过全量索引");
            indexPhase = "idle";
            return;
        }
        Thread t = new Thread(() -> doIndex(false), "project-indexer");
        t.setDaemon(true);
        t.start();
    }

    /** 热重载：编译后主动调用，清空旧索引重新扫描 */
    public Map<String, Object> refresh() {
        log.info("[ProjectIndexer] 触发热重载，清空旧索引…");
        classIndex.clear(); fqnIndex.clear();
        symbolIndex.clear(); typeMapIndex.clear();
        progressTotal.set(0); progressParsed.set(0);
        progressSkipped.set(0); progressBytes.set(0);
        return doIndex(true);
    }

    // ── 核心索引逻辑 ───────────────────────────────────────────────────────────

    private Map<String, Object> doIndex(boolean isRefresh) {
        indexing = true; indexPhase = "collecting"; indexError = null;
        indexStartMs = System.currentTimeMillis();
        progressTotal.set(0); progressParsed.set(0);
        progressSkipped.set(0); progressBytes.set(0);

        int threads = resolveThreads();
        log.info("[ProjectIndexer] 启动索引，线程数={} 大文件阈值={}KB", threads, maxFileKb);

        // ── Step 1：收集所有待索引目录 ────────────────────────────────────────
        List<String> allRoots = new ArrayList<>();

        // 1a. 直接指定的 source-roots
        if (sourceRootsConfig != null && !sourceRootsConfig.isBlank()) {
            for (String r : sourceRootsConfig.split(",")) {
                String root = r.trim();
                if (!root.isEmpty()) allRoots.add(root);
            }
        }

        // 1b. workspace-roots：自动发现 src/main/java 和 target/gen
        if (workspaceRootsConfig != null && !workspaceRootsConfig.isBlank()) {
            for (String w : workspaceRootsConfig.split(",")) {
                String wsRoot = w.trim();
                if (wsRoot.isEmpty()) continue;
                Path wsPath = Path.of(wsRoot);
                if (!Files.exists(wsPath)) {
                    log.warn("[ProjectIndexer] workspace 目录不存在，跳过：{}", wsRoot);
                    continue;
                }
                List<String> discovered = discoverSourceRoots(wsPath);
                log.info("[ProjectIndexer] workspace {} 发现 {} 个源码目录", wsRoot, discovered.size());
                allRoots.addAll(discovered);
            }
        }

        // ── Step 2：去重并收集 .java 文件 ────────────────────────────────────
        Set<String> dedupedRoots = new LinkedHashSet<>(allRoots);
        List<Map.Entry<String, List<Path>>> rootFiles = new ArrayList<>();
        int totalFiles = 0;

        for (String root : dedupedRoots) {
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
            if (!files.isEmpty()) {
                rootFiles.add(Map.entry(root, files));
                totalFiles += files.size();
            }
        }

        progressTotal.set(totalFiles);
        indexPhase = "parsing";
        log.info("[ProjectIndexer] 收集完成，共 {} 个源码目录、{} 个文件，开始并行解析", dedupedRoots.size(), totalFiles);

        // ── Step 3：并行解析 ──────────────────────────────────────────────────
        List<Map<String, Object>> rootStats = new ArrayList<>();
        AtomicInteger totalScanned = new AtomicInteger();
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            for (Map.Entry<String, List<Path>> entry : rootFiles) {
                String root      = entry.getKey();
                List<Path> files = entry.getValue();
                long rootStart   = System.currentTimeMillis();
                AtomicInteger rootParsed  = new AtomicInteger();
                AtomicInteger rootSkipped = new AtomicInteger();

                pool.submit(() ->
                    files.parallelStream().forEach(p -> {
                        totalScanned.incrementAndGet();
                        boolean isLarge = p.toFile().length() > (long) maxFileKb * 1024;
                        if (isLarge) {
                            lightIndexLargeFile(p);
                            progressSkipped.incrementAndGet();
                            rootSkipped.incrementAndGet();
                        } else {
                            if (indexFile(p)) {
                                progressParsed.incrementAndGet();
                                rootParsed.incrementAndGet();
                            }
                        }
                        progressBytes.addAndGet(p.toFile().length());
                    })
                ).get();

                long elapsed = System.currentTimeMillis() - rootStart;
                log.info("[ProjectIndexer] ✓ {}解析+{}大文件 | {}ms | {}", rootParsed.get(), rootSkipped.get(), elapsed, root);
                rootStats.add(Map.of("root", root, "parsed", rootParsed.get(), "skipped", rootSkipped.get(), "ms", elapsed));
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("[ProjectIndexer] 并行索引异常", e);
            indexError = e.getMessage();
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }

        indexElapsedMs = System.currentTimeMillis() - indexStartMs;
        indexPhase     = "done";
        indexing       = false;

        log.info("[ProjectIndexer] 全部完成：{}深度+{}大文件 / {}类 / {}ms（{}线程）",
                progressParsed.get(), progressSkipped.get(), classIndex.size(), indexElapsedMs, threads);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threads",    threads);
        result.put("scanned",    totalScanned.get());
        result.put("parsed",     progressParsed.get());
        result.put("skipped",    progressSkipped.get());
        result.put("classCount", classIndex.size());
        result.put("fqnCount",   fqnIndex.size());
        result.put("elapsedMs",  indexElapsedMs);
        result.put("roots",      rootStats);
        return result;
    }

    /**
     * 自动发现工作区下所有子模块的 src/main/java 和 target/gen 目录。
     * 最多深入 6 层，找到后不再继续向下（避免递归到 src/main/java/com/... 内部）。
     */
    private List<String> discoverSourceRoots(Path workspace) {
        List<String> roots = new ArrayList<>();
        Set<String> TARGETS = Set.of("src/main/java", "src\\main\\java",
                "target/gen", "target\\gen");
        try (Stream<Path> walk = Files.walk(workspace, 8)) {
            walk.filter(Files::isDirectory)
                .filter(p -> {
                    String rel = workspace.relativize(p).toString().replace('\\', '/');
                    return rel.endsWith("src/main/java") || rel.endsWith("target/gen");
                })
                .map(Path::toString)
                .sorted()
                .forEach(roots::add);
        } catch (IOException e) {
            log.error("[ProjectIndexer] workspace 发现失败：{}", workspace, e);
        }
        return roots;
    }

    /** 大文件轻量处理：只登记类名/FQN → 路径，跳过符号解析 */
    private void lightIndexLargeFile(Path file) {
        String filename   = file.getFileName().toString();
        String simpleName = filename.endsWith(".java") ? filename.substring(0, filename.length() - 5) : filename;
        classIndex.putIfAbsent(simpleName, file);
        String fqn = inferFqn(file, simpleName);
        if (fqn != null) fqnIndex.put(fqn, file);
        log.debug("[ProjectIndexer] 大文件轻量：{}", simpleName);
    }

    /** 标准 AST 解析并写入全部四张索引 */
    private boolean indexFile(Path file) {
        try {
            String source = Files.readString(file);
            ParseResult<CompilationUnit> result = PARSER_LOCAL.get().parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return false;

            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString()).orElse("");

            List<Map<String, Object>> symbols = new ArrayList<>();
            Map<String, String>       typeMap = new LinkedHashMap<>();

            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override public void visit(ClassOrInterfaceDeclaration n, Void a) {
                    registerType(n, n.isInterface() ? "interface" : "class", pkg, file, symbols); super.visit(n, a);
                }
                @Override public void visit(EnumDeclaration n, Void a) {
                    registerType(n, "enum", pkg, file, symbols); super.visit(n, a);
                }
                @Override public void visit(MethodDeclaration n, Void a) {
                    super.visit(n, a);
                    Map<String, Object> sym = new LinkedHashMap<>();
                    sym.put("name", n.getNameAsString());
                    sym.put("line", n.getName().getBegin().map(p -> p.line).orElse(-1));
                    sym.put("type", "method");
                    symbols.add(sym);
                }
                @Override public void visit(FieldDeclaration n, Void a) {
                    super.visit(n, a);
                    for (VariableDeclarator v : n.getVariables())
                        typeMap.put(v.getNameAsString(), v.getTypeAsString().replaceAll("<.*", "").trim());
                }
            }, null);

            symbolIndex.put(file.toString(), symbols);
            typeMapIndex.put(file.toString(), typeMap);
            return true;
        } catch (Exception e) {
            log.debug("[ProjectIndexer] 解析失败：{}，{}", file, e.getMessage());
            return false;
        }
    }

    private void registerType(TypeDeclaration<?> n, String declType,
                               String pkg, Path file, List<Map<String, Object>> symbols) {
        String simpleName  = n.getNameAsString();
        boolean isTopLevel = n.getParentNode().map(p -> p instanceof CompilationUnit).orElse(false);
        classIndex.putIfAbsent(simpleName, file);
        if (isTopLevel && !pkg.isEmpty()) fqnIndex.put(pkg + "." + simpleName, file);

        Map<String, Object> sym = new LinkedHashMap<>();
        sym.put("name", simpleName);
        sym.put("line", n.getName().getBegin().map(p -> p.line).orElse(-1));
        sym.put("type", declType);
        symbols.add(sym);
    }

    /** 从文件路径推断 FQN（Maven 标准布局） */
    private String inferFqn(Path file, String simpleName) {
        String s = file.toString().replace('\\', '/');
        for (String marker : new String[]{"/src/main/java/", "/target/gen/", "/generated-sources/"}) {
            int idx = s.indexOf(marker);
            if (idx >= 0) {
                String rel = s.substring(idx + marker.length());
                if (rel.endsWith(".java"))
                    return rel.substring(0, rel.length() - 5).replace('/', '.');
            }
        }
        return null;
    }

    // ── 查询接口 ──────────────────────────────────────────────────────────────

    /** 简单类名查找（存在同名冲突时返回首次索引的文件） */
    public Optional<Path> findBySimpleName(String simpleName) {
        return Optional.ofNullable(classIndex.get(simpleName));
    }

    /** FQN 精确查找（唯一，不会冲突）*/
    public Optional<Path> findByFqn(String fqn) {
        return Optional.ofNullable(fqnIndex.get(fqn));
    }

    public List<Map<String, Object>> getSymbols(Path file) {
        return symbolIndex.getOrDefault(file.toString(), List.of());
    }

    public Map<String, String> getTypeMap(Path file) {
        return typeMapIndex.getOrDefault(file.toString(), Map.of());
    }

    public Map<String, Path> getAllClasses() {
        return Collections.unmodifiableMap(classIndex);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("classCount",   classIndex.size());
        s.put("fqnCount",     fqnIndex.size());
        s.put("indexedFiles", symbolIndex.size());
        return s;
    }

    /** 实时进度（供前端轮询） */
    public Map<String, Object> getIndexProgress() {
        int total     = progressTotal.get();
        int parsed    = progressParsed.get();
        int skipped   = progressSkipped.get();
        int processed = parsed + skipped;
        long elapsed  = indexing ? System.currentTimeMillis() - indexStartMs : indexElapsedMs;
        int pct       = total > 0 ? (int)(processed * 100L / total) : (indexPhase.equals("done") ? 100 : 0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase",      indexPhase);
        m.put("indexing",   indexing);
        m.put("total",      total);
        m.put("parsed",     parsed);
        m.put("skipped",    skipped);
        m.put("processed",  processed);
        m.put("percent",    pct);
        m.put("classCount", classIndex.size());
        m.put("fqnCount",   fqnIndex.size());
        m.put("elapsedMs",  elapsed);
        m.put("maxFileKb",  maxFileKb);
        if (indexError != null) m.put("error", indexError);
        return m;
    }

    public boolean isIndexed() {
        return !classIndex.isEmpty();
    }
}
