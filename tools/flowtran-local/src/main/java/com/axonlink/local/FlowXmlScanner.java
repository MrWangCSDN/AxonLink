package com.axonlink.local;

import com.axonlink.local.FlowServiceMetadataResolver.ServiceTypeFileMeta;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.axonlink.local.Graph.firstNonBlank;
import static com.axonlink.local.Graph.id;
import static com.axonlink.local.Graph.props;

/** 提取自 FlowtransMetaGraphBuilder 的交易、流程容器和操作定义解析。 */
final class FlowXmlScanner {
    private static final String FLOW_SUFFIX = ".flowtrans.xml";
    private static final Map<String, String> SERVICE_SUFFIXES = Map.of(
        ".serviceType.xml", "service",
        ".pbs.xml", "pbs", ".pcs.xml", "pcs", ".pbcb.xml", "pbcb",
        ".pbcp.xml", "pbcp", ".pbcc.xml", "pbcc", ".pbct.xml", "pbct");

    private final Graph graph;
    private final SourceLayout layout;
    private final FlowServiceMetadataResolver services;
    private final ScanProgress progress;

    FlowXmlScanner(Graph graph, SourceLayout layout, FlowServiceMetadataResolver services, ScanProgress progress) {
        this.graph = graph;
        this.layout = layout;
        this.services = services;
        this.progress = progress;
    }

    void scan(MetadataResourceScanner resources, String workspaceRoots) {
        progress.begin("交易与服务 XML 文件发现");
        List<Path> files = resources.collectMetadataFiles(workspaceRoots, 8, 6, path -> {
            String name = path.getFileName().toString();
            return name.endsWith(FLOW_SUFFIX) || serviceSuffix(name) != null;
        });
        List<Path> serviceFiles = files.stream().filter(file -> !isTransactionFile(file)).toList();
        List<Path> transactionFiles = files.stream().filter(FlowXmlScanner::isTransactionFile).toList();
        progress.finish("serviceFiles=%d transactionFiles=%d".formatted(serviceFiles.size(), transactionFiles.size()));

        progress.begin("服务操作 XML 解析");
        for (int i = 0; i < serviceFiles.size(); i++) {
            Path file = serviceFiles.get(i);
            progress.file(file, i + 1, serviceFiles.size());
            try {
                parseServiceType(file, serviceSuffix(file.getFileName().toString()));
            } catch (Exception e) {
                graph.report(XmlFiles.issue(file, "SERVICE_XML", e));
            }
        }
        progress.finish("serviceTypes=%d serviceOperations=%d"
            .formatted(graph.nodes("ServiceType").size(), graph.nodes("ServiceOperation").size()));

        progress.begin("交易 XML 解析");
        int skippedProjects = 0;
        for (int i = 0; i < transactionFiles.size(); i++) {
            Path file = transactionFiles.get(i);
            progress.file(file, i + 1, transactionFiles.size());
            if ("ccbs-ap".equalsIgnoreCase(layout.parentProject(file))) {
                skippedProjects++;
                progress.skip(file, -1, "父工程 ccbs-ap 跳过交易定义");
                continue;
            }
            try {
                parseTransaction(file);
            } catch (Exception e) {
                graph.report(XmlFiles.issue(file, "FLOW_XML", e));
            }
        }
        progress.finish("transactions=%d flows=%d methodSteps=%d serviceSteps=%d skippedByProject=%d".formatted(
            graph.nodes("Transaction").size(), graph.nodes("FlowBlock").size(), graph.nodes("FlowMethodStep").size(),
            graph.nodes("FlowServiceStep").size(), skippedProjects));
    }

    private void parseServiceType(Path file, String suffix) throws Exception {
        Element root = XmlFiles.read(file).getDocumentElement();
        if (root == null) {
            return;
        }
        String fileName = file.getFileName().toString();
        String typeId = firstNonBlank(attr(root, "id"), fileName.substring(0, fileName.length() - suffix.length()));
        if (typeId == null) {
            return;
        }
        String kind = SERVICE_SUFFIXES.get(suffix);
        String pkg = attr(root, "package");
        String domain = DomainKeyResolver.resolveByProjectOrPackage(layout.parentProject(file), pkg);
        Graph.Node type = serviceType(typeId, attr(root, "longname"), kind, pkg, domain, file.toString());
        for (Element child : children(root)) {
            if (!"service".equals(child.getTagName())) {
                continue;
            }
            String operationId = attr(child, "id");
            if (operationId == null) {
                progress.skip(file, -1, "服务操作缺少 id，类型 " + typeId);
                continue;
            }
            serviceOperation(type, operationId, attr(child, "name"), attr(child, "longname"),
                pkg, kind, file.toString());
        }
    }

    private void parseTransaction(Path file) throws Exception {
        Element root = XmlFiles.read(file).getDocumentElement();
        if (root == null || !"flowtran".equals(root.getTagName())) {
            progress.skip(file, -1, "交易定义需要 flowtran 根元素");
            return;
        }
        String txId = attr(root, "id");
        if (txId == null) {
            graph.report(new Graph.Issue(null, file.toString(), -1, "FLOW_XML", "flowtran 缺少 id"));
            return;
        }
        String pkg = attr(root, "package");
        String parentProject = layout.parentProject(file);
        FlowContext context = new FlowContext(file.toString(), "TX:" + txId, pkg == null ? txId : pkg + "." + txId);
        Graph.Node transaction = graph.put("Transaction", context.txKey(), props(
            "id", txId, "name", firstNonBlank(attr(root, "longname"), txId),
            "flowKind", attr(root, "kind"), "packagePath", pkg, "filePath", context.file(),
            "domainKey", DomainKeyResolver.resolveByProjectOrPackage(parentProject, pkg),
            "parentProject", parentProject, "module", layout.module(file)));
        for (Element child : children(root)) {
            if ("flow".equals(child.getTagName())) {
                Graph.Node flow = putFlowNode("FlowBlock", context.txKey() + ":FLOW", child, context, -1, Map.of());
                graph.link(transaction.id(), "HAS_FLOW", flow.id());
                parseContainer(flow, "HAS_STEP", child, context);
                return;
            }
        }
        progress.skip(file, -1, "交易 " + txId + " 缺少 flow 容器");
    }

    private void parseContainer(Graph.Node parent, String relation, Element container, FlowContext context) {
        Graph.Node previous = null;
        int order = 0;
        for (Element child : children(container)) {
            Graph.Node current;
            switch (child.getTagName()) {
                case "method" -> current = methodStep(parent, child, ++order, context);
                case "service" -> current = serviceStep(parent, child, ++order, context);
                case "case" -> {
                    // 复用生产 case 的交易级键，保留同序号、同 ID 时的合并行为。
                    String key = context.txKey() + ":CASE:" + ++order + ":" + attr(child, "id");
                    current = putFlowNode("FlowCase", key, child, context, order, Map.of());
                    parseBranches(current, child, context);
                }
                default -> {
                    // loop、if 等透明容器复用当前父节点，递归各自计序。
                    parseContainer(parent, relation, child, context);
                    continue;
                }
            }
            graph.link(parent.id(), relation, current.id());
            if (previous != null) {
                graph.link(previous.id(), "NEXT", current.id());
            }
            previous = current;
        }
    }

    private void parseBranches(Graph.Node branch, Element container, FlowContext context) {
        int order = 0;
        for (Element child : children(container)) {
            if ("when".equals(child.getTagName())) {
                String key = branch.key() + ":WHEN:" + ++order + ":" + attr(child, "id");
                Graph.Node when = putFlowNode("FlowWhen", key, child, context, order, Map.of());
                graph.link(branch.id(), "HAS_BRANCH", when.id());
                parseContainer(when, "EXECUTES", child, context);
            }
        }
    }

    private Graph.Node methodStep(Graph.Node parent, Element element, int order, FlowContext context) {
        String methodName = attr(element, "method");
        String key = stepKey(context, parent, "METHOD", order, attr(element, "id"), methodName);
        Graph.Node step = putFlowNode("FlowMethodStep", key, element, context, order,
            props("methodName", methodName, "txClassFqn", context.txClassFqn()));
        List<String> methods = graph.methods(context.txClassFqn(), methodName);
        for (String method : methods) {
            graph.link(step.id(), "RESOLVES_TO_METHOD", method);
        }
        if (methods.isEmpty()) {
            graph.report(new Graph.Issue(step.id(), context.file(), -1, "METHOD_STEP",
                "未找到交易方法 " + context.txClassFqn() + "#" + firstNonBlank(methodName, "（缺少 method 属性）")));
        }
        return step;
    }

    private Graph.Node serviceStep(Graph.Node parent, Element element, int order, FlowContext context) {
        String serviceName = attr(element, "serviceName");
        int dot = serviceName == null ? -1 : serviceName.indexOf('.');
        String typeId = dot < 0 ? serviceName : firstNonBlank(serviceName.substring(0, dot));
        String serviceId = dot < 0 ? attr(element, "id") : firstNonBlank(serviceName.substring(dot + 1));
        String key = stepKey(context, parent, "SERVICE", order, attr(element, "id"), serviceName);
        Graph.Node step = putFlowNode("FlowServiceStep", key, element, context, order, props(
            "serviceName", serviceName, "serviceTypeId", typeId, "serviceId", serviceId));
        if (typeId == null || serviceId == null) {
            graph.report(new Graph.Issue(step.id(), context.file(), -1, "SERVICE_STEP", "服务步骤缺少类型或操作 ID"));
            return step;
        }

        Graph.Node type = graph.get("ServiceType", "SVTP:" + typeId);
        if (type == null) {
            ServiceTypeFileMeta meta = services.findByTypeId(typeId).orElse(null);
            type = serviceType(typeId, null, meta == null ? null : meta.nodeKind(),
                meta == null ? null : meta.packagePath(), meta == null ? "public" : meta.domainKey(),
                meta == null ? null : meta.preferredXmlPath());
        }
        Graph.Node operation = graph.get("ServiceOperation", "SVOP:" + typeId + "." + serviceId);
        if (operation == null) {
            operation = serviceOperation(type, serviceId, null, null, type.text("packagePath"), type.text("nodeKind"), null);
            graph.report(new Graph.Issue(step.id(), context.file(), -1, "SERVICE_OPERATION",
                "缺少服务操作定义，已创建占位 " + typeId + "." + serviceId));
        }
        graph.link(step.id(), "CALLS_SERVICE", operation.id());
        return step;
    }

    private Graph.Node serviceType(String typeId, String name, String kind, String pkg, String domain, String file) {
        String key = "SVTP:" + typeId;
        Graph.Node previous = graph.get("ServiceType", key);
        if (previous != null) {
            return previous;
        }
        ServiceTypeFileMeta meta = services.findByTypeId(typeId).orElse(null);
        return graph.put("ServiceType", key, props(
            "id", typeId, "name", firstNonBlank(name, typeId), "nodeKind", kind,
            "domainKey", firstNonBlank(meta == null ? null : meta.domainKey(), domain),
            "fileNodeKind", meta == null ? null : meta.nodeKind(),
            "packagePath", pkg, "interfaceFqn", interfaceFqn(pkg, typeId), "filePath", file));
    }

    private Graph.Node serviceOperation(Graph.Node type, String serviceId, String methodName, String name,
                                        String pkg, String kind, String file) {
        String typeId = type.text("id");
        String key = "SVOP:" + typeId + "." + serviceId;
        Graph.Node operation = graph.get("ServiceOperation", key);
        if (operation == null) {
            operation = graph.put("ServiceOperation", key, props(
                "name", firstNonBlank(name, serviceId), "serviceTypeId", typeId,
                "serviceId", serviceId, "methodName", methodName,
                "nodeKind", kind, "domainKey", type.text("domainKey"), "filePath", file,
                "interfaceFqn", interfaceFqn(pkg, typeId)));
        }
        graph.link(type.id(), "DECLARES_OPERATION", operation.id());
        return operation;
    }

    private Graph.Node putFlowNode(String label, String key, Element element,
                                   FlowContext context, int order, Map<String, Object> specific) {
        Map<String, Object> properties = props(
            "id", attr(element, "id"), "name", attr(element, "longname"), "desc", attr(element, "desc"),
            "test", attr(element, "test"), "order", order, "filePath", context.file());
        properties.putAll(specific);
        return graph.put(label, key, properties);
    }

    private static String stepKey(FlowContext context, Graph.Node parent, String kind,
                                  int order, String id, String fallback) {
        String identity = firstNonBlank(id, fallback, String.valueOf(order));
        return context.txKey() + ":" + kind + ":" + keySegment(parent.key()) + ":" + order + ":" + keySegment(identity);
    }

    private static String keySegment(String text) {
        return text.replace("%", "%25").replace(":", "%3A");
    }

    private static String interfaceFqn(String pkg, String typeId) {
        return pkg == null ? null : pkg + "." + typeId;
    }

    private static boolean isTransactionFile(Path file) {
        return file.getFileName().toString().endsWith(FLOW_SUFFIX);
    }

    private static String serviceSuffix(String name) {
        return SERVICE_SUFFIXES.keySet().stream().filter(name::endsWith).findFirst().orElse(null);
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String attr(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? null : value.trim();
    }

    private record FlowContext(String file, String txKey, String txClassFqn) {}
}
