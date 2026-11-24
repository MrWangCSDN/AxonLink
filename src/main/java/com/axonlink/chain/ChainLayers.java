package com.axonlink.chain;

import java.util.*;
import static com.axonlink.chain.ChainGraph.*;
import static com.axonlink.chain.ChainResult.fields;

/** 按业务入口归属调用点。两个构件经过同一 Java helper 时分别保留证据。 */
final class ChainLayers {
    private record Visit(Node node, Node owner, ChainRead.Depth depth) {}
    private record Owned(Node node, Node owner) {}
    private record Link(Node from, Node to, String relation) {}
    private record Access(Node from, Node dao, Node table) {}
    private final ChainRead read;
    private final Node tx;
    private final Map<String,Node> nodes = new TreeMap<>();
    private final Set<Link> links = new LinkedHashSet<>();
    private final Map<Link,Set<Edge>> components = new LinkedHashMap<>();
    private final Map<Access,Set<Edge>> tables = new LinkedHashMap<>();

    ChainLayers(ChainRead read, Node tx) {
        this.read = read;
        this.tx = tx;
    }

    Map<String,Object> view(String view) {
        var queue = new ArrayDeque<Visit>();
        var shortest = new HashMap<Owned,Integer>();
        int visits = 0;
        queue.add(new Visit(tx,tx,ChainRead.Depth.ROOT));
        nodes.put(tx.id,tx);
        while (!queue.isEmpty()) {
            Visit visit = queue.remove();
            var key = new Owned(visit.node,visit.owner);
            int hops = visit.depth.javaHops() + visit.depth.flowHops();
            if (shortest.getOrDefault(key,Integer.MAX_VALUE) <= hops) continue;
            shortest.put(key,hops);
            if (++visits > ChainRead.MAX_VISITS) { read.notes.limit(tx.id); break; }
            var next = read.graph.edges(read.graph.forward,visit.node);
            read.missing(visit.node,next);
            for (Edge e : next) {
                if (queue.size() >= ChainRead.MAX_VISITS) { read.notes.limit(tx.id); break; }
                var depth = visit.depth.advance(e);
                if (!depth.allowed(e)) { read.notes.limit(e.from().id); continue; }
                read.checkCall(e);
                Node owner = visit.owner;
                if (e.to().operation() || e.to().kind.equals("flowMethodStep")) {
                    Node to = e.to();
                    if (owner != to) businessCall(owner,to,e);
                    owner = to;
                }
                if (e.type().equals("DAO_CALLS")) access(owner,e);
                queue.add(new Visit(e.to(),owner,depth));
            }
        }
        var data = fields("view",view,"transaction",tx.ref());
        switch (view) {
            case "components" -> data.put("componentCalls", read.page.slice(new ArrayList<>(components.entrySet())).stream()
                .map(entry -> fields("from",entry.getKey().from.ref(),"to",entry.getKey().to.ref(),
                    "callSites",sites(entry.getValue()))).toList());
            case "tables" -> data.put("tableAccesses", read.page.slice(new ArrayList<>(tables.entrySet())).stream()
                .map(entry -> fields("from",entry.getKey().from.ref(),"dao",entry.getKey().dao.ref(),
                    "table",entry.getKey().table == null ? null : entry.getKey().table.ref(),
                    "callSites",sites(entry.getValue()))).toList());
            default -> {
                data.put("counts",fields("services",count("service") + count("flowMethodStep"),"components",count("component"),
                    "tables",count("table"),"daos",count("dao")));
                data.put("nodes",read.page.slice(new ArrayList<>(nodes.values())).stream().map(Node::ref).toList());
                data.put("relations",read.page.slice(new ArrayList<>(links)).stream().map(l ->
                    fields("from",l.from.ref(),"to",l.to.ref(),"relation",l.relation)).toList());
            }
        }
        return data;
    }

    private void businessCall(Node owner, Node target, Edge raw) {
        String from = owner.kind.equals("component") ? "component" : "service";
        String relation = owner == tx ? "ENTRY" : from + (target.kind.equals("component") ? "ToComponent" : "ToService");
        Link link = link(owner,target,relation);
        if (owner.kind.equals("component") || target.kind.equals("component")) {
            var evidence = components.computeIfAbsent(link,k -> new LinkedHashSet<>());
            if (CALL.contains(raw.type())) remember(evidence,raw,owner);
        }
    }

    private void access(Node owner, Edge call) {
        Node dao = call.to();
        nodes.put(dao.id,dao);
        var mapped = read.graph.edges(read.graph.forward,dao).stream()
            .filter(e -> e.type().equals("TABLE_FOR_DAO")).map(Edge::to).toList();
        if (mapped.isEmpty()) {
            remember(tables.computeIfAbsent(new Access(owner,dao,null),ignored -> new LinkedHashSet<>()),call,owner);
            link(owner,dao,"nodeToDao");
        }
        for (Node table : mapped) {
            remember(tables.computeIfAbsent(new Access(owner,dao,table),ignored -> new LinkedHashSet<>()),call,owner);
            link(owner,table,"nodeToTable");
            link(table,dao,"tableToDao");
        }
    }

    private Link link(Node from, Node to, String relation) {
        nodes.put(from.id,from);
        nodes.put(to.id,to);
        var link = new Link(from,to,relation);
        links.add(link);
        return link;
    }

    private List<Map<String,Object>> sites(Set<Edge> evidence) {
        return evidence.stream().map(read.sources::callSite).filter(Objects::nonNull).distinct().toList();
    }
    private void remember(Set<Edge> evidence, Edge edge, Node owner) {
        if (evidence.size() < 200 || evidence.contains(edge)) evidence.add(edge);
        else {
            read.notes.truncated = true;
            read.notes.add("LIMIT_REACHED","一项聚合关系最多返回 200 个调用点，可按具体节点继续查询",owner.id);
        }
    }
    private long count(String kind) { return nodes.values().stream().filter(n -> n.kind.equals(kind)).count(); }
}
