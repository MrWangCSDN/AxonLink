package com.axonlink.service;

import org.w3c.dom.Element;
import java.util.ArrayList;
import java.util.List;

/** 同一个编排父节点下，透明容器内外的步骤共享 XML 兄弟顺序。 */
public final class FlowChildren {
    private FlowChildren() {}
    public static List<Element> steps(Element parent) {
        List<Element> children = new ArrayList<>();
        collect(parent, children);
        return children;
    }
    private static void collect(Element parent, List<Element> children) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element element)) continue;
            switch (element.getTagName()) {
                case "method", "service", "case" -> children.add(element);
                default -> collect(element, children);
            }
        }
    }
}
