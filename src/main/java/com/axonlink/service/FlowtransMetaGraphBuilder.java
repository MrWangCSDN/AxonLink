package com.axonlink.service;

import com.axonlink.common.DomainKeyResolver;
import com.axonlink.config.Neo4jConfig;
import com.axonlink.dto.NodeCacheEntry;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 瑙ｆ瀽 sunline 宸ョ▼涓殑 flowtrans.xml / serviceType.xml锛屽苟琛ュ厖鍐欏叆 Neo4j銆? *
 * <p>绗竴鐗堜弗鏍煎洿缁曠敤鎴锋寚瀹氱殑瑙勫垯锛? * <ul>
 *   <li>鍙В鏋?{@code *.flowtrans.xml}</li>
 *   <li>{@code method} 姝ラ鏄犲皠鍒?{@code package + "." + txId} 浜ゆ槗绫荤殑鏂规硶</li>
 *   <li>{@code serviceName=serviceTypeId.serviceId} 鏄犲皠鍒?{@code *.serviceType.xml}</li>
 *   <li>鏀寔 {@code flow / case / when / service / method / mapping}</li>
 * </ul>
 */
@Service
public class FlowtransMetaGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(FlowtransMetaGraphBuilder.class);
    private static final int BATCH_SIZE = 300;
    private static final ErrorHandler SILENT_XML_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };
    private static final Map<String, String> PARENT_PROJECT_DOMAIN_MAP = Map.of(
        "ap-parent", "ap",
        "unvr-parent", "unvr",
        "stmt-parent", "stmt",
        "medu-parent", "medu",
        "inbu-parent", "inbu",
        "dept-parent", "dept",
        "aggr-parent", "aggr"
    );

    private final Driver driver;
    private final Neo4jConfig neo4jConfig;
    private final ServiceNodeCache serviceNodeCache;

    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    private final List<Map<String, Object>> transactionNodes = new ArrayList<>();
    private final List<Map<String, Object>> flowNodes = new ArrayList<>();
    private final List<Map<String, Object>> sectionNodes = new ArrayList<>();
    private final List<Map<String, Object>> fieldGroupNodes = new ArrayList<>();
    private final List<Map<String, Object>> fieldNodes = new ArrayList<>();
    private final List<Map<String, Object>> methodStepNodes = new ArrayList<>();
    private final List<Map<String, Object>> serviceStepNodes = new ArrayList<>();
    private final List<Map<String, Object>> caseNodes = new ArrayList<>();
    private final List<Map<String, Object>> whenNodes = new ArrayList<>();
    private final List<Map<String, Object>> mappingGroupNodes = new ArrayList<>();
    private final List<Map<String, Object>> mappingNodes = new ArrayList<>();
    private final List<Map<String, Object>> serviceTypeNodes = new ArrayList<>();
    private final List<Map<String, Object>> serviceOperationNodes = new ArrayList<>();

    private final List<Map<String, Object>> graphEdges = new ArrayList<>();
    private final List<Map<String, Object>> methodResolveEdges = new ArrayList<>();
    private final Set<String> seenServiceTypes = new LinkedHashSet<>();
    private final Set<String> seenServiceOperations = new LinkedHashSet<>();

    public FlowtransMetaGraphBuilder(Driver driver, Neo4jConfig neo4jConfig, ServiceNodeCache serviceNodeCache) {
        this.driver = driver;
        this.neo4jConfig = neo4jConfig;
        this.serviceNodeCache = serviceNodeCache;
    }

    public Map<String, Object> importMetadata() {
        if (!neo4jConfig.isEnabled() || driver == null) {
            return Map.of("skipped", true, "reason", "neo4j.disabled");
        }

        clearBuffers();

        List<Path> flowtransFiles = collectFiles(".flowtrans.xml");
        List<Path> serviceTypeFiles = new ArrayList<>();
        serviceTypeFiles.addAll(collectFiles(".serviceType.xml"));
        serviceTypeFiles.addAll(collectFiles(".pbs.xml"));
        serviceTypeFiles.addAll(collectFiles(".pcs.xml"));
        serviceTypeFiles.addAll(collectFiles(".pbcb.xml"));
        serviceTypeFiles.addAll(collectFiles(".pbcp.xml"));
        serviceTypeFiles.addAll(collectFiles(".pbcc.xml"));
        serviceTypeFiles.addAll(collectFiles(".pbct.xml"));
        serviceTypeFiles = serviceTypeFiles.stream().distinct().sorted().collect(Collectors.toList());
        log.info("[FlowtransMeta] 扫描到 flowtrans.xml={} serviceMeta.xml={}",
                 flowtransFiles.size(), serviceTypeFiles.size());

        Map<String, ServiceTypeMeta> serviceTypes = parseServiceTypes(serviceTypeFiles);
        parseFlowtrans(flowtransFiles, serviceTypes);

        try (Session session = driver.session()) {
            ensureIndexes(session);
            writeNodes(session, "Transaction", transactionNodes);
            writeNodes(session, "FlowBlock", flowNodes);
            writeNodes(session, "FlowSection", sectionNodes);
            writeNodes(session, "FlowFieldGroup", fieldGroupNodes);
            writeNodes(session, "FlowField", fieldNodes);
            writeNodes(session, "FlowMethodStep", methodStepNodes);
            writeNodes(session, "FlowServiceStep", serviceStepNodes);
            writeNodes(session, "FlowCase", caseNodes);
            writeNodes(session, "FlowWhen", whenNodes);
            writeNodes(session, "FlowMappingGroup", mappingGroupNodes);
            writeNodes(session, "FlowMapping", mappingNodes);
            writeNodes(session, "ServiceType", serviceTypeNodes);
            writeNodes(session, "ServiceOperation", serviceOperationNodes);

            writeTypedEdges(session, "HAS_FLOW");
            writeTypedEdges(session, "HAS_SECTION");
            writeTypedEdges(session, "HAS_FIELD");
            writeTypedEdges(session, "HAS_FIELD_GROUP");
            writeTypedEdges(session, "HAS_STEP");
            writeTypedEdges(session, "EXECUTES");
            writeTypedEdges(session, "HAS_BRANCH");
            writeTypedEdges(session, "NEXT");
            writeTypedEdges(session, "HAS_MAPPING_GROUP");
            writeTypedEdges(session, "HAS_MAPPING");
            writeTypedEdges(session, "SOURCE_OF");
            writeTypedEdges(session, "TARGETS");
            writeTypedEdges(session, "CALLS_SERVICE");
            writeTypedEdges(session, "DECLARES_OPERATION");

            writeMethodResolveEdges(session);
            writeServiceImplementationEdges(session);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("transactions", transactionNodes.size());
        stats.put("serviceTypes", serviceTypeNodes.size());
        stats.put("serviceOperations", serviceOperationNodes.size());
        stats.put("flowMethodSteps", methodStepNodes.size());
        stats.put("flowServiceSteps", serviceStepNodes.size());
        stats.put("flowCases", caseNodes.size());
        stats.put("flowWhens", whenNodes.size());
        stats.put("flowMappings", mappingNodes.size());
        return stats;
    }

    public Map<String, Object> queryTransactionGraph(String txId, int depth) {
        if (!neo4jConfig.isEnabled() || driver == null) {
            return Map.of("error", "Neo4j 不可用");
        }

        int hops = Math.min(Math.max(depth, 1), 18);
        String cypher =
            "MATCH p = (tx:Transaction {id: $txId})-[:HAS_FLOW|HAS_STEP|EXECUTES|HAS_BRANCH|NEXT|CALLS_SERVICE|RESOLVES_TO_METHOD|IMPLEMENTS_BY|CALLS|SYS_UTIL_CALLS|SELF_CALLS|DAO_CALLS*1.." + hops + "]->(target) " +
            "RETURN [n IN nodes(p) | {" +
            "  label: labels(n)[0], " +
            "  key: coalesce(n.key, n.signature, n.fqn, n.id, n.name), " +
            "  name: coalesce(n.longname, n.name, n.simpleName, n.id), " +
            "  code: coalesce(n.id, n.methodName, n.serviceName, n.simpleName, '')" +
            "}] AS nodes, " +
            "[r IN relationships(p) | type(r)] AS rels " +
            "LIMIT 200";

        try (Session session = driver.session()) {
            List<Map<String, Object>> paths = new ArrayList<>();
            session.run(cypher, Values.parameters("txId", txId)).forEachRemaining(record -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nodes", record.get("nodes").asList());
                row.put("rels", record.get("rels").asList());
                paths.add(row);
            });
            return Map.of("txId", txId, "depth", depth, "paths", paths);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private void parseFlowtrans(List<Path> files, Map<String, ServiceTypeMeta> serviceTypes) {
        for (Path file : files) {
            try {
                Document doc = parseXml(file);
                Element root = doc.getDocumentElement();
                if (root == null || !"flowtran".equals(root.getTagName())) {
                    continue;
                }

                String txId = attr(root, "id");
                if (isBlank(txId)) {
                    continue;
                }

                String txKey = txKey(txId);
                String pkg = attr(root, "package");
                String txClassFqn = isBlank(pkg) ? txId : pkg + "." + txId;
                String filePath = file.toString().replace('\\', '/');
                String parentProject = detectParentProject(file);

                transactionNodes.add(node(
                    "key", txKey,
                    "id", txId,
                    "longname", attr(root, "longname"),
                    "kind", attr(root, "kind"),
                    "packagePath", pkg,
                    "domainKey", resolveTransactionDomainKey(parentProject, pkg),
                    "filePath", filePath,
                    "parentProject", parentProject,
                    "module", detectModule(file)
                ));

                Map<String, List<String>> fieldIndex = new LinkedHashMap<>();

                Element interfaceEl = firstChild(root, "interface");
                if (interfaceEl != null) {
                    parseSections(txKey, txId, interfaceEl, fieldIndex);
                }

                Element flowEl = firstChild(root, "flow");
                if (flowEl != null) {
                    String flowKey = txKey + ":FLOW";
                    flowNodes.add(node(
                        "key", flowKey,
                        "txId", txId,
                        "id", attr(flowEl, "id"),
                        "longname", attr(flowEl, "longname"),
                        "desc", attr(flowEl, "desc"),
                        "test", attr(flowEl, "test")
                    ));
                    graphEdges.add(edge("HAS_FLOW", txKey, flowKey));
                    parseContainer(flowKey, "HAS_STEP", txId, txClassFqn, flowEl, fieldIndex, serviceTypes);
                }

                parseGlobalMappings(txKey, txId, root, fieldIndex);
            } catch (Exception e) {
                log.warn("[FlowtransMeta] 瑙ｆ瀽 flowtrans 澶辫触: {} - {}", file.getFileName(), e.getMessage());
            }
        }
    }

    private void parseSections(String txKey, String txId, Element interfaceEl, Map<String, List<String>> fieldIndex) {
        String interfacePackage = attr(interfaceEl, "package");
        for (String sectionName : List.of("input", "output", "property", "printer")) {
            Element sectionEl = firstChild(interfaceEl, sectionName);
            if (sectionEl == null) {
                continue;
            }

            String sectionKey = txKey + ":SECTION:" + sectionName;
            sectionNodes.add(node(
                "key", sectionKey,
                "txId", txId,
                "sectionType", sectionName,
                "interfacePackage", interfacePackage,
                "packMode", attr(sectionEl, "packMode"),
                "asParm", attr(sectionEl, "asParm")
            ));
            graphEdges.add(edge("HAS_SECTION", txKey, sectionKey));

            for (Element child : childElements(sectionEl)) {
                if ("field".equals(child.getTagName())) {
                    String fieldKey = sectionKey + ":FIELD:" + attr(child, "id");
                    addFieldNode(fieldKey, txId, sectionName, null, child);
                    graphEdges.add(edge("HAS_FIELD", sectionKey, fieldKey));
                    indexField(fieldIndex, attr(child, "id"), fieldKey);
                } else if ("fields".equals(child.getTagName())) {
                    String groupKey = sectionKey + ":GROUP:" + attr(child, "id");
                    fieldGroupNodes.add(node(
                        "key", groupKey,
                        "txId", txId,
                        "sectionType", sectionName,
                        "id", attr(child, "id"),
                        "scope", attr(child, "scope"),
                        "required", attr(child, "required"),
                        "multi", attr(child, "multi"),
                        "array", attr(child, "array"),
                        "longname", attr(child, "longname"),
                        "desc", attr(child, "desc")
                    ));
                    graphEdges.add(edge("HAS_FIELD_GROUP", sectionKey, groupKey));

                    for (Element nested : childElements(child, "field")) {
                        String fieldKey = groupKey + ":FIELD:" + attr(nested, "id");
                        addFieldNode(fieldKey, txId, sectionName, groupKey, nested);
                        graphEdges.add(edge("HAS_FIELD", groupKey, fieldKey));
                        indexField(fieldIndex, attr(nested, "id"), fieldKey);
                    }
                }
            }
        }
    }

    private void parseContainer(String parentKey,
                                String relationType,
                                String txId,
                                String txClassFqn,
                                Element container,
                                Map<String, List<String>> fieldIndex,
                                Map<String, ServiceTypeMeta> serviceTypes) {
        String previousKey = null;
        int order = 0;

        for (Element child : childElements(container)) {
            String tag = child.getTagName();
            String currentKey = null;

            if ("method".equals(tag)) {
                order++;
                currentKey = txKey(txId) + ":METHOD:" + order + ":" + attr(child, "id");
                methodStepNodes.add(node(
                    "key", currentKey,
                    "txId", txId,
                    "id", attr(child, "id"),
                    "methodName", attr(child, "method"),
                    "longname", attr(child, "longname"),
                    "desc", attr(child, "desc"),
                    "test", attr(child, "test"),
                    "order", order,
                    "txClassFqn", txClassFqn
                ));
                graphEdges.add(edge(relationType, parentKey, currentKey));
                methodResolveEdges.add(node(
                    "stepKey", currentKey,
                    "classFqn", txClassFqn,
                    "methodName", attr(child, "method")
                ));
            } else if ("service".equals(tag)) {
                order++;
                String serviceName = attr(child, "serviceName");
                String serviceTypeId = splitLeft(serviceName);
                String serviceId = splitRight(serviceName, attr(child, "id"));
                String operationKey = serviceOperationKey(serviceTypeId, serviceId);
                currentKey = txKey(txId) + ":SERVICE:" + order + ":" + attr(child, "id");

                serviceStepNodes.add(node(
                    "key", currentKey,
                    "txId", txId,
                    "id", attr(child, "id"),
                    "serviceName", serviceName,
                    "serviceTypeId", serviceTypeId,
                    "serviceId", serviceId,
                    "longname", attr(child, "longname"),
                    "desc", attr(child, "desc"),
                    "test", attr(child, "test"),
                    "mappingToProperty", attr(child, "mappingToProperty"),
                    "order", order,
                    "operationKey", operationKey
                ));
                graphEdges.add(edge(relationType, parentKey, currentKey));
                graphEdges.add(edge("CALLS_SERVICE", currentKey, operationKey));
                ensureServiceTypeStub(serviceTypeId, serviceTypes);
                ensureServiceOperationStub(serviceTypeId, serviceId, serviceTypes);
                parseStepMappings(currentKey, txId, child, fieldIndex);
            } else if ("case".equals(tag)) {
                order++;
                currentKey = txKey(txId) + ":CASE:" + order + ":" + attr(child, "id");
                caseNodes.add(node(
                    "key", currentKey,
                    "txId", txId,
                    "id", attr(child, "id"),
                    "longname", attr(child, "longname"),
                    "desc", attr(child, "desc"),
                    "test", attr(child, "test"),
                    "order", order
                ));
                graphEdges.add(edge(relationType, parentKey, currentKey));

                int branchOrder = 0;
                for (Element whenEl : childElements(child, "when")) {
                    branchOrder++;
                    String whenKey = currentKey + ":WHEN:" + branchOrder + ":" + attr(whenEl, "id");
                    whenNodes.add(node(
                        "key", whenKey,
                        "txId", txId,
                        "id", attr(whenEl, "id"),
                        "longname", attr(whenEl, "longname"),
                        "desc", attr(whenEl, "desc"),
                        "test", attr(whenEl, "test"),
                        "order", branchOrder
                    ));
                    graphEdges.add(edge("HAS_BRANCH", currentKey, whenKey));
                    parseContainer(whenKey, "EXECUTES", txId, txClassFqn, whenEl, fieldIndex, serviceTypes);
                }
            }

            if (currentKey != null && previousKey != null) {
                graphEdges.add(edge("NEXT", previousKey, currentKey));
            }
            if (currentKey != null) {
                previousKey = currentKey;
            }
        }
    }

    private void parseStepMappings(String ownerKey, String txId, Element stepEl, Map<String, List<String>> fieldIndex) {
        Element inMappings = firstChild(stepEl, "in_mappings");
        if (inMappings != null) {
            parseMappings(ownerKey, txId, "in", inMappings, fieldIndex);
        }
        Element outMappings = firstChild(stepEl, "out_mappings");
        if (outMappings != null) {
            parseMappings(ownerKey, txId, "out", outMappings, fieldIndex);
        }
    }

    private void parseGlobalMappings(String txKey, String txId, Element root, Map<String, List<String>> fieldIndex) {
        for (String groupType : List.of("outMapping", "propertyToPrinterMapping", "outToPrinterMapping")) {
            Element groupEl = firstChild(root, groupType);
            if (groupEl == null || childElements(groupEl, "mapping").isEmpty()) {
                continue;
            }

            String groupKey = txKey + ":MAPGROUP:" + groupType;
            mappingGroupNodes.add(node(
                "key", groupKey,
                "txId", txId,
                "groupType", groupType
            ));
            graphEdges.add(edge("HAS_MAPPING_GROUP", txKey, groupKey));
            parseMappings(groupKey, txId, groupType, groupEl, fieldIndex);
        }
    }

    private void parseMappings(String ownerKey,
                               String txId,
                               String mappingType,
                               Element mappingsEl,
                               Map<String, List<String>> fieldIndex) {
        int index = 0;
        for (Element mappingEl : childElements(mappingsEl, "mapping")) {
            index++;
            String mappingKey = ownerKey + ":MAP:" + mappingType + ":" + index;
            String src = attr(mappingEl, "src");
            String dest = attr(mappingEl, "dest");
            mappingNodes.add(node(
                "key", mappingKey,
                "txId", txId,
                "mappingType", mappingType,
                "src", src,
                "dest", dest,
                "byInterface", attr(mappingEl, "by_interface"),
                "onTop", attr(mappingEl, "on_top"),
                "desc", attr(mappingEl, "desc")
            ));
            graphEdges.add(edge("HAS_MAPPING", ownerKey, mappingKey));

            for (String srcFieldKey : fieldIndex.getOrDefault(src, List.of())) {
                graphEdges.add(edge("SOURCE_OF", srcFieldKey, mappingKey));
            }
            for (String targetFieldKey : fieldIndex.getOrDefault(dest, List.of())) {
                graphEdges.add(edge("TARGETS", mappingKey, targetFieldKey));
            }
        }
    }

    private Map<String, ServiceTypeMeta> parseServiceTypes(List<Path> files) {
        Map<String, ServiceTypeMeta> result = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                Document doc = parseXml(file);
                Element root = doc.getDocumentElement();
                if (root == null) {
                    continue;
                }

                String fileName = file.getFileName().toString();
                String serviceTypeId = firstNonBlank(attr(root, "id"), stripServiceMetaSuffix(fileName));
                if (isBlank(serviceTypeId)) {
                    continue;
                }

                String packagePath = attr(root, "package");
                Optional<NodeCacheEntry> cacheEntry = serviceNodeCache.findByTypeId(serviceTypeId);
                ServiceTypeMeta meta = new ServiceTypeMeta(
                    serviceTypeId,
                    attr(root, "longname"),
                    packagePath,
                    detectModule(file),
                    file.toString().replace('\\', '/'),
                    resolveServiceDomainKey(file, packagePath, cacheEntry),
                    firstNonBlank(
                        inferServiceNodeKind(fileName),
                        cacheEntry.map(NodeCacheEntry::getNodeKind).orElse(null)
                    )
                );
                result.put(serviceTypeId, meta);
                addServiceTypeNode(meta);

                for (Element serviceEl : childElements(root, "service")) {
                    String serviceId = attr(serviceEl, "id");
                    if (isBlank(serviceId)) {
                        continue;
                    }

                    String operationKey = serviceOperationKey(serviceTypeId, serviceId);
                    ServiceOperationMeta operation = new ServiceOperationMeta(
                        operationKey,
                        serviceTypeId,
                        serviceId,
                        attr(serviceEl, "name"),
                        attr(serviceEl, "longname"),
                        packagePath
                    );
                    meta.operations.put(operationKey, operation);
                    addServiceOperationNode(operation, meta);
                    graphEdges.add(edge("DECLARES_OPERATION", serviceTypeKey(serviceTypeId), operationKey));
                }
            } catch (Exception e) {
                log.warn("[FlowtransMeta] 解析 service 元数据失败: {} - {}", file.getFileName(), e.getMessage());
            }
        }
        return result;
    }

    private void ensureServiceTypeStub(String serviceTypeId, Map<String, ServiceTypeMeta> serviceTypes) {
        if (isBlank(serviceTypeId)) {
            return;
        }
        if (!serviceTypes.containsKey(serviceTypeId)) {
            Optional<NodeCacheEntry> cacheEntry = serviceNodeCache.findByTypeId(serviceTypeId);
            ServiceTypeMeta meta = new ServiceTypeMeta(
                serviceTypeId,
                null,
                cacheEntry.map(NodeCacheEntry::getPackagePath).orElse(null),
                null,
                null,
                cacheEntry.map(NodeCacheEntry::getDomainKey).orElse("public"),
                cacheEntry.map(NodeCacheEntry::getNodeKind).orElse(null)
            );
            serviceTypes.put(serviceTypeId, meta);
            addServiceTypeNode(meta);
        }
    }

    private void ensureServiceOperationStub(String serviceTypeId, String serviceId, Map<String, ServiceTypeMeta> serviceTypes) {
        if (isBlank(serviceTypeId) || isBlank(serviceId)) {
            return;
        }

        ServiceTypeMeta meta = serviceTypes.get(serviceTypeId);
        String operationKey = serviceOperationKey(serviceTypeId, serviceId);
        if (meta != null && meta.operations.containsKey(operationKey)) {
            return;
        }

        String packagePath = meta != null ? meta.packagePath : null;
        ServiceOperationMeta operation = new ServiceOperationMeta(
            operationKey,
            serviceTypeId,
            serviceId,
            null,
            null,
            packagePath
        );
        if (meta != null) {
            meta.operations.put(operationKey, operation);
        }
        addServiceOperationNode(operation, meta);
        graphEdges.add(edge("DECLARES_OPERATION", serviceTypeKey(serviceTypeId), operationKey));
    }

    private void addServiceTypeNode(ServiceTypeMeta meta) {
        String key = serviceTypeKey(meta.id);
        if (!seenServiceTypes.add(key)) {
            return;
        }
        serviceTypeNodes.add(node(
            "key", key,
            "id", meta.id,
            "longname", meta.longname,
            "packagePath", meta.packagePath,
            "module", meta.module,
            "filePath", meta.filePath,
            "domainKey", meta.domainKey,
            "nodeKind", meta.nodeKind,
            "interfaceFqn", buildInterfaceFqn(meta.packagePath, meta.id)
        ));
    }

    private void addServiceOperationNode(ServiceOperationMeta operation, ServiceTypeMeta meta) {
        if (!seenServiceOperations.add(operation.key)) {
            return;
        }
        String packagePath = operation.packagePath != null
            ? operation.packagePath
            : (meta != null ? meta.packagePath : null);
        serviceOperationNodes.add(node(
            "key", operation.key,
            "serviceTypeId", operation.serviceTypeId,
            "serviceId", operation.serviceId,
            "methodName", operation.methodName,
            "longname", operation.longname,
            "packagePath", packagePath,
            "interfaceFqn", buildInterfaceFqn(packagePath, operation.serviceTypeId)
        ));
    }

    private void ensureIndexes(Session session) {
        session.run("CREATE INDEX IF NOT EXISTS FOR (t:Transaction) ON (t.id)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (t:Transaction) ON (t.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (f:FlowBlock) ON (f.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (s:FlowSection) ON (s.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (g:FlowFieldGroup) ON (g.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (s:ServiceType) ON (s.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (o:ServiceOperation) ON (o.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (f:FlowMethodStep) ON (f.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (s:FlowServiceStep) ON (s.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (c:FlowCase) ON (c.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (w:FlowWhen) ON (w.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (f:FlowField) ON (f.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (m:FlowMappingGroup) ON (m.key)").consume();
        session.run("CREATE INDEX IF NOT EXISTS FOR (m:FlowMapping) ON (m.key)").consume();
    }

    private void writeNodes(Session session, String label, List<Map<String, Object>> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        batchWrite(session, nodes,
            "UNWIND $batch AS row " +
            "MERGE (n:" + label + " {key: row.key}) " +
            "SET n += row");
        log.info("[FlowtransMeta] 鍐欏叆 {} 鑺傜偣: {}", label, nodes.size());
    }

    private void writeTypedEdges(Session session, String type) {
        List<Map<String, Object>> typed = graphEdges.stream()
            .filter(edge -> type.equals(edge.get("type")))
            .collect(Collectors.toList());
        if (typed.isEmpty()) {
            return;
        }

        Map<String, List<Map<String, Object>>> grouped = typed.stream()
            .collect(Collectors.groupingBy(
                edge -> labelForKey(String.valueOf(edge.get("fromKey"))) + "->" + labelForKey(String.valueOf(edge.get("toKey"))),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            String[] labels = entry.getKey().split("->", 2);
            String fromLabel = labels[0];
            String toLabel = labels[1];
            batchWrite(session, entry.getValue(),
                "UNWIND $batch AS row " +
                "MATCH (a:" + fromLabel + " {key: row.fromKey}) " +
                "MATCH (b:" + toLabel + " {key: row.toKey}) " +
                "MERGE (a)-[r:" + type + "]->(b)");
        }
    }

    private void writeMethodResolveEdges(Session session) {
        if (methodResolveEdges.isEmpty()) {
            return;
        }
        batchWrite(session, methodResolveEdges,
            "UNWIND $batch AS row " +
            "MATCH (s:FlowMethodStep {key: row.stepKey}) " +
            "MATCH (m:Method {classFqn: row.classFqn, name: row.methodName}) " +
            "MERGE (s)-[:RESOLVES_TO_METHOD]->(m)");
    }

    private void writeServiceImplementationEdges(Session session) {
        session.run(
            "MATCH (op:ServiceOperation) " +
            "MATCH (iface:Interface {fqn: op.interfaceFqn}) " +
            "CALL { " +
            "  WITH op, iface " +
            "  MATCH (impl:Class)-[:IMPLEMENTS]->(iface) " +
            "  MATCH (m:Method {classFqn: impl.fqn, name: op.methodName}) " +
            "  RETURN DISTINCT m " +
            "  UNION " +
            "  WITH op, iface " +
            "  MATCH (base:Class)-[:IMPLEMENTS]->(iface) " +
            "  MATCH path = (impl:Class)-[:EXTENDS*1..6]->(base) " +
            "  UNWIND [node IN nodes(path) | node.fqn] AS ownerFqn " +
            "  MATCH (m:Method {classFqn: ownerFqn, name: op.methodName}) " +
            "  RETURN DISTINCT m " +
            "} " +
            "MERGE (op)-[:IMPLEMENTS_BY]->(m)").consume();
    }

    private void batchWrite(Session session, List<Map<String, Object>> rows, String cypher) {
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<Map<String, Object>> batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            session.run(cypher, Values.parameters("batch", batch)).consume();
        }
    }

    private void addFieldNode(String fieldKey, String txId, String sectionName, String groupKey, Element fieldEl) {
        fieldNodes.add(node(
            "key", fieldKey,
            "txId", txId,
            "sectionType", sectionName,
            "groupKey", groupKey,
            "id", attr(fieldEl, "id"),
            "type", attr(fieldEl, "type"),
            "required", attr(fieldEl, "required"),
            "multi", attr(fieldEl, "multi"),
            "array", attr(fieldEl, "array"),
            "longname", attr(fieldEl, "longname"),
            "desc", attr(fieldEl, "desc"),
            "ref", attr(fieldEl, "ref")
        ));
    }

    private void indexField(Map<String, List<String>> fieldIndex, String fieldId, String fieldKey) {
        if (isBlank(fieldId)) {
            return;
        }
        fieldIndex.computeIfAbsent(fieldId, k -> new ArrayList<>()).add(fieldKey);
    }

    private Document parseXml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        if (Files.size(file) == 0L) {
            throw new EOFException("empty xml file");
        }
        try (InputStream in = Files.newInputStream(file)) {
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(SILENT_XML_ERROR_HANDLER);
            Document doc = builder.parse(in);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }

    private List<Path> collectFiles(String suffix) {
        if (workspaceRoots == null || workspaceRoots.isBlank()) {
            return List.of();
        }

        Map<String, Path> metadataRoots = new LinkedHashMap<>();
        for (String root : workspaceRoots.split(",")) {
            String trimmed = root.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path ws = Path.of(trimmed);
            if (!Files.exists(ws)) {
                continue;
            }
            collectMetadataRoots(ws, metadataRoots);
        }

        List<Path> files = new ArrayList<>();
        for (Path metadataRoot : metadataRoots.values()) {
            try (Stream<Path> walk = Files.walk(metadataRoot, 6)) {
                walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .forEach(files::add);
            } catch (Exception e) {
                log.debug("[FlowtransMeta] 鎵弿 {} 澶辫触: {}", metadataRoot, e.getMessage());
            }
        }
        Collections.sort(files);
        return files;
    }

    private void collectMetadataRoots(Path workspaceRoot, Map<String, Path> metadataRoots) {
        try (Stream<Path> walk = Files.walk(workspaceRoot, 8)) {
            walk.filter(Files::isDirectory)
                .filter(this::isMetadataRoot)
                .sorted(Path::compareTo)
                .forEach(path -> registerMetadataRoot(metadataRoots, path));
        } catch (Exception e) {
            log.debug("[FlowtransMeta] 鎵弿 {} 澶辫触: {}", workspaceRoot, e.getMessage());
        }
    }

    private boolean isMetadataRoot(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.endsWith("/src/main/resources") || normalized.endsWith("/target/classes");
    }

    private void registerMetadataRoot(Map<String, Path> metadataRoots, Path candidate) {
        String normalized = candidate.toString().replace('\\', '/');
        String suffix = normalized.endsWith("/src/main/resources") ? "/src/main/resources" : "/target/classes";
        String moduleKey = normalized.substring(0, normalized.length() - suffix.length());
        Path existing = metadataRoots.get(moduleKey);
        if (existing == null || isPreferredMetadataRoot(candidate, existing)) {
            metadataRoots.put(moduleKey, candidate);
        }
    }

    private boolean isPreferredMetadataRoot(Path candidate, Path existing) {
        String candidateText = candidate.toString().replace('\\', '/');
        String existingText = existing.toString().replace('\\', '/');
        boolean candidateIsSource = candidateText.endsWith("/src/main/resources");
        boolean existingIsSource = existingText.endsWith("/src/main/resources");
        if (candidateIsSource != existingIsSource) {
            return candidateIsSource;
        }
        return candidateText.compareTo(existingText) < 0;
    }

    private String detectModule(Path file) {
        String normalized = file.toString().replace('\\', '/');
        if (workspaceRoots == null) {
            return "unknown";
        }
        for (String root : workspaceRoots.split(",")) {
            String ws = root.trim().replace('\\', '/');
            if (ws.isEmpty() || !normalized.startsWith(ws)) {
                continue;
            }
            String rel = normalized.substring(ws.length()).replaceFirst("^/", "");
            String[] parts = rel.split("/");
            if (parts.length >= 2) {
                return parts[0] + "/" + parts[1];
            }
        }
        return "unknown";
    }

    private String detectParentProject(Path file) {
        String normalized = file.toString().replace('\\', '/');
        if (workspaceRoots == null) {
            return "unknown";
        }
        for (String root : workspaceRoots.split(",")) {
            String ws = root.trim().replace('\\', '/');
            if (ws.isEmpty() || !normalized.startsWith(ws)) {
                continue;
            }
            String rel = normalized.substring(ws.length()).replaceFirst("^/", "");
            String[] parts = rel.split("/");
            if (parts.length >= 1 && !parts[0].isBlank()) {
                return parts[0];
            }
        }
        return "unknown";
    }

    private String resolveTransactionDomainKey(String parentProject, String packagePath) {
        String fromParent = PARENT_PROJECT_DOMAIN_MAP.get(parentProject);
        if (fromParent != null) {
            return fromParent;
        }
        return DomainKeyResolver.resolve(packagePath);
    }

    private String resolveServiceDomainKey(Path file, String packagePath, Optional<NodeCacheEntry> cacheEntry) {
        String fromParent = PARENT_PROJECT_DOMAIN_MAP.get(detectParentProject(file));
        if (fromParent != null) {
            return fromParent;
        }
        return firstNonBlank(
            cacheEntry.map(NodeCacheEntry::getDomainKey).orElse(null),
            DomainKeyResolver.resolve(packagePath)
        );
    }

    private void clearBuffers() {
        transactionNodes.clear();
        flowNodes.clear();
        sectionNodes.clear();
        fieldGroupNodes.clear();
        fieldNodes.clear();
        methodStepNodes.clear();
        serviceStepNodes.clear();
        caseNodes.clear();
        whenNodes.clear();
        mappingGroupNodes.clear();
        mappingNodes.clear();
        serviceTypeNodes.clear();
        serviceOperationNodes.clear();
        graphEdges.clear();
        methodResolveEdges.clear();
        seenServiceTypes.clear();
        seenServiceOperations.clear();
    }

    private static Map<String, Object> node(Object... kvs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            Object value = kvs[i + 1];
            if (value != null) {
                map.put(String.valueOf(kvs[i]), value);
            }
        }
        return map;
    }

    private static Map<String, Object> edge(String type, String fromKey, String toKey) {
        return node("type", type, "fromKey", fromKey, "toKey", toKey);
    }

    private static String labelForKey(String key) {
        if (isBlank(key)) {
            throw new IllegalArgumentException("Edge key cannot be blank");
        }
        if (key.startsWith("SVOP:")) {
            return "ServiceOperation";
        }
        if (key.startsWith("SVTP:")) {
            return "ServiceType";
        }
        if (key.startsWith("TX:")) {
            if (key.contains(":MAPGROUP:")) {
                return "FlowMappingGroup";
            }
            if (key.contains(":MAP:")) {
                return "FlowMapping";
            }
            if (key.contains(":WHEN:")) {
                return "FlowWhen";
            }
            if (key.contains(":CASE:")) {
                return "FlowCase";
            }
            if (key.contains(":METHOD:")) {
                return "FlowMethodStep";
            }
            if (key.contains(":SERVICE:")) {
                return "FlowServiceStep";
            }
            if (key.contains(":SECTION:")) {
                return "FlowSection";
            }
            if (key.contains(":GROUP:")) {
                return "FlowFieldGroup";
            }
            if (key.contains(":FIELD:")) {
                return "FlowField";
            }
            if (key.endsWith(":FLOW")) {
                return "FlowBlock";
            }
            return "Transaction";
        }
        throw new IllegalArgumentException("Unsupported flow graph key: " + key);
    }

    private static String txKey(String txId) {
        return "TX:" + txId;
    }

    private static String serviceTypeKey(String serviceTypeId) {
        return "SVTP:" + serviceTypeId;
    }

    private static String serviceOperationKey(String serviceTypeId, String serviceId) {
        return "SVOP:" + serviceTypeId + "." + serviceId;
    }

    private static String stripServiceMetaSuffix(String fileName) {
        for (String suffix : List.of(
            ".serviceType.xml",
            ".pbs.xml",
            ".pcs.xml",
            ".pbcb.xml",
            ".pbcp.xml",
            ".pbcc.xml",
            ".pbct.xml"
        )) {
            if (fileName.endsWith(suffix)) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        return fileName;
    }

    private static String inferServiceNodeKind(String fileName) {
        String lowered = fileName.toLowerCase();
        if (lowered.endsWith(".pbs.xml")) {
            return "pbs";
        }
        if (lowered.endsWith(".pcs.xml")) {
            return "pcs";
        }
        if (lowered.endsWith(".pbcb.xml")) {
            return "pbcb";
        }
        if (lowered.endsWith(".pbcp.xml")) {
            return "pbcp";
        }
        if (lowered.endsWith(".pbcc.xml")) {
            return "pbcc";
        }
        if (lowered.endsWith(".pbct.xml")) {
            return "pbct";
        }
        if (lowered.endsWith("pbs")) {
            return "pbs";
        }
        if (lowered.endsWith("pcs")) {
            return "pcs";
        }
        if (lowered.endsWith("pbcb")) {
            return "pbcb";
        }
        if (lowered.endsWith("pbcp")) {
            return "pbcp";
        }
        if (lowered.endsWith("pbcc")) {
            return "pbcc";
        }
        if (lowered.endsWith("pbct")) {
            return "pbct";
        }
        return null;
    }

    private static String buildInterfaceFqn(String packagePath, String serviceTypeId) {
        if (isBlank(packagePath) || isBlank(serviceTypeId)) {
            return null;
        }
        return packagePath + "." + serviceTypeId;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String splitLeft(String serviceName) {
        if (isBlank(serviceName) || !serviceName.contains(".")) {
            return serviceName;
        }
        int dot = serviceName.indexOf('.');
        return serviceName.substring(0, dot);
    }

    private static String splitRight(String serviceName, String fallback) {
        if (isBlank(serviceName) || !serviceName.contains(".")) {
            return fallback;
        }
        int dot = serviceName.indexOf('.');
        return serviceName.substring(dot + 1);
    }

    private static Element firstChild(Element parent, String tagName) {
        for (Element child : childElements(parent)) {
            if (tagName.equals(child.getTagName())) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static List<Element> childElements(Element parent, String tagName) {
        return childElements(parent).stream()
            .filter(element -> tagName.equals(element.getTagName()))
            .collect(Collectors.toList());
    }

    private static String attr(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class ServiceTypeMeta {
        private final String id;
        private final String longname;
        private final String packagePath;
        private final String module;
        private final String filePath;
        private final String domainKey;
        private final String nodeKind;
        private final Map<String, ServiceOperationMeta> operations = new LinkedHashMap<>();

        private ServiceTypeMeta(String id,
                                String longname,
                                String packagePath,
                                String module,
                                String filePath,
                                String domainKey,
                                String nodeKind) {
            this.id = id;
            this.longname = longname;
            this.packagePath = packagePath;
            this.module = module;
            this.filePath = filePath;
            this.domainKey = domainKey;
            this.nodeKind = nodeKind;
        }
    }

    private static final class ServiceOperationMeta {
        private final String key;
        private final String serviceTypeId;
        private final String serviceId;
        private final String methodName;
        private final String longname;
        private final String packagePath;

        private ServiceOperationMeta(String key,
                                     String serviceTypeId,
                                     String serviceId,
                                     String methodName,
                                     String longname,
                                     String packagePath) {
            this.key = key;
            this.serviceTypeId = serviceTypeId;
            this.serviceId = serviceId;
            this.methodName = methodName;
            this.longname = longname;
            this.packagePath = packagePath;
        }
    }
}
