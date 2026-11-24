package com.axonlink.local;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 构建与 SQLite 装载使用同一份节点和边模型。发布后仅读取。 */
final class Graph {
    record Node(String id, String kind, String key, Map<String, Object> properties) {
        String text(String property) {
            return Objects.toString(properties.get(property), null);
        }
    }

    record Edge(String from, String to, String type, int line, String methodName) {}

    record Issue(String nodeId, String file, int line, String rule, String message) {}

    private record Member(String owner, String name) {}

    private final ScanProgress progress;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Map<String, Node>> kinds = new LinkedHashMap<>();
    private final Map<Member, List<String>> methods = new HashMap<>();
    private final Set<Edge> edges = new LinkedHashSet<>();
    private final Map<String, List<Edge>> outgoing = new HashMap<>();
    private final Map<String, List<Edge>> incoming = new HashMap<>();
    private final Set<Issue> issues = new LinkedHashSet<>();
    private final Map<String, List<Issue>> issuesByNode = new HashMap<>();

    Graph() {
        this(null);
    }

    Graph(ScanProgress progress) {
        this.progress = progress;
    }

    static String id(String kind, String key) {
        return kind + ":" + key;
    }

    Node put(String kind, String key, Map<String, Object> properties) {
        String id = id(kind, key);
        Node previous = nodes.get(id);
        if (previous != null) {
            Map<String, Object> merged = new LinkedHashMap<>(previous.properties());
            merged.putAll(properties);
            properties = merged;
        }
        Node node = new Node(id, kind, key, Collections.unmodifiableMap(properties));
        nodes.put(node.id(), node);
        kinds.computeIfAbsent(kind, ignored -> new LinkedHashMap<>()).put(key, node);
        if (previous == null && "Method".equals(kind)) {
            Member member = new Member(node.text("classFqn"), node.text("name"));
            methods.computeIfAbsent(member, ignored -> new ArrayList<>()).add(node.id());
        }
        return node;
    }

    Node get(String id) {
        return nodes.get(id);
    }

    Node get(String kind, String key) {
        return get(id(kind, key));
    }

    Collection<Node> nodes() {
        return nodes.values();
    }

    Collection<Node> nodes(String kind) {
        return kinds.getOrDefault(kind, Map.of()).values();
    }

    List<String> methods(String owner, String name) {
        return methods.getOrDefault(new Member(owner, name), List.of());
    }

    void link(String from, String type, String to) {
        link(new Edge(from, to, type, -1, ""));
    }

    void link(Edge edge) {
        Node source = nodes.get(edge.from());
        if (source != null && nodes.containsKey(edge.to()) && edges.add(edge)) {
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge);
            if (progress != null) {
                progress.edge(edge, source);
            }
        }
    }

    Collection<Edge> edges() {
        return edges;
    }

    List<Edge> outgoing(String id) {
        return outgoing.getOrDefault(id, List.of());
    }

    List<Edge> incoming(String id) {
        return incoming.getOrDefault(id, List.of());
    }

    void report(Issue issue) {
        if (!issues.add(issue)) {
            return;
        }
        if (issue.nodeId() != null) {
            issuesByNode.computeIfAbsent(issue.nodeId(), ignored -> new ArrayList<>()).add(issue);
        }
        if (progress != null) {
            progress.issue(issue);
        }
    }

    Collection<Issue> issues() {
        return issues;
    }

    List<Issue> issues(String id) {
        return issuesByNode.getOrDefault(id, List.of());
    }

    Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        kinds.forEach((kind, group) -> counts.put(kind, group.size()));
        return counts;
    }

    static Map<String, Object> props(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                properties.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return properties;
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
