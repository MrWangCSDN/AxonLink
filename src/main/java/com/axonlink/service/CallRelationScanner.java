package com.axonlink.service;

import com.axonlink.common.DomainKeyResolver;
import com.axonlink.dto.NodeCacheEntry;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 调用关系扫描器（JavaParser AST）。
 *
 * <p>扫描 workspace-roots 下所有 Java 文件，提取 {@code SysUtil.getInstance(X.class).method()}
 * 和 {@code KxxxDao.method()} 调用，通过向上冒泡归集（最多5层）匹配
 * {@link ServiceNodeCache#containsTypeId} 确定 caller 身份，构建调用关系写入 call_relation 表。
 */
@Service
public class CallRelationScanner {

    private static final Logger log = LoggerFactory.getLogger(CallRelationScanner.class);

    private static final int MAX_BUBBLE_DEPTH = 5;
    private static final Pattern PAT_DAO_CALL = Pattern.compile("(K\\w+Dao)\\.(\\w+)\\s*\\(");
    private static final Set<String> IMPL_SUFFIXES = Set.of(
            "PcsImpl", "PbsImpl", "PbcbImpl", "PbcpImpl", "PbccImpl", "PbctImpl");

    private final JdbcTemplate       jdbcTemplate;
    private final ServiceNodeCache   serviceNodeCache;

    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    @Value("${project.source-roots:}")
    private String sourceRoots;

    @Value("${callgraph.include-packages:cn.sunline.ltts.busi}")
    private String includePackages;

    // JavaParser 每线程独立
    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() -> {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        return new JavaParser(cfg);
    });

    // 文件索引：简单类名 → Path
    private final Map<String, Path> fileIndex = new ConcurrentHashMap<>(10000);

    // 进度跟踪
    private volatile String  phase       = "idle";
    private volatile boolean scanning    = false;
    private final AtomicInteger totalFiles  = new AtomicInteger();
    private final AtomicInteger parsedFiles = new AtomicInteger();
    private volatile long startMs = 0;

    public CallRelationScanner(JdbcTemplate jdbcTemplate, ServiceNodeCache serviceNodeCache) {
        this.jdbcTemplate     = jdbcTemplate;
        this.serviceNodeCache = serviceNodeCache;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公开接口
    // ─────────────────────────────────────────────────────────────────────────

    public void startFullScan() {
        if (scanning) { log.warn("[CallRelation] 扫描正在进行中，忽略重复触发"); return; }
        Thread t = new Thread(this::doFullScan, "call-relation-scanner");
        t.setDaemon(true);
        t.start();
    }

    public Map<String, Object> fullScanSync() { return doFullScan(); }

    public Map<String, Object> getProgress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase",       phase);
        m.put("scanning",    scanning);
        m.put("totalFiles",  totalFiles.get());
        m.put("parsedFiles", parsedFiles.get());
        m.put("elapsedMs",   scanning ? System.currentTimeMillis() - startMs : 0);
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 核心扫描流程
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> doFullScan() {
        scanning = true;
        startMs = System.currentTimeMillis();
        totalFiles.set(0);
        parsedFiles.set(0);
        log.info("[CallRelation] ===== 全量扫描开始 =====");

        try {
            // Phase 0: 构建文件索引
            phase = "build_index";
            buildFileIndex();
            log.info("[CallRelation] 文件索引：{} 个 Java 文件", fileIndex.size());

            // Phase 1: 快速过滤含 SysUtil.getInstance / KxxxDao 的文件
            phase = "grep_files";
            List<Path> targetFiles = grepTargetFiles();
            totalFiles.set(targetFiles.size());
            log.info("[CallRelation] 匹配文件数：{}", targetFiles.size());

            if (targetFiles.isEmpty()) {
                log.warn("[CallRelation] 未找到含 SysUtil.getInstance / KxxxDao 的文件");
                phase = "done";
                return summary(0, 0);
            }

            // Phase 2: AST 解析 + 向上冒泡 + 提取调用关系
            phase = "scan";
            List<Map<String, Object>> allEdges = Collections.synchronizedList(new ArrayList<>());
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            ForkJoinPool pool = new ForkJoinPool(threads);
            try {
                pool.submit(() ->
                    targetFiles.parallelStream().forEach(f -> {
                        List<Map<String, Object>> edges = scanFile(f);
                        allEdges.addAll(edges);
                        parsedFiles.incrementAndGet();
                    })
                ).get();
            } catch (Exception e) {
                log.error("[CallRelation] 扫描线程异常", e);
            } finally {
                pool.shutdown();
            }
            log.info("[CallRelation] 扫描完成，共 {} 条边", allEdges.size());

            // Phase 3: 清空旧数据，批量写入
            phase = "persist";
            jdbcTemplate.execute("TRUNCATE TABLE call_relation");
            batchInsert(allEdges);
            log.info("[CallRelation] 写入 call_relation 表完成");

            phase = "done";
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("[CallRelation] ===== 全量扫描完成，耗时 {}ms =====", elapsed);
            return summary(allEdges.size(), elapsed);

        } catch (Exception e) {
            log.error("[CallRelation] 扫描异常", e);
            phase = "error";
            return Map.of("error", e.getMessage());
        } finally {
            scanning = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 文件索引
    // ─────────────────────────────────────────────────────────────────────────

    private void buildFileIndex() {
        fileIndex.clear();
        for (String root : collectSourceRoots()) {
            Path rootPath = Path.of(root);
            if (!Files.exists(rootPath)) continue;
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/test/"))
                    .forEach(p -> {
                        String name = p.getFileName().toString().replace(".java", "");
                        fileIndex.putIfAbsent(name, p);
                    });
            } catch (IOException e) {
                log.warn("[CallRelation] 遍历失败: {}", root);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 快速过滤
    // ─────────────────────────────────────────────────────────────────────────

    private List<Path> grepTargetFiles() {
        List<Path> result = new ArrayList<>();
        for (Path p : fileIndex.values()) {
            try {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                if (content.contains("SysUtil.getInstance") || PAT_DAO_CALL.matcher(content).find()) {
                    result.add(p);
                }
            } catch (IOException ignored) {}
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 单文件扫描：AST 解析 + 向上冒泡 + 提取调用
    // ─────────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> scanFile(Path file) {
        List<Map<String, Object>> edges = new ArrayList<>();
        try {
            if (file.toFile().length() > 500 * 1024L) return edges; // 跳过大文件

            ParseResult<CompilationUnit> result = PARSER.get().parse(Files.readString(file));
            if (!result.isSuccessful() || result.getResult().isEmpty()) return edges;
            CompilationUnit cu = result.getResult().get();

            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

            // 构建 import map
            Map<String, String> importMap = buildImportMap(cu);

            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration clazz, Void arg) {
                    if (clazz.isInterface()) { super.visit(clazz, arg); return; }

                    // 推导 callerId
                    String className = clazz.getNameAsString();
                    String callerId = deriveCallerId(className);

                    // 检查 callerId 是否在 longnameMap（service/component 注册表）
                    String resolvedCallerId = null;
                    if (serviceNodeCache.containsTypeId(callerId)) {
                        resolvedCallerId = callerId;
                    } else {
                        // 向上冒泡：查该类实现的接口
                        resolvedCallerId = bubbleUp(clazz, 0);
                    }

                    if (resolvedCallerId == null) {
                        super.visit(clazz, arg);
                        return; // 无法确定 caller 身份，跳过
                    }

                    // 从 nodeMap 获取 caller 信息
                    String callerType = null;
                    String callerDomain = null;
                    String callerLongname = null;
                    String callerServiceId = null;

                    // nodeMap 的 key 是 typeId.serviceId，需要遍历前缀匹配
                    Optional<NodeCacheEntry> callerEntry = findNodeByTypeId(resolvedCallerId);
                    if (callerEntry.isPresent()) {
                        NodeCacheEntry ce = callerEntry.get();
                        callerType      = ce.getNodeKind();
                        callerDomain    = ce.getDomainKey();
                        callerLongname  = ce.getServiceLongname();
                        callerServiceId = ce.getServiceName();
                    } else {
                        // 从类名后缀推断类型
                        callerType = inferTypeFromClassName(className);
                        callerDomain = DomainKeyResolver.resolve(pkg);
                    }

                    String fromJar = detectModule(file);
                    String finalCallerId = resolvedCallerId;
                    String finalCallerType = callerType != null ? callerType : "unknown";
                    String finalCallerDomain = callerDomain;
                    String finalCallerLongname = callerLongname;
                    String finalCallerServiceId = callerServiceId;
                    String finalFromJar = fromJar;

                    // 遍历每个方法，提取调用
                    for (MethodDeclaration method : clazz.getMethods()) {
                        String methodName = method.getNameAsString();
                        method.accept(new VoidVisitorAdapter<Void>() {
                            @Override
                            public void visit(MethodCallExpr call, Void a) {
                                super.visit(call, a);
                                processCall(call, finalCallerId, finalCallerType, methodName,
                                           finalCallerLongname, finalCallerDomain,
                                           finalCallerServiceId, finalFromJar,
                                           cu, importMap, edges);
                            }
                        }, null);
                    }

                    super.visit(clazz, arg);
                }
            }, null);
        } catch (Exception e) {
            log.debug("[CallRelation] 扫描跳过 {}: {}", file.getFileName(), e.getMessage());
        }
        return edges;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 向上冒泡归集：查 implements 接口是否在 longnameMap
    // ─────────────────────────────────────────────────────────────────────────

    private String bubbleUp(ClassOrInterfaceDeclaration clazz, int depth) {
        if (depth >= MAX_BUBBLE_DEPTH) return null;

        // 检查该类实现的所有接口
        for (ClassOrInterfaceType iface : clazz.getImplementedTypes()) {
            String ifaceName = iface.getNameAsString();
            if (serviceNodeCache.containsTypeId(ifaceName)) {
                return ifaceName;
            }
        }

        // 检查父类
        for (ClassOrInterfaceType ext : clazz.getExtendedTypes()) {
            String parentName = ext.getNameAsString();
            // 从 fileIndex 找到父类文件，解析其接口
            Path parentFile = fileIndex.get(parentName);
            if (parentFile != null) {
                try {
                    ParseResult<CompilationUnit> pr = PARSER.get().parse(Files.readString(parentFile));
                    if (pr.isSuccessful() && pr.getResult().isPresent()) {
                        for (var type : pr.getResult().get().findAll(ClassOrInterfaceDeclaration.class)) {
                            if (type.getNameAsString().equals(parentName)) {
                                String found = bubbleUp(type, depth + 1);
                                if (found != null) return found;
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 处理单个 MethodCallExpr
    // ─────────────────────────────────────────────────────────────────────────

    private void processCall(MethodCallExpr call,
                             String callerId, String callerType, String callerMethod,
                             String callerLongname, String callerDomain, String callerServiceId,
                             String fromJar, CompilationUnit cu, Map<String, String> importMap,
                             List<Map<String, Object>> edges) {

        Optional<Expression> scopeOpt = call.getScope();
        if (scopeOpt.isEmpty()) return;
        Expression scope = scopeOpt.get();
        String methodName = call.getNameAsString();

        // ── SysUtil.getInstance(X.class).method() ──
        if (isSysUtilGetInstance(scope)) {
            String targetClass = extractClassArgSimpleName((MethodCallExpr) scope);
            if (targetClass == null) return;

            // 查 nodeMap
            Optional<NodeCacheEntry> calleeEntry = findNodeByTypeId(targetClass);
            if (calleeEntry.isEmpty()) return; // 未注册的跳过

            NodeCacheEntry ce = calleeEntry.get();
            Map<String, Object> edge = buildEdge(
                callerId, callerType, callerMethod, callerLongname, callerDomain, callerServiceId,
                targetClass, ce.getNodeKind(), methodName, ce.getServiceLongname(),
                ce.getDomainKey(), targetClass, ce.getServiceName(), ce.getServiceName(),
                fromJar, true, false
            );
            if (edge != null) edges.add(edge);
            return;
        }

        // ── KxxxDao.method() ──
        if (scope instanceof NameExpr nameExpr) {
            String scopeName = nameExpr.getNameAsString();
            if (scopeName.matches("K\\w+Dao")) {
                Map<String, Object> edge = buildEdge(
                    callerId, callerType, callerMethod, callerLongname, callerDomain, callerServiceId,
                    scopeName, "bcc", methodName, null, null, scopeName, null, null,
                    fromJar, true, true
                );
                if (edge != null) edges.add(edge);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 构建 call_relation 边
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildEdge(
            String callerId, String callerType, String callerMethod,
            String callerLongname, String callerDomain, String callerServiceId,
            String calleeId, String calleeType, String calleeMethod,
            String calleeLongname, String calleeDomain, String calleeClass,
            String calleeServiceId, String calleeServiceName,
            String fromJar, boolean isDirect, boolean isDao) {

        // 跨域检测
        boolean crossDomain = callerDomain != null && calleeDomain != null
                && !callerDomain.equals(calleeDomain);

        // 分层违规检测
        String violation = checkRuleViolation(callerType, calleeType, callerDomain, calleeDomain);

        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("caller_id",         callerId);
        edge.put("caller_type",       callerType);
        edge.put("caller_method",     callerMethod);
        edge.put("caller_longname",   callerLongname);
        edge.put("caller_domain",     callerDomain);
        edge.put("caller_service_id", callerServiceId);

        edge.put("callee_id",           calleeId);
        edge.put("callee_type",         calleeType);
        edge.put("callee_method",       calleeMethod);
        edge.put("callee_longname",     calleeLongname);
        edge.put("callee_domain",       calleeDomain);
        edge.put("callee_class",        calleeClass);
        edge.put("callee_service_id",   calleeServiceId);
        edge.put("callee_service_name", calleeServiceName);

        edge.put("from_jar",       fromJar);
        edge.put("is_direct",      isDirect ? 1 : 0);
        edge.put("cross_domain",   crossDomain ? 1 : 0);
        edge.put("rule_violation",  violation != null ? 1 : 0);
        edge.put("violation_desc",  violation);

        return edge;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 分层违规检查
    // ─────────────────────────────────────────────────────────────────────────

    private String checkRuleViolation(String callerType, String calleeType,
                                      String callerDomain, String calleeDomain) {
        if (callerType == null || calleeType == null) return null;

        // 同层互调违规
        if (callerType.equals(calleeType)) {
            if (Set.of("pbs", "pcs", "pbcc", "pbct", "pbcb", "pbcp").contains(callerType)) {
                return callerType + " 同层互调 " + calleeType;
            }
        }

        // pbs → pbcb/pbcp 必须同域
        if ("pbs".equals(callerType) && Set.of("pbcb", "pbcp").contains(calleeType)) {
            if (callerDomain != null && calleeDomain != null && !callerDomain.equals(calleeDomain)) {
                return "pbs→" + calleeType + " 跨域（" + callerDomain + "→" + calleeDomain + "）";
            }
        }

        // 非构件层调用 bcc(DAO) 违规
        if ("bcc".equals(calleeType)) {
            if (!Set.of("pbcb", "pbcp", "pbcc", "pbct").contains(callerType)) {
                return callerType + " 直接调用 DAO（仅构件层允许）";
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
        if (arg0 instanceof ClassExpr ce) {
            return ce.getTypeAsString().replaceAll("<.*>", "").replaceAll("\\[]", "").trim();
        }
        return null;
    }

    /** 从 nodeMap 中按 typeId 前缀查找第一个匹配的 NodeCacheEntry */
    private Optional<NodeCacheEntry> findNodeByTypeId(String typeId) {
        return serviceNodeCache.findByTypeId(typeId);
    }

    /** 推导 callerId：去掉 Impl 后缀 */
    private String deriveCallerId(String className) {
        for (String suffix : IMPL_SUFFIXES) {
            if (className.endsWith(suffix)) {
                return className.substring(0, className.length() - 4); // 去 "Impl" 4字符
            }
        }
        return className;
    }

    /** 从类名后缀推断类型 */
    private String inferTypeFromClassName(String className) {
        if (className.endsWith("PcsImpl") || className.endsWith("Pcs")) return "pcs";
        if (className.endsWith("PbsImpl") || className.endsWith("Pbs")) return "pbs";
        if (className.endsWith("PbcbImpl") || className.endsWith("Pbcb")) return "pbcb";
        if (className.endsWith("PbcpImpl") || className.endsWith("Pbcp")) return "pbcp";
        if (className.endsWith("PbccImpl") || className.endsWith("Pbcc")) return "pbcc";
        if (className.endsWith("PbctImpl") || className.endsWith("Pbct")) return "pbct";
        return "unknown";
    }

    private String detectModule(Path file) {
        String s = file.toString().replace('\\', '/');
        for (String ws : (workspaceRoots == null ? "" : workspaceRoots).split(",")) {
            String r = ws.trim().replace('\\', '/');
            if (!r.isEmpty() && s.startsWith(r)) {
                String rel = s.substring(r.length()).replaceFirst("^/", "");
                String[] parts = rel.split("/");
                if (parts.length >= 2) return parts[0] + "/" + parts[1];
            }
        }
        return "unknown";
    }

    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        for (var imp : cu.getImports()) {
            if (!imp.isAsterisk()) {
                String fqn = imp.getNameAsString();
                String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                map.put(simple, fqn);
            }
        }
        return map;
    }

    private List<String> collectSourceRoots() {
        List<String> roots = new ArrayList<>();
        if (sourceRoots != null && !sourceRoots.isBlank()) {
            for (String r : sourceRoots.split(",")) if (!r.isBlank()) roots.add(r.trim());
        }
        if (workspaceRoots != null && !workspaceRoots.isBlank()) {
            for (String w : workspaceRoots.split(",")) {
                String ws = w.trim();
                if (ws.isEmpty()) continue;
                Path wsPath = Path.of(ws);
                if (!Files.exists(wsPath)) continue;
                try (Stream<Path> walk = Files.walk(wsPath, 8)) {
                    walk.filter(Files::isDirectory)
                        .filter(p -> {
                            String rel = wsPath.relativize(p).toString().replace('\\', '/');
                            return rel.endsWith("src/main/java");
                        })
                        .map(Path::toString)
                        .forEach(roots::add);
                } catch (IOException e) {
                    log.warn("[CallRelation] workspace 扫描失败: {}", ws);
                }
            }
        }
        return roots;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 批量写入
    // ─────────────────────────────────────────────────────────────────────────

    private void batchInsert(List<Map<String, Object>> edges) {
        String sql = """
            INSERT IGNORE INTO call_relation
              (caller_id, caller_type, caller_method, caller_longname, caller_domain, caller_service_id,
               callee_id, callee_type, callee_method, callee_longname, callee_domain, callee_class,
               callee_service_id, callee_service_name,
               from_jar, is_direct, cross_domain, rule_violation, violation_desc, create_time, update_time)
            VALUES (?,?,?,?,?,?, ?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?)
            """;
        List<Object[]> batch = new ArrayList<>(2000);
        for (Map<String, Object> e : edges) {
            batch.add(new Object[]{
                e.get("caller_id"), e.get("caller_type"), e.get("caller_method"),
                e.get("caller_longname"), e.get("caller_domain"), e.get("caller_service_id"),
                e.get("callee_id"), e.get("callee_type"), e.get("callee_method"),
                e.get("callee_longname"), e.get("callee_domain"), e.get("callee_class"),
                e.get("callee_service_id"), e.get("callee_service_name"),
                e.get("from_jar"), e.get("is_direct"), e.get("cross_domain"),
                e.get("rule_violation"), e.get("violation_desc"),
                LocalDateTime.now(), LocalDateTime.now()
            });
            if (batch.size() == 2000) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbcTemplate.batchUpdate(sql, batch);
        log.info("[CallRelation] 写入 {} 条边", edges.size());
    }

    private Map<String, Object> summary(int edges, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalEdges", edges);
        m.put("elapsedMs", ms);
        return m;
    }
}
