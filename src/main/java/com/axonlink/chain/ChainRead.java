package com.axonlink.chain;

import java.util.*;
import static com.axonlink.chain.ChainGraph.*;
import static com.axonlink.chain.ChainResult.*;

final class ChainRead {
    // 业务边界之间的 Java 跳数与 FlowtranServiceImpl 的 MAX_METHOD_DEPTH 保持一致。
    static final int JAVA_HOPS = 8, FLOW_HOPS = 24, MAX_VISITS = 50000;
    final ChainGraph graph;
    final ChainSources sources;
    final ChainPage page;
    final Notes notes = new Notes();

    ChainRead(ChainGraph graph, ChainSources sources, ChainPage page) {
        this.graph = graph;
        this.sources = sources;
        this.page = page;
    }

    Map<String,Object> search(String term, String kind, String domain) {
        if (term.isBlank()) throw invalid("query 不能为空");
        if (!kind.isEmpty() && !SEARCHABLE.contains(kind)) throw invalid("不支持的节点类型");
        String needle = term.toLowerCase(Locale.ROOT);
        var matches = graph.nodes.values().stream().filter(n -> SEARCHABLE.contains(n.kind))
            .filter(n -> kind.isEmpty() || n.kind.equals(kind))
            .filter(n -> domain.isEmpty() || domain.equals(n.domain))
            .filter(n -> n.code.toLowerCase(Locale.ROOT).contains(needle) ||
                n.name.toLowerCase(Locale.ROOT).contains(needle) || n.id.equals(term))
            .sorted(Comparator.comparingInt((Node n) -> n.id.equals(term) || n.code.equalsIgnoreCase(term) ? 0 :
                n.name.equalsIgnoreCase(term) ? 1 : 2).thenComparing(n -> n.id)).toList();
        return fields("items", page.slice(matches).stream().map(Node::ref).toList());
    }

    Map<String,Object> neighbors(String id, String direction, List<String> types) {
        Node n = graph.require(id);
        if (!Set.of("both","incoming","outgoing").contains(direction)) throw invalid("direction 无效");
        var edges = new LinkedHashSet<Edge>();
        if (!direction.equals("incoming")) edges.addAll(graph.edges(graph.outgoing,n));
        if (!direction.equals("outgoing")) edges.addAll(graph.edges(graph.incoming,n));
        var selected = edges.stream().filter(e -> types.isEmpty() || types.contains(e.type())).toList();
        missing(n, graph.edges(graph.forward,n));
        return fields("node",n.ref(),"relations",page.slice(selected).stream().map(this::relation).toList());
    }

    Map<String,Object> chain(String txCode, String view) {
        Node tx = graph.require("Transaction:" + txCode);
        if ("ordered".equals(view)) return ordered(tx);
        if (!Set.of("overview","components","tables").contains(view)) throw invalid("未知交易视图");
        return new ChainLayers(this,tx).view(view);
    }

    Map<String,Object> relation(Edge e) {
        var row = fields("relation",e.type(),"from",e.from().ref(),"to",e.to().ref());
        var call = sources.callSite(e);
        if (call != null) row.put("callSite",call);
        if (e.to().text("test") != null) row.put("condition",e.to().text("test"));
        return row;
    }

    void checkCall(Edge e) {
        if (CALL.contains(e.type()) && (e.to().kind.equals("class") || e.to().kind.equals("interface"))) {
            notes.add("UNRESOLVED_CALL","该调用只定位到 Java 类型，未解析到具体方法或操作",e.to().id);
        }
    }

    String missing(Node n, List<Edge> next) {
        String expected = switch(n.kind) {
            case "transaction" -> "HAS_FLOW";
            case "flowMethodStep" -> "RESOLVES_TO_METHOD";
            case "flowServiceStep" -> "CALLS_SERVICE";
            case "service","component" -> "IMPLEMENTS_BY";
            case "dao" -> "TABLE_FOR_DAO";
            default -> null;
        };
        if (expected == null || next.stream().anyMatch(e -> e.type().equals(expected))) return null;
        notes.add("MISSING_BINDING",n.code + " 缺少 " + expected + " 绑定",n.id);
        return expected;
    }

    record Depth(int javaHops, int flowHops) {
        static final Depth ROOT = new Depth(0,0);
        Depth advance(Edge edge) {
            return new Depth(CALL.contains(edge.type()) ? javaHops + 1 : 0,
                FLOW.contains(edge.type()) ? flowHops + 1 : 0);
        }
        boolean allowed(Edge edge) {
            // 正向第八层方法仍允许读取其 DAO 调用。
            return flowHops <= FLOW_HOPS && (javaHops <= JAVA_HOPS ||
                javaHops == JAVA_HOPS + 1 && edge.type().equals("DAO_CALLS"));
        }
    }
    private record Pending(Edge edge, int parent, Depth depth) {}

    private Map<String,Object> ordered(Node tx) {
        var ordering = graph.flowOrders.computeIfAbsent(tx,node -> new ChainFlowOrder(graph,node,sources));
        for (var diagnostic : ordering.diagnostics.items()) {
            notes.add((String)diagnostic.get("code"),(String)diagnostic.get("message"),(String)diagnostic.get("nodeId"));
        }
        var first = new HashMap<Node,Integer>();
        var rows = new ArrayList<Map<String,Object>>();
        int sequence = 0;
        var stack = new ArrayDeque<Pending>();
        var roots = ordering.children(graph,tx);
        missing(tx,roots);
        push(stack,roots,0,Depth.ROOT);
        while (!stack.isEmpty()) {
            Pending visit = stack.pop();
            Edge edge = visit.edge;
            if (!visit.depth.allowed(edge)) { notes.limit(edge.from().id); continue; }
            if (sequence >= MAX_VISITS) { notes.limit(tx.id); break; }
            checkCall(edge);
            int seq = ++sequence;
            Integer reference = first.putIfAbsent(edge.to(),seq);
            var children = reference == null ? ordering.children(graph,edge.to()) : List.<Edge>of();
            String binding = reference == null ? missing(edge.to(),children) : null;
            if (page.includes(seq - 1)) {
                var row = relation(edge);
                row.put("sequence",seq);
                row.put("parentSequence",visit.parent);
                if (reference != null) row.put("referenceSequence",reference);
                if (binding != null) row.put("missingBinding",binding);
                rows.add(row);
            }
            if (reference == null) push(stack,children,seq,visit.depth);
        }
        return fields("view","ordered","transaction",tx.ref(),"orderMode","static-depth-first","relations",rows);
    }

    private void push(Deque<Pending> stack, List<Edge> edges, int parent, Depth depth) {
        int count = Math.min(edges.size(), MAX_VISITS - stack.size());
        if (count < edges.size()) notes.limit(edges.get(0).from().id);
        for (int i = count - 1; i >= 0; i--) {
            Edge edge = edges.get(i);
            stack.push(new Pending(edge,parent,depth.advance(edge)));
        }
    }

    // 反向 BFS 保存每个真实 txId 的见证链；按节点和连续调用深度避免环与错误剪枝。
    private record Trail(Edge edge, Trail tail) {}
    private record ReverseVisit(Node node, Trail trail, int javaHops, int flowHops, boolean terminalDao) {}
    private record ReverseKey(Node node, boolean terminalDao) {}

    Map<String,Object> impact(String id, String domain) {
        Node target = graph.require(id);
        if (!Set.of("service","component","method","dao","table").contains(target.kind))
            throw invalid("影响反查支持服务、构件、方法、DAO 或表");
        var queue = new ArrayDeque<ReverseVisit>();
        var shortest = new HashMap<ReverseKey,Integer>();
        int visits = 0;
        var found = new TreeMap<String,ReverseVisit>();
        queue.add(new ReverseVisit(target,null,0,0,false));
        while (!queue.isEmpty()) {
            var visit = queue.remove();
            var key = new ReverseKey(visit.node,visit.terminalDao);
            int hops = visit.javaHops + visit.flowHops;
            if (shortest.getOrDefault(key,Integer.MAX_VALUE) <= hops) continue;
            shortest.put(key,hops);
            if (++visits > MAX_VISITS) { notes.limit(target.id); break; }
            if (visit.node.kind.equals("transaction")) {
                if (domain.isEmpty() || domain.equals(visit.node.domain)) found.putIfAbsent(visit.node.code,visit);
                continue;
            }
            List<Edge> parents = graph.edges(graph.reverse,visit.node);
            if (visit.trail == null && target.kind.equals("method")) {
                var includingRaw = new LinkedHashSet<>(parents);
                graph.edges(graph.incoming,target).stream().filter(e -> CALL.contains(e.type())).forEach(includingRaw::add);
                parents = List.copyOf(includingRaw);
            }
            for (Edge edge : parents) {
                if (queue.size() >= MAX_VISITS) { notes.limit(target.id); break; }
                boolean call = CALL.contains(edge.type());
                int javaHops = call ? visit.javaHops + 1 : 0;
                boolean terminalDao = call && (visit.terminalDao || edge.type().equals("DAO_CALLS"));
                int flowHops = FLOW.contains(edge.type()) ? visit.flowHops + 1 : 0;
                if (javaHops > JAVA_HOPS + (terminalDao ? 1 : 0) || flowHops > FLOW_HOPS) {
                    notes.limit(edge.from().id);
                    continue;
                }
                queue.add(new ReverseVisit(edge.from(),new Trail(edge,visit.trail),javaHops,flowHops,terminalDao));
            }
        }
        List<Map<String,Object>> items = new ArrayList<>();
        for (ReverseVisit visit : page.slice(new ArrayList<>(found.values()))) {
            var path = new ArrayList<Map<String,Object>>();
            for (Trail t = visit.trail; t != null; t = t.tail) path.add(relation(t.edge));
            items.add(fields("txId",visit.node.code,"name",visit.node.name,"domainKey",visit.node.domain,"evidencePath",path));
        }
        return fields("target",target.ref(),"items",items);
    }

    static Problem invalid(String message) { return new Problem("INVALID_ARGUMENT",message); }
}
