package com.axonlink.local;

import com.axonlink.local.FlowServiceMetadataResolver.ServiceTypeFileMeta;
import com.axonlink.local.TableMetadataResolver.TableMeta;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.axonlink.local.Graph.firstNonBlank;
import static com.axonlink.local.Graph.id;
import static com.axonlink.local.Graph.props;

/** 提取自 Neo4jGraphBuilder 的声明、调用点与连边规则。 */
final class JavaCallScanner {
    private static final Logger log = LoggerFactory.getLogger(JavaCallScanner.class);

    // 来自 SysUtilTargetRegistry，通用 service 类型走普通调用分支。
    private static final Set<String> SYS_UTIL_KINDS = Set.of("pbs", "pcs", "pbcb", "pbcp", "pbcc", "pbct");
    private static final Set<String> SYS_UTIL_METHODS = Set.of("getInstance", "getRemoteInstance", "getInsance");
    private static final Set<String> IGNORED_SELF_METHODS = Set.of(
        "toString", "hashCode", "equals", "getClass", "notify", "notifyAll", "wait");

    private final JavaParser parser = new JavaParser(
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    private final Graph graph;
    private final ScanSettings settings;
    private final SourceLayout layout;
    private final FlowServiceMetadataResolver services;
    private final TableMetadataResolver tables;
    private final ScanProgress progress;
    private final List<String> includedPackages;
    private final Map<String, String> simpleToFqn = new HashMap<>();
    private final List<Hierarchy> implementsEdges = new ArrayList<>();
    private final List<Call> calls = new ArrayList<>();
    private final Set<Call> unlinkedSysUtilCalls = new HashSet<>();

    JavaCallScanner(Graph graph, ScanSettings settings, SourceLayout layout,
                    FlowServiceMetadataResolver services, TableMetadataResolver tables, ScanProgress progress) {
        this.graph = graph;
        this.settings = settings;
        this.layout = layout;
        this.services = services;
        this.tables = tables;
        this.progress = progress;
        this.includedPackages = List.of(settings.includePackages().split(","));
    }

    int scan() {
        for (TableMeta table : tables.allMetadata()) {
            if (graph.get("Table", table.tableId()) == null) {
                graph.put("Table", table.tableId(), props(
                    "name", table.tableLongname(), "domainKey", table.domainKey(),
                    "projectName", table.projectName(), "filePath", table.xmlFilePath(),
                    "packagePath", table.packagePath(), "schemaId", table.schemaId(),
                    "outerClassName", table.outerClassName(), "outerClassFqn", table.outerClassFqn(),
                    "daoClassName", table.daoClassName()));
            }
        }

        progress.begin("Java 文件发现");
        List<Path> javaFiles = layout.collectJava(settings.sourcePaths(), graph, progress.id());
        progress.finish("candidateFiles=" + javaFiles.size());

        progress.begin("Java 声明解析");
        List<Path> parsedFiles = new ArrayList<>();
        List<Hierarchy> extendsEdges = new ArrayList<>();
        int skippedByPackage = 0;
        for (int i = 0; i < javaFiles.size(); i++) {
            Path file = javaFiles.get(i);
            progress.file(file, i + 1, javaFiles.size());
            CompilationUnit unit = parse(file);
            if (unit == null) {
                continue;
            }
            String pkg = packageName(unit);
            if (!isIncluded(pkg)) {
                skippedByPackage++;
                progress.skip(file, -1, "包前缀不匹配，package=" + pkg);
                continue;
            }
            collectDeclarations(file, unit, extendsEdges);
            parsedFiles.add(file);
        }
        for (Hierarchy edge : extendsEdges) {
            graph.link(id("Class", edge.from()), "EXTENDS", id("Class", edge.to()));
        }
        progress.finish("includedFiles=%d skippedByPackage=%d classes=%d interfaces=%d methods=%d".formatted(
            parsedFiles.size(), skippedByPackage, graph.nodes("Class").size(),
            graph.nodes("Interface").size(), graph.nodes("Method").size()));

        progress.begin("Java 调用解析");
        for (int i = 0; i < parsedFiles.size(); i++) {
            Path file = parsedFiles.get(i);
            progress.file(file, i + 1, parsedFiles.size());
            CompilationUnit unit = parse(file);
            if (unit != null) {
                collectCalls(file, unit);
            }
        }
        progress.finish("candidateCalls=%d daoMethods=%d".formatted(calls.size(), graph.nodes("DaoMethod").size()));

        // 先落 Java 调用，再按生产构建顺序补充 XML 派生的接口占位和操作边。
        progress.begin("Java 调用连边");
        int before = graph.edges().size();
        linkCalls();
        progress.finish("candidateCalls=%d newEdges=%d".formatted(calls.size(), graph.edges().size() - before));
        return parsedFiles.size();
    }

    void backfillMetadataLinks() {
        for (Graph.Node operation : graph.nodes("ServiceOperation")) {
            String interfaceFqn = operation.text("interfaceFqn");
            if (interfaceFqn != null && graph.get("Interface", interfaceFqn) == null) {
                graph.put("Interface", interfaceFqn, props("name", simpleTypeName(interfaceFqn)));
            }
        }
        for (Graph.Node type : graph.nodes("ServiceType")) {
            String interfaceFqn = type.text("interfaceFqn");
            if (interfaceFqn != null && graph.get("Interface", interfaceFqn) == null) {
                graph.put("Interface", interfaceFqn, props("name", simpleTypeName(interfaceFqn)));
            }
        }
        for (Hierarchy edge : implementsEdges) {
            graph.link(id("Class", edge.from()), "IMPLEMENTS", id("Interface", edge.to()));
        }
        Map<String, Set<String>> ownersByInterface = new HashMap<>();
        for (Graph.Node operation : graph.nodes("ServiceOperation")) {
            int before = graph.edges().size();
            String interfaceFqn = operation.text("interfaceFqn");
            String methodName = operation.text("methodName");
            if (interfaceFqn != null && methodName != null) {
                for (String owner : ownersByInterface.computeIfAbsent(interfaceFqn, this::implementationOwners)) {
                    for (String method : graph.methods(owner, methodName)) {
                        graph.link(operation.id(), "IMPLEMENTS_BY", method);
                    }
                }
            }
            if (graph.edges().size() == before && log.isDebugEnabled()) {
                log.debug("[scan={}] 服务操作未绑定实现 operation={} file={} interfaceFqn={} methodName={}",
                    progress.id(), operation.key(), operation.text("filePath"), interfaceFqn, methodName);
            }
        }
        Map<Hierarchy, List<Graph.Node>> operations = new LinkedHashMap<>();
        for (Graph.Node type : graph.nodes("ServiceType")) {
            String interfaceFqn = type.text("interfaceFqn");
            if (interfaceFqn == null) {
                continue;
            }
            for (Graph.Edge edge : graph.outgoing(type.id())) {
                if ("DECLARES_OPERATION".equals(edge.type())) {
                    Graph.Node operation = graph.get(edge.to());
                    operations.computeIfAbsent(new Hierarchy(interfaceFqn, operation.text("methodName")),
                        ignored -> new ArrayList<>()).add(operation);
                }
            }
        }

        Set<Hierarchy> bridged = new HashSet<>();
        for (Call call : calls) {
            if (!"SYS_UTIL_CALLS".equals(call.kind())) {
                continue;
            }
            List<Graph.Node> targets = operations.getOrDefault(new Hierarchy(call.target(), call.method()), List.of());
            for (Graph.Node target : targets) {
                if (bridged.add(new Hierarchy(call.caller(), target.id()))) {
                    graph.link(new Graph.Edge(call.caller(), target.id(), call.kind(), call.line(), call.method()));
                }
            }
            if (targets.isEmpty() && unlinkedSysUtilCalls.contains(call)) {
                report(call, "CALL_TARGET", "未找到 SysUtil 调用目标 " + call.target() + "#" + call.method());
            }
        }
    }

    private Set<String> implementationOwners(String interfaceFqn) {
        String interfaceId = id("Interface", interfaceFqn);
        Set<String> owners = new LinkedHashSet<>();
        List<String> frontier = graph.incoming(interfaceId).stream()
            .filter(edge -> "IMPLEMENTS".equals(edge.type())).map(Graph.Edge::from).toList();
        Set<String> visited = new HashSet<>(frontier);
        for (int depth = 0; depth <= 6 && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String owner : frontier) {
                owners.add(graph.get(owner).key());
                for (Graph.Edge edge : graph.incoming(owner)) {
                    if ("EXTENDS".equals(edge.type()) && visited.add(edge.from())) {
                        next.add(edge.from());
                    }
                }
            }
            frontier = next;
        }
        return owners;
    }

    private CompilationUnit parse(Path file) {
        try {
            long size = Files.size(file);
            if (size > (long) settings.maxFileKb() * 1024L) {
                graph.report(new Graph.Issue(null, file.toString(), -1, "FILE_LIMIT",
                    "Java 文件大小 %d 字节，超过上限 %d KB".formatted(size, settings.maxFileKb())));
                return null;
            }
            var result = parser.parse(Files.readString(file));
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return result.getResult().get();
            }
            var problem = result.getProblems().stream().findFirst().orElse(null);
            int line = problem == null ? -1 : problem.getLocation()
                .flatMap(tokens -> tokens.getBegin().getRange()).map(range -> range.begin.line).orElse(-1);
            String reason = problem == null ? "Java AST 解析失败" : problem.getMessage();
            graph.report(new Graph.Issue(null, file.toString(), line, "JAVA_PARSE", reason));
        } catch (IOException e) {
            graph.report(new Graph.Issue(null, file.toString(), -1, "JAVA_READ", e.getMessage()));
        }
        return null;
    }

    private void collectDeclarations(Path file, CompilationUnit unit, List<Hierarchy> extendsEdges) {
        String pkg = packageName(unit);
        Map<String, String> imports = imports(unit);
        String parent = layout.parentProject(file);
        String domain = DomainKeyResolver.resolveByProjectOrPackage(parent, pkg);
        String filePath = file.toString();
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            String name = type.getNameAsString();
            String fqn = qualifiedName(pkg, name);
            simpleToFqn.putIfAbsent(name, fqn);
            graph.put(type.isInterface() ? "Interface" : "Class", fqn, props(
                "name", name, "module", layout.module(file), "domainKey", domain, "filePath", filePath,
                "nodeKind", type.isInterface() ? null : nodeKind(name, filePath)));
            if (!type.isInterface()) {
                for (ClassOrInterfaceType target : type.getImplementedTypes()) {
                    implementsEdges.add(new Hierarchy(fqn, resolve(target.getNameWithScope(), pkg, imports)));
                }
                for (ClassOrInterfaceType target : type.getExtendedTypes()) {
                    extendsEdges.add(new Hierarchy(fqn, resolve(target.getNameWithScope(), pkg, imports)));
                }
            }
            for (MethodDeclaration method : type.getMethods()) {
                graph.put("Method", signature(fqn, method), props(
                    "name", method.getNameAsString(), "classFqn", fqn, "filePath", filePath,
                    "line", method.getBegin().map(p -> p.line).orElse(-1)));
            }
        }
    }

    private void collectCalls(Path file, CompilationUnit unit) {
        String pkg = packageName(unit);
        Map<String, String> imports = imports(unit);
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) {
                continue;
            }
            String owner = qualifiedName(pkg, type.getNameAsString());
            Map<String, String> fields = new HashMap<>();
            for (var field : type.getFields()) {
                for (var variable : field.getVariables()) {
                    fields.put(variable.getNameAsString(), eraseGenerics(field.getElementType().asString()));
                }
            }
            for (MethodDeclaration method : type.getMethods()) {
                String caller = id("Method", signature(owner, method));
                Map<String, String> variables = new HashMap<>();
                method.accept(new VoidVisitorAdapter<Void>() {
                    @Override
                    public void visit(VariableDeclarationExpr node, Void ignored) {
                        super.visit(node, ignored);
                        for (var variable : node.getVariables()) {
                            variable.getInitializer().ifPresent(value ->
                                registerVariable(variable.getNameAsString(), value));
                        }
                    }

                    @Override
                    public void visit(AssignExpr node, Void ignored) {
                        super.visit(node, ignored);
                        if (node.getTarget() instanceof NameExpr name) {
                            registerVariable(name.getNameAsString(), node.getValue());
                        }
                    }

                    private void registerVariable(String name, Expression value) {
                        if (value instanceof MethodCallExpr call && isSysUtilFactory(call)) {
                            String target = sysUtilTarget(file, caller, call, pkg, imports);
                            if (target != null) {
                                variables.put(name, target);
                            }
                        }
                    }
                }, null);
                method.accept(new VoidVisitorAdapter<Void>() {
                    @Override
                    public void visit(MethodCallExpr call, Void ignored) {
                        super.visit(call, ignored);
                        processCall(file, owner, caller, call, pkg, imports, fields, variables);
                    }
                }, null);
            }
        }
    }

    private void processCall(Path file, String owner, String caller, MethodCallExpr call,
                             String pkg, Map<String, String> imports,
                             Map<String, String> fields, Map<String, String> variables) {
        Expression scope = call.getScope().orElse(null);
        String method = call.getNameAsString();
        int line = call.getBegin().map(p -> p.line).orElse(-1);
        if (scope == null) {
            if (!IGNORED_SELF_METHODS.contains(method)) {
                calls.add(new Call(file, caller, "SELF_CALLS", owner, method, line));
            }
            return;
        }
        if (scope instanceof MethodCallExpr factory && isSysUtilFactory(factory)) {
            String target = sysUtilTarget(file, caller, factory, pkg, imports);
            if (target != null) {
                calls.add(new Call(file, caller, "SYS_UTIL_CALLS", target, method, line));
            }
            return;
        }
        if (scope instanceof NameExpr name && variables.containsKey(name.getNameAsString())) {
            calls.add(new Call(file, caller, "SYS_UTIL_CALLS", variables.get(name.getNameAsString()), method, line));
            return;
        }
        String dao = daoName(scope, fields);
        if (dao != null) {
            registerDaoCall(new Call(file, caller, "DAO_CALLS", dao, method, line));
            return;
        }
        if (scope instanceof NameExpr name) {
            String receiver = name.getNameAsString();
            if (fields.containsKey(receiver) || !"SysUtil".equals(receiver)) {
                String target = resolve(fields.getOrDefault(receiver, receiver), pkg, imports);
                if (isIncluded(target)) {
                    calls.add(new Call(file, caller, "CALLS", target, method, line));
                } else {
                    log.debug("[scan={}] 调用目标包前缀不匹配 caller={} target={} method={} file={} line={}",
                        progress.id(), caller, target, method, file, line);
                }
            }
            return;
        }
        log.debug("[scan={}] 调用接收者无匹配规则 caller={} method={} scopeType={} file={} line={}",
            progress.id(), caller, method, scope.getClass().getSimpleName(), file, line);
    }

    private void registerDaoCall(Call call) {
        String dao = call.target();
        String key = dao + "#" + call.method();
        TableMeta table = tables.findByDaoClassName(dao).orElse(null);
        Graph.Node daoNode = graph.put("DaoMethod", key, props(
            "name", call.method(), "methodName", call.method(), "daoClassName", dao,
            "tableId", table == null ? null : table.tableId()));
        if (table == null) {
            report(call, "DAO_TABLE", "DAO 方法已识别，缺少表定义 " + dao);
        } else {
            graph.link(id("Table", table.tableId()), "EXPOSES_DAO", daoNode.id());
        }
        graph.link(new Graph.Edge(call.caller(), daoNode.id(), call.kind(), call.line(), call.method()));
    }

    private void linkCalls() {
        for (Call call : calls) {
            List<String> methods = graph.methods(call.target(), call.method());
            if (!methods.isEmpty()) {
                for (String method : methods) {
                    graph.link(new Graph.Edge(call.caller(), method, call.kind(), call.line(), ""));
                }
                continue;
            }
            Graph.Node target = graph.get("Class", call.target());
            if (target == null) {
                target = graph.get("Interface", call.target());
            }
            if (target != null) {
                graph.link(new Graph.Edge(call.caller(), target.id(), call.kind(), call.line(), call.method()));
            } else if ("SYS_UTIL_CALLS".equals(call.kind())) {
                unlinkedSysUtilCalls.add(call);
            } else {
                report(call, "CALL_TARGET", "未找到 Java 调用目标 " + call.target() + "#" + call.method());
            }
        }
    }

    private String sysUtilTarget(Path file, String caller, MethodCallExpr call,
                                 String pkg, Map<String, String> imports) {
        int line = call.getBegin().map(p -> p.line).orElse(-1);
        if (call.getArguments().isEmpty() || !(call.getArgument(0) instanceof ClassExpr arg)) {
            progress.skip(file, line, "SysUtil 调用的首个参数需要类字面量");
            return null;
        }
        String type = eraseGenerics(arg.getTypeAsString()).replace("[]", "").trim();
        String simple = simpleTypeName(type);
        var qualified = services.findByTypeId(type);
        var unqualified = type.equals(simple) ? qualified : services.findByTypeId(simple);
        ServiceTypeFileMeta meta = qualified.filter(JavaCallScanner::isAllowedSysUtilTarget)
            .or(() -> unqualified.filter(JavaCallScanner::isAllowedSysUtilTarget)).orElse(null);
        if (meta == null) {
            var definition = qualified.or(() -> unqualified).orElse(null);
            String reason = definition == null
                ? "类型索引中无对应定义"
                : "分类 " + definition.nodeKind() + " 未通过 SysUtil 筛选，定义文件 " + definition.preferredXmlPath();
            graph.report(new Graph.Issue(caller, file.toString(), line, "SYS_UTIL_KIND", type + " " + reason));
            return null;
        }
        String typeId = meta.typeId();
        if (typeId.contains(".")) {
            return typeId;
        }
        String preferred = firstNonBlank(imports.get(typeId), simpleToFqn.get(typeId));
        if (preferred != null) {
            return preferred;
        }
        String path = firstNonBlank(meta.packagePath());
        if (path == null) {
            return qualifiedName(pkg, typeId);
        }
        String packagePath = path.trim().replace('/', '.');
        return packagePath.endsWith("." + typeId) || packagePath.equals(typeId)
            ? packagePath : qualifiedName(packagePath, typeId);
    }

    private static boolean isAllowedSysUtilTarget(ServiceTypeFileMeta meta) {
        return meta.nodeKind() != null && SYS_UTIL_KINDS.contains(meta.nodeKind());
    }

    private static boolean isSysUtilFactory(MethodCallExpr call) {
        return SYS_UTIL_METHODS.contains(call.getNameAsString())
            && call.getScope().orElse(null) instanceof NameExpr name && "SysUtil".equals(name.getNameAsString());
    }

    private static String daoName(Expression scope, Map<String, String> fields) {
        if (scope instanceof NameExpr name) {
            String receiver = name.getNameAsString();
            if (isDao(receiver)) {
                return receiver;
            }
            String fieldType = simpleTypeName(fields.get(receiver));
            return isDao(fieldType) ? fieldType : null;
        }
        if (scope instanceof FieldAccessExpr field && isDao(field.getNameAsString())) {
            return field.getNameAsString();
        }
        return null;
    }

    private void report(Call call, String rule, String message) {
        graph.report(new Graph.Issue(call.caller(), call.file().toString(), call.line(), rule, message));
    }

    private String resolve(String name, String pkg, Map<String, String> imports) {
        return name.contains(".") ? name
            : firstNonBlank(imports.get(name), simpleToFqn.get(name), qualifiedName(pkg, name));
    }

    private static Map<String, String> imports(CompilationUnit unit) {
        Map<String, String> names = new HashMap<>();
        for (var declaration : unit.getImports()) {
            if (!declaration.isAsterisk()) {
                String fqn = declaration.getNameAsString();
                names.put(simpleTypeName(fqn), fqn);
            }
        }
        return names;
    }

    private boolean isIncluded(String name) {
        return !name.isBlank() && includedPackages.stream().anyMatch(name::startsWith);
    }

    private static String packageName(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    }

    private static String signature(String owner, MethodDeclaration method) {
        String parameters = method.getParameters().stream()
            .map(p -> eraseGenerics(p.getTypeAsString())).collect(Collectors.joining(","));
        return owner + "#" + method.getNameAsString() + "(" + parameters + ")";
    }

    private static String eraseGenerics(String value) {
        return value.replaceAll("<.*>", "").trim();
    }

    private static String qualifiedName(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static String simpleTypeName(String value) {
        return value == null ? null : value.substring(value.lastIndexOf('.') + 1);
    }

    private static boolean isDao(String value) {
        return value != null && value.matches("\\w+Dao");
    }

    private static String nodeKind(String name, String filePath) {
        if (name.endsWith("ApsImpl") || name.endsWith("ApsSvtp")) return "APS";
        if (name.endsWith("BcsImpl")) return "BCS";
        if (name.endsWith("Dao") || filePath.contains("/namedsql/")) return "DAO";
        if (filePath.contains("/type/") || filePath.contains("/pojo/")) return "POJO";
        return "OTHER";
    }

    private record Hierarchy(String from, String to) {}
    private record Call(Path file, String caller, String kind, String target, String method, int line) {}
}
