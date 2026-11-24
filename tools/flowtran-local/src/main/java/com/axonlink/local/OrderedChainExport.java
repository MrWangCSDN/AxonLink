package com.axonlink.local;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.axonlink.local.Graph.firstNonBlank;

/** 按 XML 文档顺序和 Java 调用行展开一笔交易，保留重复引用的位置。 */
final class OrderedChainExport {
    record Document(String orderMode, String snapshotAt, Endpoint transaction, String xmlFile, List<Relation> relations) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Endpoint(String kind, String code, String name, String id) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CallSite(String file, Integer line, String rawTarget, String methodName) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Relation(int sequence, int parentSequence, String relation, Endpoint from, Endpoint to,
                    String condition, CallSite callSite, Integer referenceSequence,
                    String missingBinding, List<Graph.Issue> diagnostics) {}

    private record Hop(String relation, Graph.Node target, Graph.Edge call, ChainProjection.FlowItem flow) {}
    private record Visit(Graph.Node source, Hop hop, int parentSequence) {}

    private static final Comparator<Graph.Edge> CALL_ORDER = Comparator
        .comparingInt((Graph.Edge edge) -> edge.line() > 0 ? edge.line() : Integer.MAX_VALUE)
        .thenComparing(Graph.Edge::type).thenComparing(Graph.Edge::to).thenComparing(Graph.Edge::methodName);

    private final Graph graph;
    private final ChainProjection projection;
    private final Map<Graph.Edge, Graph.Node> callTargets = new HashMap<>();
    private final Map<String, Graph.Node> tableByDao = new HashMap<>();
    private final Map<String, Endpoint> endpoints = new HashMap<>();

    OrderedChainExport(Graph graph, Graph.Node transaction) {
        this.graph = graph;
        this.projection = new ChainProjection(graph, transaction);
    }

    Document read(String snapshotAt) {
        ChainProjection.View view = projection.read(snapshotAt, callTargets);
        for (var access : view.tableAccesses()) {
            Graph.Node dao = graph.get(access.daoId());
            // 包含数据层在第八跳 Java 调用之后额外找到的 DAO_CALLS。
            for (Graph.Edge call : access.calls()) {
                callTargets.put(call, dao);
            }
            if (access.tableId() != null) {
                tableByDao.put(dao.id(), graph.get(access.tableId()));
            }
        }

        Graph.Node tx = view.transaction();
        List<Relation> relations = new ArrayList<>();
        Map<String, Integer> firstVisit = new HashMap<>();
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        push(pending, tx, flowHops(view.flow()), 0);
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            Hop hop = visit.hop();
            Graph.Node target = hop.target();
            int sequence = relations.size() + 1;
            Integer reference = firstVisit.putIfAbsent(target.id(), sequence);
            List<Hop> children = reference == null ? children(hop) : List.of();
            List<Graph.Issue> issues = reference == null ? graph.issues(target.id()) : List.of();
            relations.add(new Relation(sequence, visit.parentSequence(), hop.relation(), endpoint(visit.source()),
                endpoint(target), target.text("test"), callSite(visit), reference,
                reference == null && children.isEmpty() ? missingBinding(target) : null,
                issues.isEmpty() ? null : issues));
            push(pending, target, children, sequence);
        }
        return new Document("static-depth-first", snapshotAt, endpoint(tx), tx.text("filePath"), relations);
    }

    private List<Hop> children(Hop hop) {
        Graph.Node node = hop.target();
        if (hop.flow() != null && !hop.flow().children().isEmpty()) {
            return flowHops(hop.flow().children());
        }
        return switch (node.kind()) {
            case "FlowMethodStep" -> bindings(node, "RESOLVES_TO_METHOD");
            case "FlowServiceStep" -> bindings(node, "CALLS_SERVICE");
            case "ServiceOperation" -> bindings(node, "IMPLEMENTS_BY");
            case "Method" -> graph.outgoing(node.id()).stream().filter(callTargets::containsKey)
                .sorted(CALL_ORDER).map(edge -> new Hop(edge.type(), callTargets.get(edge), edge, null)).toList();
            case "DaoMethod" -> {
                Graph.Node table = tableByDao.get(node.id());
                yield table == null ? List.of() : List.of(new Hop("TABLE_FOR_DAO", table, null, null));
            }
            default -> List.of();
        };
    }

    private List<Hop> bindings(Graph.Node node, String type) {
        return graph.outgoing(node.id()).stream().filter(edge -> type.equals(edge.type()))
            .sorted(Comparator.comparing(Graph.Edge::to))
            .map(edge -> new Hop(type, graph.get(edge.to()), null, null)).toList();
    }

    private static List<Hop> flowHops(List<ChainProjection.FlowItem> items) {
        return items.stream().map(item -> new Hop(item.relation(), item.node(), null, item)).toList();
    }

    private static void push(ArrayDeque<Visit> pending, Graph.Node source, List<Hop> children, int parent) {
        for (int i = children.size() - 1; i >= 0; i--) {
            pending.push(new Visit(source, children.get(i), parent));
        }
    }

    private Endpoint endpoint(Graph.Node node) {
        return endpoints.computeIfAbsent(node.id(), ignored -> {
            if ("ServiceOperation".equals(node.kind())) {
                var operation = projection.operationEntry(node);
                return new Endpoint(operation.kind(), operation.code(), operation.name(), node.id());
            }
            String code = switch (node.kind()) {
                case "FlowMethodStep" -> firstNonBlank(node.text("id"), node.text("methodName"), node.key());
                case "FlowServiceStep" -> firstNonBlank(node.text("id"), node.text("serviceName"), node.key());
                default -> firstNonBlank(node.text("id"), node.key());
            };
            String name = node.text("name");
            return new Endpoint(node.kind(), code, code.equals(name) ? null : name, node.id());
        });
    }

    private static CallSite callSite(Visit visit) {
        Graph.Edge edge = visit.hop().call();
        return edge == null ? null : new CallSite(visit.source().text("filePath"),
            edge.line() > 0 ? edge.line() : null, edge.to(), firstNonBlank(edge.methodName()));
    }

    private static String missingBinding(Graph.Node node) {
        return switch (node.kind()) {
            case "FlowMethodStep" -> "RESOLVES_TO_METHOD";
            case "FlowServiceStep" -> "CALLS_SERVICE";
            case "ServiceOperation" -> "IMPLEMENTS_BY";
            case "DaoMethod" -> "TABLE_FOR_DAO";
            default -> null;
        };
    }
}
