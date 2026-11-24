package com.axonlink.chain;

import com.axonlink.service.ServiceNodeCache;
import java.util.*;
import static com.axonlink.chain.ChainResult.fields;
import static com.axonlink.chain.ChainResult.first;

/** 只索引交易链路使用的标签与边。加载结束后作为一个整体发布。 */
final class ChainGraph {
    static final List<String> LABELS = List.of("Transaction", "FlowBlock", "FlowCase", "FlowWhen",
        "FlowMethodStep", "FlowServiceStep", "ServiceType", "ServiceOperation", "Method",
        "Class", "Interface", "DaoMethod", "Table");
    static final Set<String> FLOW = Set.of("HAS_FLOW", "HAS_STEP", "HAS_BRANCH", "EXECUTES");
    static final Set<String> CALL = Set.of("CALLS", "SELF_CALLS", "SYS_UTIL_CALLS", "DAO_CALLS");
    static final Set<String> SEARCHABLE = Set.of("transaction","service","component","method","dao","table");

    static final class Node {
        final String label, id, code;
        final Map<String, Object> props;
        String kind, name, domain, file;
        int line;
        Node(String label, Map<String, Object> props) {
            this.label = label;
            this.props = props;
            String key = switch (label) {
                case "Transaction", "Table", "DaoMethod" -> text("id");
                case "Method" -> text("signature");
                case "Class", "Interface" -> text("fqn");
                default -> text("key");
            };
            if (key == null) throw new IllegalArgumentException("缺少图节点业务键 " + label);
            id = label + ":" + key;
            kind = switch (label) {
                case "ServiceOperation" -> "service";
                case "DaoMethod" -> "dao";
                default -> Character.toLowerCase(label.charAt(0)) + label.substring(1);
            };
            code = switch (label) {
                case "ServiceOperation" -> text("serviceTypeId") + "." + text("serviceId");
                case "Method", "DaoMethod", "Interface", "Class" -> key;
                default -> first(text("id"), key);
            };
            name = first(text("longname"), text("name"), text("simpleName"), code);
            domain = text("domainKey");
            file = first(text("filePath"), text("xmlFilePath"));
            line = number("lineNo");
        }
        String text(String key) { return props.get(key) instanceof String s && !s.isBlank() ? s : null; }
        int number(String key) { return props.get(key) instanceof Number n ? n.intValue() : 0; }
        Map<String, Object> ref() {
            var result = fields("nodeId", id, "kind", kind, "code", code);
            if (!name.equals(code)) result.put("name", name);
            if (domain != null) result.put("domainKey", domain);
            if (file != null) result.put("sourceRef", "def:" + id);
            return result;
        }
        boolean operation() { return "ServiceOperation".equals(label); }
    }

    record Edge(Node from, String type, Node to, int line, String method, Node rawTarget) {
        Edge pointTo(Node target) { return new Edge(from, type, target, line, method, to); }
    }
    private record Member(String owner, String name) {}
    final String revision = UUID.randomUUID().toString();
    final String snapshotAt;
    final Map<String, Node> nodes = new TreeMap<>();
    final Map<Node,ChainFlowOrder> flowOrders = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<Node,List<Edge>> outgoing = new HashMap<>(), incoming = new HashMap<>();
    final Map<Node,List<Edge>> forward = new HashMap<>(), reverse = new HashMap<>();

    ChainGraph(String snapshotAt) { this.snapshotAt = snapshotAt; }
    Node require(String id) {
        Node n = nodes.get(id);
        if (n == null) throw new ChainResult.Problem("NOT_FOUND", "当前图中没有该节点");
        return n;
    }
    List<Edge> edges(Map<Node,List<Edge>> index, Node n) { return index.getOrDefault(n, List.of()); }
    void raw(Edge e) { index(outgoing, e.from, e); index(incoming, e.to, e); }
    private void semantic(Edge e) { index(forward, e.from, e); index(reverse, e.to, e); }
    private static <T> void index(Map<Node,List<T>> map, Node key, T item) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
    }

    void finish(ServiceNodeCache metadata) {
        Map<Member, List<Node>> methods = new HashMap<>();
        Map<Member, List<Node>> interfaces = new HashMap<>();
        Map<Node, List<Node>> implementations = new HashMap<>();
        for (Node n : nodes.values()) {
            if (n.operation()) {
                Node type = edges(incoming,n).stream().filter(e -> e.type.equals("DECLARES_OPERATION"))
                    .map(Edge::from).findFirst().orElse(null);
                if (type != null) { n.file = type.file; n.domain = first(n.domain, type.domain); }
                var cached = metadata.get(n.code);
                String prefix = first(n.text("nodeKind"), type == null ? null : type.text("nodeKind"),
                    cached.map(c -> c.getNodeKind()).orElse(null), "pbs");
                n.kind = prefix.toLowerCase(Locale.ROOT).startsWith("pbc") ? "component" : "service";
                n.domain = first(n.domain, cached.map(c -> c.getDomainKey()).orElse(null));
                if (n.name.equals(n.code)) n.name = first(cached.map(c -> c.getServiceLongname()).orElse(null),n.code);
                String iface = first(n.text("interfaceFqn"), type == null ? null : type.text("interfaceFqn"));
                interfaces.computeIfAbsent(new Member(iface, n.text("methodName")), k -> new ArrayList<>()).add(n);
                for (Edge e : edges(outgoing,n)) if (e.type.equals("IMPLEMENTS_BY")) {
                    index(implementations, e.to, n);
                }
            } else if (n.label.equals("Method")) {
                String owner = n.text("classFqn");
                Node type = nodes.get("Class:" + owner);
                if (type == null) type = nodes.get("Interface:" + owner);
                if (type != null) { n.file = type.file; n.domain = type.domain; }
                methods.computeIfAbsent(new Member(owner, n.text("name")), k -> new ArrayList<>()).add(n);
            } else if (n.text("txId") != null) {
                Node tx = nodes.get("Transaction:" + n.text("txId"));
                if (tx != null) { n.file = tx.file; n.domain = tx.domain; }
            } else if (n.label.equals("DaoMethod")) {
                Node owner = nodes.get("Class:" + n.text("outerClassFqn"));
                if (owner != null) n.file = owner.file;
                Node table = nodes.get("Table:" + n.text("tableId"));
                if (n.file == null && table != null) n.file = table.file;
            }
        }
        for (Node n : nodes.values()) {
            var effective = new LinkedHashSet<Edge>();
            for (Edge e : edges(outgoing,n)) {
                if (FLOW.contains(e.type) || Set.of("CALLS_SERVICE","RESOLVES_TO_METHOD","IMPLEMENTS_BY").contains(e.type)) {
                    effective.add(e);
                } else if (CALL.contains(e.type)) {
                    List<Node> ops = switch (e.to.label) {
                        case "Method" -> {
                            var bound = implementations.getOrDefault(e.to, List.of());
                            yield bound.isEmpty() ? interfaces.getOrDefault(
                                new Member(e.to.text("classFqn"), e.to.text("name")), List.of()) : bound;
                        }
                        case "Interface" -> interfaces.getOrDefault(new Member(e.to.code,e.method), List.of());
                        case "Class" -> methods.getOrDefault(new Member(e.to.code,e.method), List.of()).stream()
                            .flatMap(m -> implementations.getOrDefault(m,List.of()).stream()).distinct().toList();
                        default -> List.of();
                    };
                    if (ops.isEmpty() || n == e.to) effective.add(e);
                    else for (Node op : ops) {
                        // 同一操作内的 super/实现方法调用保留具体被调方法。
                        effective.add(implementations.getOrDefault(n,List.of()).contains(op) ? e : e.pointTo(op));
                    }
                }
            }
            if (n.label.equals("DaoMethod")) {
                for (Edge e : edges(incoming,n)) if (e.type.equals("EXPOSES_DAO"))
                    effective.add(new Edge(n,"TABLE_FOR_DAO",e.from,0,null,null));
                if (effective.isEmpty()) {
                    Node table = nodes.get("Table:" + n.text("tableId"));
                    if (table != null) effective.add(new Edge(n,"TABLE_FOR_DAO",table,0,null,null));
                }
            }
            effective.stream().sorted(ORDER).forEach(this::semantic);
        }
        outgoing.values().forEach(list -> list.sort(ORDER));
        Comparator<Edge> bySource = Comparator.comparing((Edge e) -> e.from.id).thenComparing(ORDER);
        incoming.values().forEach(list -> list.sort(bySource));
        reverse.values().forEach(list -> list.sort(bySource));
    }

    static final Comparator<Edge> ORDER = Comparator
        .comparingInt((Edge e) -> FLOW.contains(e.type) ? e.to.number("order") :
            e.line > 0 ? e.line : Integer.MAX_VALUE)
        .thenComparing(Edge::type).thenComparing(e -> e.to.id)
        .thenComparing(e -> e.rawTarget == null ? "" : e.rawTarget.id)
        .thenComparing(e -> Objects.toString(e.method, ""));
}
