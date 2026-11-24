package com.axonlink.local;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.axonlink.local.Graph.firstNonBlank;

/** 提取 FlowtranServiceImpl.getChain 的入口归并、边界识别和数据层遍历。 */
final class ChainProjection {
    private static final int MAX_FLOW_DEPTH = 24;
    private static final int MAX_METHOD_DEPTH = 8;
    private static final Set<String> FLOW_TREE_EDGES = Set.of("HAS_FLOW", "HAS_STEP", "HAS_BRANCH", "EXECUTES");
    private static final Set<String> CALL_EDGES = Set.of("CALLS", "SELF_CALLS", "SYS_UTIL_CALLS", "DAO_CALLS");
    private static final Set<String> DATA_WALK_EDGES = Set.of("CALLS", "SELF_CALLS");
    private static final Comparator<Graph.Node> BY_KEY = Comparator.comparing(Graph.Node::key);

    record View(Graph.Node transaction, List<FlowItem> flow, List<LogicalNode> nodes, List<LogicalEdge> edges,
                List<ComponentCall> componentCalls, List<TableAccess> tableAccesses, String snapshotAt) {}
    record FlowItem(Graph.Node node, List<FlowItem> children, boolean repeated, String relation) {}
    record LogicalNode(String id, String kind, String code, String name, String prefix,
                       String domainKey, String rawId, Set<String> entries) {}
    record LogicalEdge(String from, String to, String type) {}
    record ComponentCall(String sourceId, LogicalNode target, Set<Graph.Edge> calls) {}
    record TableAccess(String sourceId, String daoId, String tableId, Set<Graph.Edge> calls) {}
    private record NodePair(String sourceId, String targetId) {}
    private record InterfaceMethod(String owner, String name) {}

    private final Graph graph;
    private final Graph.Node transaction;
    private final Map<InterfaceMethod, Graph.Node> interfaceOperations = new HashMap<>();
    private final Map<String, LogicalNode> nodes = new LinkedHashMap<>();
    private final Set<LogicalEdge> edges = new LinkedHashSet<>();
    private final Map<NodePair, ComponentCall> componentCalls = new LinkedHashMap<>();
    private final Map<NodePair, TableAccess> tableAccesses = new LinkedHashMap<>();
    private final ArrayDeque<LogicalNode> services = new ArrayDeque<>();
    private final ArrayDeque<LogicalNode> components = new ArrayDeque<>();

    ChainProjection(Graph graph, Graph.Node transaction) {
        this.graph = graph;
        this.transaction = transaction;
        for (Graph.Node type : graph.nodes("ServiceType")) {
            for (Graph.Node operation : targets(type.id(), "DECLARES_OPERATION")) {
                InterfaceMethod key = new InterfaceMethod(type.text("interfaceFqn"), operation.text("methodName"));
                interfaceOperations.merge(key, operation, (a, b) -> BY_KEY.compare(a, b) <= 0 ? a : b);
            }
        }
    }

    View read(String snapshotAt) {
        return read(snapshotAt, null);
    }

    // 导出时同时保存本次遍历命中的原始调用边和解析后的目标，保持与页面相同的范围和绑定规则。
    View read(String snapshotAt, Map<Graph.Edge, Graph.Node> exportTargets) {
        LogicalNode root = logical("transaction", transaction.text("id"), transaction.text("name"),
            "flowtran", transaction.text("domainKey"), transaction.id(), List.of());
        nodes.put(root.id(), root);
        List<Graph.Node> flows = targets(transaction.id(), "HAS_FLOW");
        for (Graph.Node step : reachableSteps(flows)) {
            LogicalNode entry = stepEntry(step);
            if (entry != null) {
                addSeed(entry);
                edges.add(new LogicalEdge(root.id(), entry.id(), "ENTRY"));
                if ("component".equals(entry.kind())) {
                    componentCall(root, entry);
                }
            }
        }
        expand(services, true, exportTargets);
        expand(components, false, exportTargets);

        Set<String> expanded = new HashSet<>();
        List<FlowItem> flow = flows.stream().map(node -> flowTree(node, "HAS_FLOW", expanded, 0)).toList();
        return new View(transaction, flow, new ArrayList<>(nodes.values()), new ArrayList<>(edges),
            new ArrayList<>(componentCalls.values()), new ArrayList<>(tableAccesses.values()), snapshotAt);
    }

    private LogicalNode stepEntry(Graph.Node step) {
        if ("FlowMethodStep".equals(step.kind())) {
            String code = firstNonBlank(step.text("id"), step.text("methodName"), step.key());
            return logical("service", code, firstNonBlank(step.text("name"), code), "method",
                transaction.text("domainKey"), step.id(), targetIds(step.id(), "RESOLVES_TO_METHOD"));
        }
        String typeId = step.text("serviceTypeId");
        String serviceId = step.text("serviceId");
        String code = firstNonBlank(step.text("serviceName"), serviceCode(typeId, serviceId), step.text("id"));
        if (code == null) {
            return null;
        }
        Graph.Node type = graph.get("ServiceType", "SVTP:" + typeId);
        String name = firstNonBlank(step.text("name"), step.text("id"), serviceId, code);
        Graph.Node operation = targets(step.id(), "CALLS_SERVICE").stream().min(BY_KEY).orElse(null);
        List<String> entries = operation == null ? List.of() : targetIds(operation.id(), "IMPLEMENTS_BY");
        return serviceNode(code, name, type, step.id(), entries);
    }

    private void addSeed(LogicalNode candidate) {
        LogicalNode existing = nodes.putIfAbsent(candidate.id(), candidate);
        if (existing == null || existing.entries().addAll(candidate.entries())) {
            LogicalNode changed = existing == null ? candidate : existing;
            ("component".equals(changed.kind()) ? components : services).addLast(changed);
        }
    }

    private void expand(ArrayDeque<LogicalNode> queue, boolean expandServices, Map<Graph.Edge, Graph.Node> exportTargets) {
        Map<String, Integer> processed = new HashMap<>();
        while (!queue.isEmpty()) {
            LogicalNode seed = queue.removeFirst();
            if (processed.getOrDefault(seed.id(), 0) >= seed.entries().size()) {
                continue;
            }
            processed.put(seed.id(), seed.entries().size());
            Set<String> frontier = new LinkedHashSet<>(seed.entries());
            Set<String> visited = new HashSet<>(frontier);
            for (int depth = 0; depth < MAX_METHOD_DEPTH && !frontier.isEmpty(); depth++) {
                Set<String> next = new LinkedHashSet<>();
                for (String method : frontier) {
                    for (Graph.Edge edge : graph.outgoing(method)) {
                        if (!CALL_EDGES.contains(edge.type())) {
                            continue;
                        }
                        Graph.Node target = graph.get(edge.to());
                        if ("DaoMethod".equals(target.kind())) {
                            addData(seed, target, edge);
                            continue;
                        }
                        Graph.Node operation = resolveOperation(target, edge.methodName());
                        if (exportTargets != null) {
                            exportTargets.put(edge, operation == null ? target : operation);
                        }
                        if (operation != null) {
                            LogicalNode boundary = operationEntry(operation);
                            boolean targetIsComponent = "component".equals(boundary.kind());
                            if ((targetIsComponent || "component".equals(seed.kind())) && !seed.id().equals(boundary.id())) {
                                componentCall(seed, boundary).calls().add(edge);
                            }
                            if (expandServices || targetIsComponent) {
                                addSeed(boundary);
                                String relation = seed.kind() + "To" + (targetIsComponent ? "Component" : "Service");
                                relate(seed, boundary, relation);
                            }
                        }
                        if ("Method".equals(target.kind()) && visited.add(target.id())) {
                            next.add(target.id());
                        }
                    }
                }
                frontier = next;
            }
            addDataTargets(seed);
        }
    }

    private void addDataTargets(LogicalNode seed) {
        Set<String> frontier = new LinkedHashSet<>(seed.entries());
        Set<String> visited = new HashSet<>(frontier);
        for (int depth = 0; depth <= MAX_METHOD_DEPTH && !frontier.isEmpty(); depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (String method : frontier) {
                for (Graph.Edge edge : graph.outgoing(method)) {
                    Graph.Node target = graph.get(edge.to());
                    if ("DAO_CALLS".equals(edge.type()) && "DaoMethod".equals(target.kind())) {
                        addData(seed, target, edge);
                    } else if (DATA_WALK_EDGES.contains(edge.type()) && "Method".equals(target.kind())
                            && visited.add(target.id())) {
                        next.add(target.id());
                    }
                }
            }
            frontier = next;
        }
    }

    private ComponentCall componentCall(LogicalNode source, LogicalNode target) {
        return componentCalls.computeIfAbsent(new NodePair(source.id(), target.id()),
            ignored -> new ComponentCall(source.id(), target, new LinkedHashSet<>()));
    }

    private void addData(LogicalNode seed, Graph.Node dao, Graph.Edge call) {
        String tableId = dao.text("tableId");
        Graph.Node table = graph.incoming(dao.id()).stream().filter(edge -> "EXPOSES_DAO".equals(edge.type()))
            .map(edge -> graph.get(edge.from())).findFirst()
            .orElseGet(() -> tableId == null ? null : graph.get("Table", tableId));
        // 同一张表可被不同入口通过不同 DAO 方法访问。保留实际调用点和它命中的 DAO。
        TableAccess access = tableAccesses.computeIfAbsent(new NodePair(seed.id(), dao.id()),
            ignored -> new TableAccess(seed.id(), dao.id(), table == null ? null : table.id(), new LinkedHashSet<>()));
        access.calls().add(call);
        if (table == null) {
            return;
        }
        String domain = table.text("domainKey");
        LogicalNode tableNode = logical("table", table.key(), table.text("name"), "table", domain, table.id(), List.of());
        LogicalNode daoNode = logical("dao", dao.key(), dao.text("name"), "dao", domain, dao.id(), List.of());
        nodes.putIfAbsent(tableNode.id(), tableNode);
        nodes.putIfAbsent(daoNode.id(), daoNode);
        relate(seed, tableNode, "nodeToTable");
        relate(tableNode, daoNode, "tableToDao");
    }

    private Graph.Node resolveOperation(Graph.Node target, String methodName) {
        return switch (target.kind()) {
            case "Method" -> operationForMethods(List.of(target.id()));
            case "Class" -> operationForMethods(graph.methods(target.key(), methodName));
            case "Interface" -> interfaceOperations.get(new InterfaceMethod(target.key(), methodName));
            case "ServiceOperation" -> target;
            default -> null;
        };
    }

    private Graph.Node operationForMethods(List<String> methods) {
        return methods.stream().flatMap(method -> graph.incoming(method).stream())
            .filter(edge -> "IMPLEMENTS_BY".equals(edge.type()))
            .map(edge -> graph.get(edge.from())).min(BY_KEY).orElse(null);
    }

    LogicalNode operationEntry(Graph.Node operation) {
        String typeId = operation.text("serviceTypeId");
        String code = serviceCode(typeId, operation.text("serviceId"));
        return serviceNode(code, firstNonBlank(operation.text("name"), code),
            graph.get("ServiceType", "SVTP:" + typeId), operation.id(), targetIds(operation.id(), "IMPLEMENTS_BY"));
    }

    private LogicalNode serviceNode(String code, String name, Graph.Node type, String rawId, List<String> entries) {
        String graphKind = type == null ? null : type.text("nodeKind");
        String fileKind = type == null ? null : type.text("fileNodeKind");
        String group = normalizeKind(firstNonBlank(graphKind, fileKind)).startsWith("pbc") ? "component" : "service";
        String domain = firstNonBlank(type == null ? null : type.text("domainKey"), transaction.text("domainKey"));
        return logical(group, code, name, normalizeKind(firstNonBlank(fileKind, graphKind)), domain, rawId, entries);
    }

    private List<Graph.Node> reachableSteps(List<Graph.Node> flows) {
        List<Graph.Node> steps = new ArrayList<>();
        List<Graph.Node> frontier = flows;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth <= MAX_FLOW_DEPTH && !frontier.isEmpty(); depth++) {
            List<Graph.Node> next = new ArrayList<>();
            for (Graph.Node node : frontier) {
                if (!visited.add(node.id())) {
                    continue;
                }
                if ("FlowMethodStep".equals(node.kind()) || "FlowServiceStep".equals(node.kind())) {
                    steps.add(node);
                }
                graph.outgoing(node.id()).stream()
                    .filter(edge -> FLOW_TREE_EDGES.contains(edge.type()) || "NEXT".equals(edge.type()))
                    .map(edge -> graph.get(edge.to())).forEach(next::add);
            }
            frontier = next;
        }
        return steps.stream().sorted(BY_KEY).toList();
    }

    private FlowItem flowTree(Graph.Node node, String relation, Set<String> expanded, int depth) {
        if (!expanded.add(node.id())) {
            return new FlowItem(node, List.of(), true, relation);
        }
        List<FlowItem> children = depth >= MAX_FLOW_DEPTH ? List.of() : graph.outgoing(node.id()).stream()
            .filter(edge -> FLOW_TREE_EDGES.contains(edge.type()))
            .map(edge -> flowTree(graph.get(edge.to()), edge.type(), expanded, depth + 1)).toList();
        return new FlowItem(node, children, false, relation);
    }

    private List<Graph.Node> targets(String from, String type) {
        return targetIds(from, type).stream().map(graph::get).toList();
    }

    private List<String> targetIds(String from, String type) {
        return graph.outgoing(from).stream().filter(edge -> type.equals(edge.type())).map(Graph.Edge::to).toList();
    }

    private void relate(LogicalNode from, LogicalNode to, String type) {
        if (!from.code().equals(to.code())) {
            edges.add(new LogicalEdge(from.id(), to.id(), type));
        }
    }

    private static LogicalNode logical(String kind, String code, String name, String prefix,
                                       String domain, String rawId, List<String> entries) {
        return new LogicalNode(kind + ":" + code, kind, code, firstNonBlank(name, code),
            prefix, domain, rawId, new LinkedHashSet<>(entries));
    }

    private static String normalizeKind(String kind) {
        return kind == null ? "pbs" : kind.trim().toLowerCase(Locale.ROOT);
    }

    private static String serviceCode(String typeId, String serviceId) {
        return typeId == null || serviceId == null ? null : typeId + "." + serviceId;
    }
}
