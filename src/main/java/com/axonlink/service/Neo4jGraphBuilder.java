package com.axonlink.service;

import com.axonlink.common.DomainKeyResolver;
import com.axonlink.config.Neo4jConfig;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Neo4j AST 语法树图构建器。
 *
 * <p>基于 JavaParser AST 全量扫描 workspace-roots 下所有 Java 文件，
 * 构建类/接口/方法/调用关系图，写入 Neo4j 图数据库。
 *
 * <p>图数据模型：
 * <ul>
 *   <li>节点：Class, Interface, Method, Dao</li>
 *   <li>边：HAS_METHOD, CALLS, SYS_UTIL_CALLS, DAO_CALLS, SELF_CALLS, IMPLEMENTS, EXTENDS</li>
 * </ul>
 *
 * <p>Dao 节点：name=KXxxDao, tableName=KXxx（去掉 Dao 后缀），是调用链的终端节点。
 * 通过 DAO_CALLS 边连接 Method 与 Dao，可从任意入口方法推导出所有涉及的数据库表。
 *
 * <p>同名类处理：以 FQN（全限定名）为唯一标识。
 * <br>接口多实现：一个 Interface 可有多条 IMPLEMENTED_BY 边。
 * <br>只扫描 src/main/java，排除 JAR/test。
 */
@Service
public class Neo4jGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphBuilder.class);

    private final Driver driver;
    private final Neo4jConfig neo4jConfig;
    private final FlowtransMetaGraphBuilder flowtransMetaGraphBuilder;

    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    @Value("${project.source-roots:}")
    private String sourceRoots;

    @Value("${project.max-file-kb:500}")
    private int maxFileKb;

    @Value("${callgraph.include-packages:cn.sunline.ltts.busi}")
    private String includePackages;

    // JavaParser 每线程独立
    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() -> {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        return new JavaParser(cfg);
    });

    // 阶段间共享
    private final Map<String, String> simpleToFqn   = new ConcurrentHashMap<>(10000);
    private final Map<String, String> fqnToModule   = new ConcurrentHashMap<>(10000);

    // 进度
    private volatile String  phase    = "idle";
    private volatile boolean building = false;
    private final AtomicInteger totalFiles  = new AtomicInteger();
    private final AtomicInteger parsedFiles = new AtomicInteger();
    private volatile long startMs = 0;

    // 批量缓冲
    private static final int BATCH_SIZE = 2000;
    private final List<Map<String, Object>> classNodes     = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> interfaceNodes = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> methodNodes    = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> daoNodes       = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> implementsEdges= Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> extendsEdges   = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> hasMethodEdges = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> callsEdges     = Collections.synchronizedList(new ArrayList<>());

    public Neo4jGraphBuilder(Driver driver,
                             Neo4jConfig neo4jConfig,
                             FlowtransMetaGraphBuilder flowtransMetaGraphBuilder) {
        this.driver = driver;
        this.neo4jConfig = neo4jConfig;
        this.flowtransMetaGraphBuilder = flowtransMetaGraphBuilder;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公开接口
    // ─────────────────────────────────────────────────────────────────────────

    public void startBuildAsync() {
        if (!neo4jConfig.isEnabled() || driver == null) {
            log.info("[Neo4j] neo4j.enabled=false 或 Driver 不可用，跳过构建");
            return;
        }
        if (building) { log.warn("[Neo4j] 构建正在进行中"); return; }
        Thread t = new Thread(this::doBuild, "neo4j-graph-builder");
        t.setDaemon(true);
        t.start();
    }

    public Map<String, Object> buildSync() {
        if (!neo4jConfig.isEnabled() || driver == null)
            return Map.of("skipped", true, "reason", "neo4j.enabled=false");
        return doBuild();
    }

    public Map<String, Object> getProgress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase",       phase);
        m.put("building",    building);
        m.put("totalFiles",  totalFiles.get());
        m.put("parsedFiles", parsedFiles.get());
        m.put("elapsedMs",   building ? System.currentTimeMillis() - startMs : 0);
        return m;
    }

    public Map<String, Object> getStats() {
        if (!neo4jConfig.isEnabled() || driver == null) return Map.of("enabled", false);
        try (Session session = driver.session()) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("enabled", true);
            stats.put("classCount", session.run("MATCH (n:Class) RETURN count(n) AS c").single().get("c").asLong());
            stats.put("interfaceCount", session.run("MATCH (n:Interface) RETURN count(n) AS c").single().get("c").asLong());
            stats.put("methodCount", session.run("MATCH (n:Method) RETURN count(n) AS c").single().get("c").asLong());
            stats.put("daoNodeCount", session.run("MATCH (n:Dao) RETURN count(n) AS c").single().get("c").asLong());
            stats.put("callsEdgeCount", session.run("MATCH ()-[r:CALLS]->() RETURN count(r) AS c").single().get("c").asLong());
            stats.put("sysUtilEdgeCount", session.run("MATCH ()-[r:SYS_UTIL_CALLS]->() RETURN count(r) AS c").single().get("c").asLong());
            stats.put("daoEdgeCount", session.run("MATCH ()-[r:DAO_CALLS]->() RETURN count(r) AS c").single().get("c").asLong());
            stats.put("selfCallEdgeCount", session.run("MATCH ()-[r:SELF_CALLS]->() RETURN count(r) AS c").single().get("c").asLong());
            stats.put("implementsCount", session.run("MATCH ()-[r:IMPLEMENTS]->() RETURN count(r) AS c").single().get("c").asLong());
            stats.put("transactionCount", session.run("MATCH (n:Transaction) RETURN count(n) AS c").single().get("c").asLong());
            stats.put("flowMethodStepCount", session.run("MATCH (n:FlowMethodStep) RETURN count(n) AS c").single().get("c").asLong());
            stats.put("flowServiceStepCount", session.run("MATCH (n:FlowServiceStep) RETURN count(n) AS c").single().get("c").asLong());
            stats.put("serviceTypeCount", session.run("MATCH (n:ServiceType) RETURN count(n) AS c").single().get("c").asLong());
            stats.put("serviceOperationCount", session.run("MATCH (n:ServiceOperation) RETURN count(n) AS c").single().get("c").asLong());
            return stats;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * 调用链查询：从指定类/方法出发，向下 N 层穿透。
     *
     * <p>路径模型：Method -[CALLS]-> Class -[HAS_METHOD]-> Method -[CALLS]-> ...
     * 每跨一个 Class 节点算 1 次 HAS_METHOD 跳，实际方法层数 = depth/2。
     * depth 建议设为逻辑层数 × 2（如要穿 5 层方法，传 depth=10）。
     */
    public Map<String, Object> queryCallChain(String fqn, int depth) {
        if (driver == null) return Map.of("error", "Neo4j 不可用");
        int hops = Math.min(depth, 12);
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH path = (m:Method)-[:CALLS|SYS_UTIL_CALLS|DAO_CALLS|SELF_CALLS*1.." + hops + "]->(target) " +
                "WHERE m.classFqn = $fqn " +
                "RETURN [n IN nodes(path) | {name: coalesce(n.name, n.simpleName, ''), " +
                "  fqn: coalesce(n.classFqn, n.fqn, n.name, ''), type: labels(n)[0]}] AS chain " +
                "LIMIT 200",
                Values.parameters("fqn", fqn)
            );
            List<Object> chains = new ArrayList<>();
            result.forEachRemaining(r -> chains.add(r.get("chain").asList()));
            return Map.of("fqn", fqn, "depth", depth, "chains", chains);
        }
    }

    /**
     * 表访问分析：从指定入口方法出发，穿透调用链，统计所有涉及的 DAO/表。
     *
     * @param sig   入口方法签名，如 com.xxx.A#a()
     * @param depth 最大穿透层数
     */
    public Map<String, Object> queryTables(String sig, int depth) {
        if (driver == null) return Map.of("error", "Neo4j 不可用");
        int hops = Math.min(depth, 15);
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH (start:Method {signature: $sig}) " +
                "MATCH p = (start)-[:CALLS|SYS_UTIL_CALLS|SELF_CALLS|DAO_CALLS*1.." + hops + "]->(dao:Dao) " +
                "UNWIND relationships(p) AS r " +
                "WITH dao, r WHERE type(r) = 'DAO_CALLS' " +
                "RETURN dao.name AS daoClass, dao.tableName AS tableName, " +
                "       collect(DISTINCT r.methodName) AS operations, count(r) AS callCount " +
                "ORDER BY callCount DESC",
                Values.parameters("sig", sig)
            );
            List<Map<String, Object>> tables = new ArrayList<>();
            result.forEachRemaining(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("daoClass",   r.get("daoClass").asString());
                row.put("tableName",  r.get("tableName").asString());
                row.put("operations", r.get("operations").asList());
                row.put("callCount",  r.get("callCount").asLong());
                tables.add(row);
            });
            return Map.of("sig", sig, "depth", depth, "tableCount", tables.size(), "tables", tables);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * 反向影响面查询：谁调用了指定类的方法。
     */
    public Map<String, Object> queryImpact(String fqn, int depth) {
        if (driver == null) return Map.of("error", "Neo4j 不可用");
        int hops = Math.min(depth, 12);
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH path = (caller:Method)-[:CALLS|SYS_UTIL_CALLS|SELF_CALLS*1.." + hops + "]->(m:Method) " +
                "WHERE m.classFqn = $fqn " +
                "RETURN [n IN nodes(path) | {name: coalesce(n.name, n.simpleName, ''), " +
                "  fqn: coalesce(n.classFqn, n.fqn, n.name, ''), type: labels(n)[0]}] AS chain " +
                "LIMIT 200",
                Values.parameters("fqn", fqn)
            );
            List<Object> chains = new ArrayList<>();
            result.forEachRemaining(r -> chains.add(r.get("chain").asList()));
            return Map.of("fqn", fqn, "depth", depth, "callers", chains);
        }
    }

    public Map<String, Object> queryTransactionGraph(String txId, int depth) {
        return flowtransMetaGraphBuilder.queryTransactionGraph(txId, depth);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 核心构建流程
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> doBuild() {
        building = true;
        startMs = System.currentTimeMillis();
        totalFiles.set(0);
        parsedFiles.set(0);
        clearBuffers();
        simpleToFqn.clear();
        fqnToModule.clear();
        log.info("[Neo4j] ===== AST 图构建开始 =====");

        try {
            // 收集文件
            phase = "collect";
            List<Path> allFiles = collectFiles();
            totalFiles.set(allFiles.size());
            log.info("[Neo4j] 共 {} 个 Java 文件", allFiles.size());

            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

            // Phase 1：类/接口/方法声明 + implements/extends
            phase = "phase1_declarations";
            log.info("[Neo4j] Phase1: 解析声明，线程数={}", threads);
            ForkJoinPool pool = new ForkJoinPool(threads);
            pool.submit(() -> allFiles.parallelStream().forEach(f -> {
                if (f.toFile().length() > (long) maxFileKb * 1024L) return;
                parseDeclarations(f);
                parsedFiles.incrementAndGet();
            })).get();

            log.info("[Neo4j] Phase1 完成: classes={} interfaces={} methods={} implements={} extends={}",
                     classNodes.size(), interfaceNodes.size(), methodNodes.size(),
                     implementsEdges.size(), extendsEdges.size());

            // Phase 2：方法调用关系
            phase = "phase2_calls";
            parsedFiles.set(0);
            log.info("[Neo4j] Phase2: 解析调用关系");
            pool.submit(() -> allFiles.parallelStream().forEach(f -> {
                if (f.toFile().length() > (long) maxFileKb * 1024L) return;
                parseCalls(f);
                parsedFiles.incrementAndGet();
            })).get();
            pool.shutdown();

            log.info("[Neo4j] Phase2 完成: calls={}", callsEdges.size());

            // Phase 3：写入 Neo4j
            phase = "phase3_write";
            writeToNeo4j();

            // Phase 4：补充 flowtrans / serviceType 元数据图
            phase = "phase4_flowtrans";
            Map<String, Object> flowtransResult = flowtransMetaGraphBuilder.importMetadata();

            phase = "done";
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("[Neo4j] ===== 图构建完成，耗时 {}ms =====", elapsed);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("classes",    classNodes.size());
            result.put("interfaces", interfaceNodes.size());
            result.put("methods",    methodNodes.size());
            result.put("calls",      callsEdges.size());
            result.put("implements", implementsEdges.size());
            result.put("extends",    extendsEdges.size());
            result.put("flowtrans",  flowtransResult);
            result.put("elapsedMs",  elapsed);
            return result;

        } catch (Exception e) {
            log.error("[Neo4j] 构建异常", e);
            phase = "error";
            return Map.of("error", e.getMessage());
        } finally {
            building = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1：解析声明
    // ─────────────────────────────────────────────────────────────────────────

    private void parseDeclarations(Path file) {
        try {
            ParseResult<CompilationUnit> pr = PARSER.get().parse(Files.readString(file));
            if (!pr.isSuccessful() || pr.getResult().isEmpty()) return;
            CompilationUnit cu = pr.getResult().get();
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

                    String domainKey = DomainKeyResolver.resolve(pkg);
                    String filePath  = file.toString();

                    if (n.isInterface()) {
                        interfaceNodes.add(Map.of(
                            "fqn", fqn, "simpleName", simpleName, "module", module,
                            "domainKey", domainKey, "filePath", filePath));
                    } else {
                        String nodeKind = inferNodeKind(simpleName, filePath);
                        classNodes.add(Map.of(
                            "fqn", fqn, "simpleName", simpleName, "module", module,
                            "nodeKind", nodeKind, "domainKey", domainKey, "filePath", filePath));

                        // implements 边
                        for (ClassOrInterfaceType iface : n.getImplementedTypes()) {
                            String ifaceFqn = resolveToFqn(iface.getNameAsString(), cu);
                            implementsEdges.add(Map.of("classFqn", fqn, "interfaceFqn", ifaceFqn));
                        }
                        // extends 边
                        for (ClassOrInterfaceType ext : n.getExtendedTypes()) {
                            String parentFqn = resolveToFqn(ext.getNameAsString(), cu);
                            extendsEdges.add(Map.of("childFqn", fqn, "parentFqn", parentFqn));
                        }
                    }

                    // 方法声明 + HAS_METHOD 边
                    for (MethodDeclaration method : n.getMethods()) {
                        String sig = fqn + "#" + method.getNameAsString() + "(" +
                            method.getParameters().stream()
                                .map(p -> p.getTypeAsString().replaceAll("<.*>", "").trim())
                                .collect(Collectors.joining(",")) + ")";
                        int lineNo = method.getBegin().map(p -> p.line).orElse(-1);
                        methodNodes.add(Map.of(
                            "signature", sig, "name", method.getNameAsString(),
                            "classFqn", fqn, "lineNo", lineNo));
                        hasMethodEdges.add(Map.of("classFqn", fqn, "methodSig", sig));
                    }

                    super.visit(n, arg);
                }
            }, null);
        } catch (Exception e) {
            log.debug("[Neo4j] Phase1 跳过: {}", file.getFileName());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2：解析调用关系
    // ─────────────────────────────────────────────────────────────────────────

    private void parseCalls(Path file) {
        try {
            ParseResult<CompilationUnit> pr = PARSER.get().parse(Files.readString(file));
            if (!pr.isSuccessful() || pr.getResult().isEmpty()) return;
            CompilationUnit cu = pr.getResult().get();
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            if (!isIncluded(pkg)) return;

            Map<String, String> importMap = buildImportMap(cu);

            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration clazz, Void arg) {
                    if (clazz.isInterface()) { super.visit(clazz, arg); return; }
                    String classFqn = pkg.isEmpty() ? clazz.getNameAsString() : pkg + "." + clazz.getNameAsString();

                    // 收集类级字段类型表：fieldName → 字段类型简名
                    // 用于识别 @Autowired / 普通字段的 fieldName.method() 调用
                    Map<String, String> fieldTypeMap = new HashMap<>();
                    for (var field : clazz.getFields()) {
                        String typeName = field.getElementType().asString()
                            .replaceAll("<.*>", "").trim();
                        for (var v : field.getVariables()) {
                            fieldTypeMap.put(v.getNameAsString(), typeName);
                        }
                    }

                    for (MethodDeclaration method : clazz.getMethods()) {
                        String callerSig = classFqn + "#" + method.getNameAsString() + "(" +
                            method.getParameters().stream()
                                .map(p -> p.getTypeAsString().replaceAll("<.*>", "").trim())
                                .collect(Collectors.joining(",")) + ")";

                        // 先收集方法体内 SysUtil 变量赋值：varName → targetClass
                        // 处理两种形式：
                        //   IoDpAccLimitApsSvtp xxx = SysUtil.getInstance(IoDpAccLimitApsSvtp.class);
                        //   var xxx = SysUtil.getInstance(IoDpAccLimitApsSvtp.class);
                        Map<String, String> sysUtilVarMap = new HashMap<>();
                        method.accept(new VoidVisitorAdapter<Void>() {
                            @Override
                            public void visit(com.github.javaparser.ast.expr.VariableDeclarationExpr vde, Void a) {
                                super.visit(vde, a);
                                for (var decl : vde.getVariables()) {
                                    decl.getInitializer().ifPresent(init -> {
                                        if (init instanceof MethodCallExpr mc && isSysUtilGetInstance(mc)) {
                                            String targetSimple = extractClassArg(mc);
                                            if (targetSimple != null) {
                                                String varName = decl.getNameAsString();
                                                String targetFqn = resolveSimpleName(targetSimple, cu, importMap);
                                                sysUtilVarMap.put(varName, targetFqn);
                                            }
                                        }
                                    });
                                }
                            }
                            // 也处理赋值语句：xxx = SysUtil.getInstance(X.class);
                            @Override
                            public void visit(com.github.javaparser.ast.expr.AssignExpr ae, Void a) {
                                super.visit(ae, a);
                                Expression value = ae.getValue();
                                if (value instanceof MethodCallExpr mc && isSysUtilGetInstance(mc)) {
                                    String targetSimple = extractClassArg(mc);
                                    if (targetSimple != null && ae.getTarget() instanceof NameExpr ne) {
                                        String targetFqn = resolveSimpleName(targetSimple, cu, importMap);
                                        sysUtilVarMap.put(ne.getNameAsString(), targetFqn);
                                    }
                                }
                            }
                        }, null);

                        // 再扫描方法调用，将变量方式调用也识别为 SYS_UTIL_CALLS
                        method.accept(new VoidVisitorAdapter<Void>() {
                            @Override
                            public void visit(MethodCallExpr call, Void a) {
                                super.visit(call, a);
                                processCall(call, callerSig, classFqn, cu, importMap, sysUtilVarMap, fieldTypeMap);
                            }
                        }, null);
                    }
                    super.visit(clazz, arg);
                }
            }, null);
        } catch (Exception e) {
            log.debug("[Neo4j] Phase2 跳过: {}", file.getFileName());
        }
    }

    private void processCall(MethodCallExpr call, String callerSig, String callerClassFqn,
                              CompilationUnit cu, Map<String, String> importMap) {
        processCall(call, callerSig, callerClassFqn, cu, importMap, Map.of(), Map.of());
    }

    private void processCall(MethodCallExpr call, String callerSig, String callerClassFqn,
                              CompilationUnit cu, Map<String, String> importMap,
                              Map<String, String> sysUtilVarMap) {
        processCall(call, callerSig, callerClassFqn, cu, importMap, sysUtilVarMap, Map.of());
    }

    private void processCall(MethodCallExpr call, String callerSig, String callerClassFqn,
                              CompilationUnit cu, Map<String, String> importMap,
                              Map<String, String> sysUtilVarMap,
                              Map<String, String> fieldTypeMap) {
        Optional<Expression> scopeOpt = call.getScope();
        String methodName = call.getNameAsString();
        int lineNo = call.getBegin().map(p -> p.line).orElse(-1);

        // 形式0：无 scope → 同类内部方法调用，如 d1()
        // 排除 log.xxx()、super.xxx() 等常见噪声
        if (scopeOpt.isEmpty()) {
            String excludedMethods = "toString|hashCode|equals|getClass|notify|notifyAll|wait";
            if (!methodName.matches(excludedMethods) && isIncluded(callerClassFqn)) {
                callsEdges.add(Map.of(
                    "callerSig",  callerSig,
                    "targetFqn",  callerClassFqn,  // 同类
                    "methodName", methodName,
                    "lineNo",     lineNo,
                    "type",       "SELF_CALLS"));
            }
            return;
        }

        Expression scope = scopeOpt.get();

        // 形式1：SysUtil.getInstance(X.class).method()  ← 链式调用
        if (isSysUtilGetInstance(scope)) {
            String targetSimple = extractClassArg((MethodCallExpr) scope);
            if (targetSimple == null) return;
            String targetFqn = resolveSimpleName(targetSimple, cu, importMap);
            callsEdges.add(Map.of(
                "callerSig", callerSig, "targetFqn", targetFqn,
                "methodName", methodName, "lineNo", lineNo, "type", "SYS_UTIL_CALLS"));
            return;
        }

        if (scope instanceof NameExpr nameExpr) {
            String scopeName = nameExpr.getNameAsString();

            // 形式2：xxx = SysUtil.getInstance(X.class); xxx.method()  ← 变量调用
            if (sysUtilVarMap.containsKey(scopeName)) {
                String targetFqn = sysUtilVarMap.get(scopeName);
                callsEdges.add(Map.of(
                    "callerSig", callerSig, "targetFqn", targetFqn,
                    "methodName", methodName, "lineNo", lineNo, "type", "SYS_UTIL_CALLS"));
                return;
            }

            // 形式3：KxxxDao.method()  ← DAO 调用，同时注册 Dao 节点
            if (scopeName.matches("K\\w+Dao")) {
                String tableName = scopeName.replaceAll("Dao$", "");
                daoNodes.add(Map.of("name", scopeName, "tableName", tableName));
                callsEdges.add(Map.of(
                    "callerSig", callerSig, "targetFqn", scopeName,
                    "methodName", methodName, "lineNo", lineNo, "type", "DAO_CALLS"));
                return;
            }

            // 形式4：fieldName.method()  ← @Autowired / 成员字段调用
            // 如：dpSetAccLimitBcs.setAccLimit(...)，dpSetAccLimitBcs 是类的字段
            if (fieldTypeMap.containsKey(scopeName)) {
                String fieldTypeName = fieldTypeMap.get(scopeName);
                String targetFqn = resolveSimpleName(fieldTypeName, cu, importMap);
                if (isIncluded(targetFqn)) {
                    callsEdges.add(Map.of(
                        "callerSig", callerSig, "targetFqn", targetFqn,
                        "methodName", methodName, "lineNo", lineNo, "type", "CALLS"));
                }
                return;
            }

            // 形式5：ClassName.staticMethod()  ← 普通静态调用
            if (!"SysUtil".equals(scopeName)) {
                String targetFqn = resolveSimpleName(scopeName, cu, importMap);
                if (isIncluded(targetFqn)) {
                    callsEdges.add(Map.of(
                        "callerSig", callerSig, "targetFqn", targetFqn,
                        "methodName", methodName, "lineNo", lineNo, "type", "CALLS"));
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 3：写入 Neo4j
    // ─────────────────────────────────────────────────────────────────────────

    private void writeToNeo4j() {
        try (Session session = driver.session()) {
            // 清空旧图
            session.run("MATCH (n) DETACH DELETE n");
            log.info("[Neo4j] 已清空旧图数据");

            // 建索引
            session.run("CREATE INDEX IF NOT EXISTS FOR (c:Class) ON (c.fqn)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (i:Interface) ON (i.fqn)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (m:Method) ON (m.signature)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (m:Method) ON (m.classFqn, m.name)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (d:Dao) ON (d.name)");

            // 写入 Class 节点
            batchWrite(session, classNodes,
                "UNWIND $batch AS row " +
                "CREATE (c:Class {fqn: row.fqn, simpleName: row.simpleName, module: row.module, " +
                "nodeKind: row.nodeKind, domainKey: row.domainKey, filePath: row.filePath})");
            log.info("[Neo4j] 写入 Class 节点: {}", classNodes.size());

            // 写入 Interface 节点
            batchWrite(session, interfaceNodes,
                "UNWIND $batch AS row " +
                "CREATE (i:Interface {fqn: row.fqn, simpleName: row.simpleName, module: row.module, " +
                "domainKey: row.domainKey, filePath: row.filePath})");
            log.info("[Neo4j] 写入 Interface 节点: {}", interfaceNodes.size());

            // 写入 Method 节点
            batchWrite(session, methodNodes,
                "UNWIND $batch AS row " +
                "CREATE (m:Method {signature: row.signature, name: row.name, " +
                "classFqn: row.classFqn, lineNo: row.lineNo})");
            log.info("[Neo4j] 写入 Method 节点: {}", methodNodes.size());

            // 写入 Dao 节点（去重，同一个 DAO 可能被多处引用）
            Map<String, Map<String, Object>> daoMap = new LinkedHashMap<>();
            for (Map<String, Object> dao : daoNodes) {
                daoMap.putIfAbsent((String) dao.get("name"), dao);
            }
            List<Map<String, Object>> uniqueDaoNodes = new ArrayList<>(daoMap.values());
            batchWrite(session, uniqueDaoNodes,
                "UNWIND $batch AS row " +
                "MERGE (d:Dao {name: row.name}) " +
                "SET d.tableName = row.tableName");
            log.info("[Neo4j] 写入 Dao 节点: {}", uniqueDaoNodes.size());

            // IMPLEMENTS 边
            batchWrite(session, implementsEdges,
                "UNWIND $batch AS row " +
                "MATCH (c:Class {fqn: row.classFqn}) " +
                "MATCH (i:Interface {fqn: row.interfaceFqn}) " +
                "CREATE (c)-[:IMPLEMENTS]->(i)");
            log.info("[Neo4j] 写入 IMPLEMENTS 边: {}", implementsEdges.size());

            // EXTENDS 边
            batchWrite(session, extendsEdges,
                "UNWIND $batch AS row " +
                "MATCH (child:Class {fqn: row.childFqn}) " +
                "MATCH (parent:Class {fqn: row.parentFqn}) " +
                "CREATE (child)-[:EXTENDS]->(parent)");
            log.info("[Neo4j] 写入 EXTENDS 边: {}", extendsEdges.size());

            // HAS_METHOD 边
            batchWrite(session, hasMethodEdges,
                "UNWIND $batch AS row " +
                "MATCH (c:Class {fqn: row.classFqn}) " +
                "MATCH (m:Method {signature: row.methodSig}) " +
                "CREATE (c)-[:HAS_METHOD]->(m)");
            log.info("[Neo4j] 写入 HAS_METHOD 边: {}", hasMethodEdges.size());

            // CALLS / SYS_UTIL_CALLS / SELF_CALLS 边
            // 优先连到目标 Method 节点（Method→Method），无法匹配时回退到 Class/Interface 节点
            // 这样遍历查询只需 CALLS* 即可穿透，无需 HAS_METHOD 造成组合爆炸
            for (String edgeType : List.of("CALLS", "SYS_UTIL_CALLS", "SELF_CALLS")) {
                List<Map<String, Object>> typed = callsEdges.stream()
                    .filter(e -> edgeType.equals(e.get("type")))
                    .collect(Collectors.toList());
                if (typed.isEmpty()) continue;

                // Pass 1：Method → Method（callee 方法在图中存在时）
                batchWrite(session, typed,
                    "UNWIND $batch AS row " +
                    "MATCH (caller:Method {signature: row.callerSig}) " +
                    "MATCH (callee:Method {classFqn: row.targetFqn, name: row.methodName}) " +
                    "CREATE (caller)-[:" + edgeType + " {lineNo: row.lineNo}]->(callee)");

                // Pass 2：Method → Class/Interface（callee 方法不在图中时的回退）
                batchWrite(session, typed,
                    "UNWIND $batch AS row " +
                    "MATCH (caller:Method {signature: row.callerSig}) " +
                    "OPTIONAL MATCH (matched:Method {classFqn: row.targetFqn, name: row.methodName}) " +
                    "WITH caller, row, matched " +
                    "WHERE matched IS NULL " +
                    "OPTIONAL MATCH (targetC:Class {fqn: row.targetFqn}) " +
                    "OPTIONAL MATCH (targetI:Interface {fqn: row.targetFqn}) " +
                    "WITH caller, row, COALESCE(targetC, targetI) AS target " +
                    "WHERE target IS NOT NULL " +
                    "CREATE (caller)-[:" + edgeType + " {methodName: row.methodName, lineNo: row.lineNo}]->(target)");

                log.info("[Neo4j] 写入 {} 边: {}", edgeType, typed.size());
            }

            // DAO_CALLS 边（目标为 Dao 节点）
            List<Map<String, Object>> daoCallEdges = callsEdges.stream()
                .filter(e -> "DAO_CALLS".equals(e.get("type")))
                .collect(Collectors.toList());
            if (!daoCallEdges.isEmpty()) {
                batchWrite(session, daoCallEdges,
                    "UNWIND $batch AS row " +
                    "MATCH (caller:Method {signature: row.callerSig}) " +
                    "MATCH (dao:Dao {name: row.targetFqn}) " +
                    "CREATE (caller)-[:DAO_CALLS {methodName: row.methodName, lineNo: row.lineNo}]->(dao)");
                log.info("[Neo4j] 写入 DAO_CALLS 边: {}", daoCallEdges.size());
            }
        }
    }

    private void batchWrite(Session session, List<Map<String, Object>> data, String cypher) {
        for (int i = 0; i < data.size(); i += BATCH_SIZE) {
            List<Map<String, Object>> batch = data.subList(i, Math.min(i + BATCH_SIZE, data.size()));
            session.run(cypher, Values.parameters("batch", batch));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isSysUtilGetInstance(Expression expr) {
        if (!(expr instanceof MethodCallExpr mc)) return false;
        String name = mc.getNameAsString();
        if (!name.equals("getInstance") && !name.equals("getRemoteInstance") && !name.equals("getInsance")) return false;
        return mc.getScope().filter(s -> s instanceof NameExpr ne && ne.getNameAsString().equals("SysUtil")).isPresent();
    }

    private String extractClassArg(MethodCallExpr sysUtilCall) {
        if (sysUtilCall.getArguments().isEmpty()) return null;
        Expression arg0 = sysUtilCall.getArguments().get(0);
        if (arg0 instanceof ClassExpr ce) return ce.getTypeAsString().replaceAll("<.*>", "").trim();
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

    private String resolveToFqn(String simpleName, CompilationUnit cu) {
        if (simpleName.contains(".")) return simpleName;
        String fromIndex = simpleToFqn.get(simpleName);
        if (fromIndex != null) return fromIndex;
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString() + "." + simpleName).orElse(simpleName);
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

    private boolean isIncluded(String pkg) {
        if (pkg == null || pkg.isBlank()) return false;
        for (String prefix : includePackages.split(",")) {
            if (pkg.startsWith(prefix.trim())) return true;
        }
        return false;
    }

    private String inferNodeKind(String name, String filePath) {
        if (name.endsWith("ApsImpl") || name.endsWith("ApsSvtp")) return "APS";
        if (name.endsWith("BcsImpl")) return "BCS";
        if (name.endsWith("Dao") || filePath.contains("/namedsql/")) return "DAO";
        if (filePath.contains("/type/") || filePath.contains("/pojo/")) return "POJO";
        return "OTHER";
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

    private List<Path> collectFiles() {
        List<Path> files = new ArrayList<>();
        List<String> roots = new ArrayList<>();
        if (sourceRoots != null && !sourceRoots.isBlank())
            for (String r : sourceRoots.split(",")) if (!r.isBlank()) roots.add(r.trim());
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
                            return rel.endsWith("src/main/java") || rel.endsWith("target/gen");
                        })
                        .map(Path::toString).forEach(roots::add);
                } catch (IOException ignored) {}
            }
        }
        for (String root : roots) {
            Path p = Path.of(root);
            if (!Files.exists(p)) continue;
            try (Stream<Path> walk = Files.walk(p)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().replace('\\', '/').contains("/test/"))
                    .forEach(files::add);
            } catch (IOException ignored) {}
        }
        return files;
    }

    private void clearBuffers() {
        classNodes.clear(); interfaceNodes.clear(); methodNodes.clear();
        daoNodes.clear();
        implementsEdges.clear(); extendsEdges.clear(); hasMethodEdges.clear();
        callsEdges.clear();
    }
}
