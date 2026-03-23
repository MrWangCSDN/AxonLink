package com.axonlink.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.*;

/**
 * 方法调用关系网扫描器
 *
 * 与 ProjectIndexer 保持一致的大文件策略：
 *   超过 max-file-kb 阈值的文件（生成枚举/错误码/数据类型），
 *   Phase1 只用文件名+路径推断 FQN，登记到 simpleToFqn / fqnToModule（不调 JavaParser）；
 *   Phase2 跳过（生成文件中无业务调用逻辑，无需建 call-edge）。
 *
 * SysUtil 统一处理（单体架构，三个方法等价）：
 *   SysUtil.getInstance(X.class).method()
 *   SysUtil.getRemoteInstance(X.class).method()
 *   SysUtil.getInsance(X.class).method()     （框架 typo，保留兼容）
 */
@Service
public class CallGraphScanner {

    private static final Logger log = LoggerFactory.getLogger(CallGraphScanner.class);

    private final JdbcTemplate jdbc;

    @Value("${callgraph.include-packages:cn.sunline.ltts}")
    private String includePackages;

    @Value("${callgraph.scan-threads:0}")
    private int scanThreads;

    /** 大文件阈值（KB），与 ProjectIndexer 共用配置 */
    @Value("${project.max-file-kb:500}")
    private int maxFileKb;

    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    @Value("${project.source-roots:}")
    private String sourceRoots;

    // ── 阶段间共享的内存索引 ──────────────────────────────────────────────────
    private final Map<String, String>       simpleToFqn    = new ConcurrentHashMap<>(10000);
    private final Map<String, String>       fqnToModule    = new ConcurrentHashMap<>(10000);
    private final Map<String, String>       fqnToLayer     = new ConcurrentHashMap<>(10000);
    private final Map<String, List<String>> interfaceImpls = new ConcurrentHashMap<>(2000);

    // ── 进度跟踪 ──────────────────────────────────────────────────────────────
    private volatile String  phase       = "idle";
    private volatile boolean scanning    = false;
    private final AtomicInteger totalFiles  = new AtomicInteger();
    private final AtomicInteger parsedFiles = new AtomicInteger();
    private final AtomicInteger skippedFiles= new AtomicInteger();
    private final AtomicLong    startMs     = new AtomicLong();

    // JavaParser 每线程独立（与 ProjectIndexer 同模式）
    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() -> {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        return new JavaParser(cfg);
    });

    public CallGraphScanner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private int resolveThreads() {
        return scanThreads > 0 ? scanThreads : Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公开接口
    // ─────────────────────────────────────────────────────────────────────────

    public void startFullScan() {
        if (scanning) { log.warn("[CallGraph] 扫描正在进行中，忽略重复触发"); return; }
        Thread t = new Thread(this::doFullScan, "callgraph-scanner");
        t.setDaemon(true);
        t.start();
    }

    public Map<String, Object> fullScanSync() { return doFullScan(); }

    public Map<String, Object> incrementalRefresh(List<String> changedFilePaths) {
        log.info("[CallGraph] 增量刷新，文件数={}", changedFilePaths.size());
        long t0 = System.currentTimeMillis();
        List<MethodNode> nodes = new ArrayList<>();
        List<CallEdge>   edges = new ArrayList<>();
        int deleted = 0;
        for (String fp : changedFilePaths) {
            Path p = Path.of(fp);
            if (!Files.exists(p) || !fp.endsWith(".java")) continue;
            String simpleName = p.getFileName().toString().replace(".java", "");
            // 删除旧数据
            String fqn = simpleToFqn.getOrDefault(simpleName, "");
            if (!fqn.isEmpty()) {
                deleted += jdbc.update("DELETE FROM cg_call_edge  WHERE caller_sig LIKE ?", fqn + "#%");
                jdbc.update("DELETE FROM cg_method_node WHERE class_fqn = ?", fqn);
            }
            parsePhase1(p);
            parsePhase2(p, nodes, edges);
        }
        batchInsertNodes(nodes);
        batchInsertEdges(edges);
        return Map.of("refreshedFiles", changedFilePaths.size(),
                      "deletedEdges", deleted,
                      "newNodes", nodes.size(), "newEdges", edges.size(),
                      "elapsedMs", System.currentTimeMillis() - t0);
    }

    public Map<String, Object> getProgress() {
        int total = totalFiles.get(), parsed = parsedFiles.get(), skipped = skippedFiles.get();
        int processed = parsed + skipped;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase",       phase);
        m.put("scanning",    scanning);
        m.put("totalFiles",  total);
        m.put("parsedFiles", parsed);
        m.put("skippedFiles",skipped);
        m.put("percent",     total > 0 ? processed * 100 / total : 0);
        m.put("elapsedMs",   scanning ? System.currentTimeMillis() - startMs.get() : 0);
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 核心扫描流程（与 ProjectIndexer.doIndex 结构对齐）
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> doFullScan() {
        scanning = true;
        startMs.set(System.currentTimeMillis());
        totalFiles.set(0); parsedFiles.set(0); skippedFiles.set(0);
        simpleToFqn.clear(); fqnToModule.clear(); fqnToLayer.clear(); interfaceImpls.clear();
        log.info("[CallGraph] ===== 全量扫描开始，大文件阈值={}KB =====", maxFileKb);

        try {
            int threads = resolveThreads();
            ForkJoinPool pool = new ForkJoinPool(threads);

            // ── Step1：发现源码根目录（与 ProjectIndexer 相同逻辑：含 target/gen） ──
            List<Map.Entry<String, List<Path>>> rootFiles = collectRootFiles();
            int total = rootFiles.stream().mapToInt(e -> e.getValue().size()).sum();
            totalFiles.set(total);
            log.info("[CallGraph] 共 {} 个目录，{} 个文件，线程数={}", rootFiles.size(), total, threads);

            if (rootFiles.isEmpty()) {
                log.warn("[CallGraph] 未找到 Java 文件，请检查 project.workspace-roots 配置");
                return summary(0, 0, 0);
            }

            // ── Phase1：收集类声明 + 接口实现关系（每 root 并行） ──────────────────
            phase = "phase1_collect";
            log.info("[CallGraph] Phase1: 收集类声明 + 接口实现关系");
            for (var entry : rootFiles) {
                List<Path> files = entry.getValue();
                long maxBytes = (long) maxFileKb * 1024;
                pool.submit(() ->
                    files.parallelStream().forEach(f -> {
                        if (f.toFile().length() > maxBytes) {
                            lightPhase1(f);               // 大文件：只靠文件名登记 FQN
                            skippedFiles.incrementAndGet();
                        } else {
                            parsePhase1(f);               // 正常文件：AST 解析
                            parsedFiles.incrementAndGet();
                        }
                    })
                ).get();
            }
            log.info("[CallGraph] Phase1 完成：类={}, 接口实现映射={}", simpleToFqn.size(), interfaceImpls.size());

            // 重置进度计数，供 Phase2 展示
            parsedFiles.set(0); skippedFiles.set(0);

            // ── Phase2：扫描方法调用（每 root 并行） ─────────────────────────────
            phase = "phase2_scan";
            log.info("[CallGraph] Phase2: 扫描方法调用");
            List<MethodNode> allNodes = Collections.synchronizedList(new ArrayList<>(50000));
            List<CallEdge>   allEdges = Collections.synchronizedList(new ArrayList<>(300000));
            long maxBytes = (long) maxFileKb * 1024;
            for (var entry : rootFiles) {
                List<Path> files = entry.getValue();
                pool.submit(() ->
                    files.parallelStream().forEach(f -> {
                        if (f.toFile().length() > maxBytes) {
                            skippedFiles.incrementAndGet(); // 大文件跳过 Phase2
                        } else {
                            parsePhase2(f, allNodes, allEdges);
                            parsedFiles.incrementAndGet();
                        }
                    })
                ).get();
            }
            pool.shutdown();
            log.info("[CallGraph] Phase2 完成：方法节点={}, 调用边={}", allNodes.size(), allEdges.size());

            // ── Phase3：清库写入 ──────────────────────────────────────────────
            phase = "phase3_persist";
            log.info("[CallGraph] Phase3: 清库写入");
            jdbc.execute("TRUNCATE TABLE cg_call_edge");
            jdbc.execute("TRUNCATE TABLE cg_method_node");
            jdbc.execute("TRUNCATE TABLE cg_interface_impl");
            batchInsertNodes(allNodes);
            batchInsertEdges(allEdges);
            batchInsertInterfaceImpls();

            phase = "done";
            long elapsed = System.currentTimeMillis() - startMs.get();
            log.info("[CallGraph] ===== 全量扫描完成，耗时 {}ms =====", elapsed);
            return summary(allNodes.size(), allEdges.size(), elapsed);

        } catch (Exception e) {
            log.error("[CallGraph] 扫描异常", e);
            phase = "error";
            return Map.of("error", e.getMessage());
        } finally {
            scanning = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase1：大文件轻量处理（与 ProjectIndexer.lightIndexLargeFile 对齐）
    // 只用文件名+路径推断 FQN，不调 JavaParser
    // ─────────────────────────────────────────────────────────────────────────

    private void lightPhase1(Path file) {
        String filename   = file.getFileName().toString();
        String simpleName = filename.endsWith(".java") ? filename.substring(0, filename.length() - 5) : filename;
        String fqn        = inferFqn(file, simpleName);
        if (fqn == null) fqn = simpleName;
        simpleToFqn.putIfAbsent(simpleName, fqn);
        String module = detectModule(file);
        fqnToModule.putIfAbsent(fqn, module);
        fqnToLayer.putIfAbsent(fqn, detectLayerByPath(file));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase1：正常文件 AST 解析
    // ─────────────────────────────────────────────────────────────────────────

    private void parsePhase1(Path file) {
        try {
            ParseResult<CompilationUnit> result = PARSER.get().parse(Files.readString(file));
            if (!result.isSuccessful() || result.getResult().isEmpty()) return;
            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            if (!isIncluded(pkg)) return;

            String module = detectModule(file);

            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                    String simpleName = n.getNameAsString();
                    String fqn = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
                    simpleToFqn.putIfAbsent(simpleName, fqn);
                    fqnToModule.put(fqn, module);
                    fqnToLayer.put(fqn, detectLayerByDecl(n, file));

                    if (!n.isInterface()) {
                        for (var impl : n.getImplementedTypes()) {
                            String ifaceFqn = resolveToFqn(impl.getNameAsString(), cu);
                            interfaceImpls
                                .computeIfAbsent(ifaceFqn, k -> Collections.synchronizedList(new ArrayList<>()))
                                .add(fqn);
                        }
                    }
                    super.visit(n, arg);
                }
            }, null);
        } catch (Exception e) {
            log.debug("[CallGraph] Phase1 跳过: {} -> {}", file.getFileName(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase2：扫描方法调用
    // ─────────────────────────────────────────────────────────────────────────

    private void parsePhase2(Path file, List<MethodNode> nodes, List<CallEdge> edges) {
        try {
            ParseResult<CompilationUnit> result = PARSER.get().parse(Files.readString(file));
            if (!result.isSuccessful() || result.getResult().isEmpty()) return;
            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            if (!isIncluded(pkg)) return;

            Map<String, String> importMap = buildImportMap(cu);

            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration clazz, Void arg) {
                    String classFqn = pkg.isEmpty() ? clazz.getNameAsString() : pkg + "." + clazz.getNameAsString();
                    for (MethodDeclaration method : clazz.getMethods()) {
                        nodes.add(buildNode(classFqn, clazz, method, file));
                        String callerSig = buildSig(classFqn, method);
                        method.accept(new VoidVisitorAdapter<Void>() {
                            @Override
                            public void visit(MethodCallExpr call, Void a) {
                                super.visit(call, a); // 先递归，保证嵌套（链式/参数）调用都被收集
                                processCallExpr(call, callerSig, cu, importMap, edges);
                            }
                        }, null);
                    }
                    super.visit(clazz, arg);
                }
            }, null);
        } catch (Exception e) {
            log.debug("[CallGraph] Phase2 跳过: {} -> {}", file.getFileName(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 单个 MethodCallExpr 处理
    // ─────────────────────────────────────────────────────────────────────────

    private void processCallExpr(MethodCallExpr call, String callerSig,
                                  CompilationUnit cu, Map<String, String> importMap,
                                  List<CallEdge> edges) {
        Optional<Expression> scopeOpt = call.getScope();
        if (scopeOpt.isEmpty()) return;
        Expression scope  = scopeOpt.get();
        String methodName = call.getNameAsString();
        int    lineNo     = call.getBegin().map(p -> p.line).orElse(-1);

        // ── SysUtil.getInstance/getRemoteInstance/getInsance(X.class).method() ──
        if (isSysUtilGetInstance(scope)) {
            String targetSimple = extractClassArgSimpleName((MethodCallExpr) scope);
            if (targetSimple == null) return;
            String targetFqn = resolveSimpleName(targetSimple, cu, importMap);
            String calleeSig = targetFqn + "#" + methodName + "(*)";
            edges.add(new CallEdge(callerSig, calleeSig, "SYS_UTIL", lineNo));
            // 展开接口 → 实现类
            for (String implFqn : interfaceImpls.getOrDefault(targetFqn, List.of())) {
                edges.add(new CallEdge(callerSig, implFqn + "#" + methodName + "(*)", "IMPL", lineNo));
            }
            return;
        }

        // ── 静态调用：ClassName.method() ──
        if (scope instanceof NameExpr nameExpr) {
            String scopeName = nameExpr.getNameAsString();
            if ("SysUtil".equals(scopeName)) return;
            String targetFqn = resolveSimpleName(scopeName, cu, importMap);
            if (!isIncluded(targetFqn)) return;
            edges.add(new CallEdge(callerSig, targetFqn + "#" + methodName + "(*)", "STATIC", lineNo));
        }
        // 实例调用（变量.method()）类型无法静态推断，暂不建边（避免大量噪声）
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 文件收集（与 ProjectIndexer 完全相同的 root 发现策略）
    // ─────────────────────────────────────────────────────────────────────────

    private List<Map.Entry<String, List<Path>>> collectRootFiles() {
        List<String> allRoots = new ArrayList<>();

        // source-roots 直接指定
        if (sourceRoots != null && !sourceRoots.isBlank()) {
            for (String r : sourceRoots.split(",")) {
                String t = r.trim();
                if (!t.isEmpty()) allRoots.add(t);
            }
        }

        // workspace-roots 自动发现（含 src/main/java + target/gen + target/generated-sources/annotations）
        if (workspaceRoots != null && !workspaceRoots.isBlank()) {
            for (String w : workspaceRoots.split(",")) {
                String ws = w.trim();
                if (ws.isEmpty()) continue;
                Path wsPath = Path.of(ws);
                if (!Files.exists(wsPath)) {
                    log.warn("[CallGraph] workspace 不存在，跳过：{}", ws);
                    continue;
                }
                List<String> discovered = discoverSourceRoots(wsPath);
                log.info("[CallGraph] workspace {} 发现 {} 个源码目录", ws, discovered.size());
                allRoots.addAll(discovered);
            }
        }

        // 去重 → 每个 root 收集 .java 文件列表
        List<Map.Entry<String, List<Path>>> result = new ArrayList<>();
        for (String root : new LinkedHashSet<>(allRoots)) {
            Path rootPath = Path.of(root);
            if (!Files.exists(rootPath)) {
                log.warn("[CallGraph] 目录不存在，跳过：{}", root);
                continue;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(files::add);
            } catch (IOException e) {
                log.error("[CallGraph] 遍历失败：{}", root, e);
            }
            if (!files.isEmpty()) result.add(Map.entry(root, files));
        }
        return result;
    }

    /**
     * 发现 workspace 下所有 src/main/java、target/gen、target/generated-sources/annotations
     * 与 ProjectIndexer.discoverSourceRoots 保持完全一致
     */
    private List<String> discoverSourceRoots(Path workspace) {
        List<String> roots = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(workspace, 8)) {
            walk.filter(Files::isDirectory)
                .filter(p -> {
                    String rel = workspace.relativize(p).toString().replace('\\', '/');
                    return rel.endsWith("src/main/java")
                        || rel.endsWith("target/gen")
                        || rel.endsWith("target/generated-sources/annotations");
                })
                .map(Path::toString)
                .sorted()
                .forEach(roots::add);
        } catch (IOException e) {
            log.error("[CallGraph] workspace 发现失败：{}", workspace, e);
        }
        return roots;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FQN 推断（与 ProjectIndexer.inferFqn 完全一致）
    // ─────────────────────────────────────────────────────────────────────────

    private String inferFqn(Path file, String simpleName) {
        String s = file.toString().replace('\\', '/');
        for (String marker : new String[]{"/src/main/java/", "/target/gen/", "/generated-sources/"}) {
            int idx = s.indexOf(marker);
            if (idx >= 0) {
                String rel = s.substring(idx + marker.length());
                if (rel.endsWith(".java")) return rel.substring(0, rel.length() - 5).replace('/', '.');
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isSysUtilGetInstance(Expression expr) {
        if (!(expr instanceof MethodCallExpr mc)) return false;
        String name = mc.getNameAsString();
        if (!name.equals("getInstance") && !name.equals("getRemoteInstance") && !name.equals("getInsance")) return false;
        return mc.getScope()
                .filter(s -> s instanceof NameExpr ne && ne.getNameAsString().equals("SysUtil"))
                .isPresent();
    }

    private String extractClassArgSimpleName(MethodCallExpr sysUtilCall) {
        if (sysUtilCall.getArguments().isEmpty()) return null;
        Expression arg0 = sysUtilCall.getArguments().get(0);
        if (arg0 instanceof ClassExpr ce) return ce.getTypeAsString().replaceAll("<.*>", "").replaceAll("\\[]", "").trim();
        return null;
    }

    private String resolveSimpleName(String name, CompilationUnit cu, Map<String, String> importMap) {
        if (name.contains(".")) return name;
        String fromImport = importMap.get(name);
        if (fromImport != null) return fromImport;
        String fromIndex = simpleToFqn.get(name);
        if (fromIndex != null) return fromIndex;
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString() + "." + name).orElse(name);
    }

    private String resolveToFqn(String name, CompilationUnit cu) {
        if (name.contains(".")) return name;
        String fromIndex = simpleToFqn.get(name);
        if (fromIndex != null) return fromIndex;
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString() + "." + name).orElse(name);
    }

    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        for (var imp : cu.getImports()) {
            if (!imp.isAsterisk()) {
                String fqn    = imp.getNameAsString();
                String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                map.put(simple, fqn);
            }
        }
        return map;
    }

    private String buildSig(String classFqn, MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(p -> p.getTypeAsString().replaceAll("<.*>", "").replaceAll("\\[]", "").trim())
                .collect(Collectors.joining(","));
        return classFqn + "#" + method.getNameAsString() + "(" + params + ")";
    }

    private String detectModule(Path file) {
        String s = file.toString().replace('\\', '/');
        for (String ws : (workspaceRoots == null ? "" : workspaceRoots).split(",")) {
            String r = ws.trim().replace('\\', '/');
            if (!r.isEmpty() && s.startsWith(r)) {
                String rel = s.substring(r.length()).replaceFirst("^/", "");
                String[] parts = rel.split("/");
                if (parts.length >= 2) return parts[0] + "/" + parts[1];
                if (parts.length == 1) return parts[0];
            }
        }
        return "unknown";
    }

    private String detectLayerByPath(Path file) {
        String p = file.toString().replace('\\', '/');
        if (p.contains("/aps/") || p.contains("-aps/")) return "APS";
        if (p.contains("/bcs/") || p.contains("-bcs/")) return "BCS";
        if (p.contains("/namedsql/") || p.contains("-dao/")) return "DAO";
        if (p.contains("/type/") || p.contains("/pojo/")) return "POJO";
        return "OTHER";
    }

    private String detectLayerByDecl(ClassOrInterfaceDeclaration n, Path file) {
        String name = n.getNameAsString();
        if (name.endsWith("ApsImpl") || name.endsWith("ApsSvtp")) return "APS";
        if (name.endsWith("BcsImpl"))                              return "BCS";
        if (name.endsWith("Dao"))                                  return "DAO";
        return detectLayerByPath(file);
    }

    /**
     * 包名是否在扫描白名单内。
     * 默认边界：cn.sunline.ltts.busi（含所有子包）
     * 超出此范围的类（cn.sunline.aps.*、java.*、org.* 等）一律不建节点和调用边。
     *
     * 注：lightPhase1 中大文件的 FQN 仍会登记到 simpleToFqn，
     * 但在 Phase2 processCallExpr 中，callee 必须经过此检查才写边，
     * 所以框架类即使被 SysUtil 调用也不会出现在 call_edge 中。
     */
    private boolean isIncluded(String pkg) {
        if (pkg == null || pkg.isBlank()) return false;
        for (String prefix : includePackages.split(",")) {
            if (pkg.startsWith(prefix.trim())) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 批量写库
    // ─────────────────────────────────────────────────────────────────────────

    private static final int BATCH_SIZE = 2000;

    private void batchInsertNodes(List<MethodNode> nodes) {
        String sql = """
            INSERT IGNORE INTO cg_method_node
              (id, signature, module, class_fqn, class_name, class_type, layer,
               method_name, param_types, return_type, modifiers, file_path, line_no, create_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        flushBatch(sql, nodes, BATCH_SIZE, (n, id) -> new Object[]{
            id, n.signature, n.module, n.classFqn, n.className, n.classType, n.layer,
            n.methodName, n.paramTypes, n.returnType, n.modifiers, n.filePath, n.lineNo, LocalDateTime.now()
        });
        log.info("[CallGraph] 写入 method_node: {} 行", nodes.size());
    }

    private void batchInsertEdges(List<CallEdge> edges) {
        String sql = "INSERT IGNORE INTO cg_call_edge (id,caller_sig,callee_sig,call_type,line_no,create_time) VALUES (?,?,?,?,?,?)";
        List<CallEdge> filtered = edges.stream().filter(e -> !e.callerSig.equals(e.calleeSig)).toList();
        flushBatch(sql, filtered, BATCH_SIZE, (e, id) -> new Object[]{
            id, e.callerSig, e.calleeSig, e.callType, e.lineNo, LocalDateTime.now()
        });
        log.info("[CallGraph] 写入 call_edge: {} 行", filtered.size());
    }

    private void batchInsertInterfaceImpls() {
        String sql = "INSERT IGNORE INTO cg_interface_impl (interface_fqn, impl_fqn, create_time) VALUES (?,?,?)";
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        for (var e : interfaceImpls.entrySet()) {
            for (String impl : e.getValue()) {
                batch.add(new Object[]{ e.getKey(), impl, LocalDateTime.now() });
                if (batch.size() == BATCH_SIZE) { jdbc.batchUpdate(sql, batch); batch.clear(); }
            }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);
        log.info("[CallGraph] 写入 interface_impl: {} 行",
                interfaceImpls.values().stream().mapToInt(List::size).sum());
    }

    @FunctionalInterface
    interface RowMapper<T> { Object[] map(T t, long id); }

    private <T> void flushBatch(String sql, List<T> items, int batchSize, RowMapper<T> mapper) {
        List<Object[]> batch = new ArrayList<>(batchSize);
        long id = System.currentTimeMillis() * 100000L + ThreadLocalRandom.current().nextInt(100000);
        for (T item : items) {
            batch.add(mapper.map(item, id++));
            if (batch.size() == batchSize) { jdbc.batchUpdate(sql, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MethodNode 构建
    // ─────────────────────────────────────────────────────────────────────────

    private MethodNode buildNode(String classFqn, ClassOrInterfaceDeclaration clazz,
                                  MethodDeclaration method, Path file) {
        MethodNode n = new MethodNode();
        n.classFqn   = classFqn;
        n.className  = clazz.getNameAsString();
        n.classType  = clazz.isInterface() ? "INTERFACE" : "CLASS";
        n.layer      = fqnToLayer.getOrDefault(classFqn, detectLayerByDecl(clazz, file));
        n.module     = fqnToModule.getOrDefault(classFqn, detectModule(file));
        n.methodName = method.getNameAsString();
        n.paramTypes = method.getParameters().stream()
                .map(p -> p.getTypeAsString().replaceAll("<.*>", "").replaceAll("\\[]", "").trim())
                .collect(Collectors.joining(","));
        n.returnType = method.getTypeAsString().replaceAll("<.*>", "").trim();
        n.modifiers  = method.getModifiers().stream()
                .map(m -> m.getKeyword().asString()).collect(Collectors.joining(","));
        n.filePath   = file.toString();
        n.lineNo     = method.getBegin().map(p -> p.line).orElse(-1);
        n.signature  = buildSig(classFqn, method);
        return n;
    }

    private Map<String, Object> summary(int nodes, int edges, long ms) {
        return Map.of("methodNodes", nodes, "callEdges", edges, "elapsedMs", ms);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部 DTO
    // ─────────────────────────────────────────────────────────────────────────

    static class MethodNode {
        String signature, module, classFqn, className, classType, layer;
        String methodName, paramTypes, returnType, modifiers, filePath;
        int lineNo;
    }

    static class CallEdge {
        final String callerSig, calleeSig, callType;
        final int lineNo;
        CallEdge(String a, String b, String t, int l) {
            callerSig = a; calleeSig = b; callType = t; lineNo = l;
        }
    }
}
