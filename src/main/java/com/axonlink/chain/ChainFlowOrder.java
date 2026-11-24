package com.axonlink.chain;

import com.axonlink.service.FlowChildren;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.*;
import static com.axonlink.chain.ChainGraph.*;

/** 旧图可能从 1 重新计算 loop 内步骤序号，按原交易 XML 核对同级节点。 */
final class ChainFlowOrder {
    private final Map<Node,List<Edge>> overrides = new HashMap<>();
    final ChainResult.Notes diagnostics = new ChainResult.Notes();

    ChainFlowOrder(ChainGraph graph, Node tx, ChainSources source) {
        List<Edge> flows = graph.edges(graph.forward,tx);
        if (flows.isEmpty() || flows.stream().allMatch(e -> e.to().number("orderVersion") > 0)) return;
        try {
            Path path = source.readable(tx.file);
            if (path != null) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");
                factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                var xml = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
                if ("flowtran".equals(xml.getTagName()) && tx.code.equals(xml.getAttribute("id"))) {
                    sort(graph,tx,xml,diagnostics,new HashSet<>());
                    return;
                }
            }
        } catch (Exception ex) {
            // 原文件不可用时仍能读取已建图的关系。
        }
        diagnostics.add("ORDER_UNVERIFIED","旧图的步骤顺序未能与原交易 XML 核对",tx.id);
    }

    private void sort(ChainGraph graph, Node parent, Element xml, ChainResult.Notes notes, Set<Node> seen) {
        if (!seen.add(parent)) return;
        List<Edge> existing = graph.edges(graph.forward,parent);
        List<Element> elements = new ArrayList<>();
        if (parent.kind.equals("transaction") || parent.kind.equals("flowCase")) {
            String tag = parent.kind.equals("transaction") ? "flow" : "when";
            for (var child = xml.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element e && tag.equals(e.getTagName())) elements.add(e);
            }
        } else elements.addAll(FlowChildren.steps(xml));
        List<Edge> remaining = new ArrayList<>(existing);
        List<Edge> ordered = new ArrayList<>();
        for (Element element : elements) {
            String kind = switch (element.getTagName()) {
                case "flow" -> "flowBlock"; case "when" -> "flowWhen"; case "case" -> "flowCase";
                case "method" -> "flowMethodStep"; default -> "flowServiceStep";
            };
            Edge matched = remaining.stream().filter(e -> kind.equals(e.to().kind) &&
                element.getAttribute("id").equals(Objects.toString(e.to().text("id"),"")) &&
                (!kind.equals("flowMethodStep") || Objects.equals(element.getAttribute("method"),Objects.toString(e.to().text("methodName"),""))) &&
                (!kind.equals("flowServiceStep") || Objects.equals(element.getAttribute("serviceName"),Objects.toString(e.to().text("serviceName"),""))))
                .findFirst().orElse(null);
            if (matched == null) { notes.add("ORDER_UNVERIFIED","XML 步骤与图节点不完全一致",parent.id); continue; }
            remaining.remove(matched);
            ordered.add(matched);
            if (Set.of("flowBlock","flowCase","flowWhen").contains(kind)) sort(graph,matched.to(),element,notes,seen);
        }
        if (!remaining.isEmpty()) notes.add("ORDER_UNVERIFIED","部分图节点没有对应 XML 步骤",parent.id);
        ordered.addAll(remaining);
        overrides.put(parent,ordered);
    }

    List<Edge> children(ChainGraph graph, Node n) {
        return overrides.getOrDefault(n, graph.edges(graph.forward,n));
    }
}
