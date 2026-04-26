package com.axonlink.service.impl;

import com.axonlink.config.Neo4jConfig;
import com.axonlink.dto.NodeCacheEntry;
import com.axonlink.service.FlowServiceMetadataResolver;
import com.axonlink.service.FlowServiceMetadataResolver.ServiceTypeFileMeta;
import com.axonlink.service.FlowtranImpactProjectionCache;
import com.axonlink.service.FlowtranImpactProjectionData;
import com.axonlink.service.FlowtranImpactService;
import com.axonlink.service.ServiceNodeCache;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FlowtranImpactServiceImpl implements FlowtranImpactService {

    private static final Logger log = LoggerFactory.getLogger(FlowtranImpactServiceImpl.class);
    private static final int MAX_METHOD_DEPTH = 8;
    private static final int PRELOAD_BATCH_SIZE = 200;
    private static final List<String> COMPONENT_NODE_KINDS = List.of("pbcb", "pbcp", "pbcc", "pbct");
    private static final List<String> SERVICE_NODE_KINDS = List.of("pbs", "pcs", "service");

    private final Driver driver;
    private final Neo4jConfig neo4jConfig;
    private final FlowtranImpactProjectionCache impactProjectionCache;
    private final ServiceNodeCache serviceNodeCache;
    private final FlowServiceMetadataResolver flowServiceMetadataResolver;

    public FlowtranImpactServiceImpl(Driver driver,
                                     Neo4jConfig neo4jConfig,
                                     FlowtranImpactProjectionCache impactProjectionCache,
                                     ServiceNodeCache serviceNodeCache,
                                     FlowServiceMetadataResolver flowServiceMetadataResolver) {
        this.driver = driver;
        this.neo4jConfig = neo4jConfig;
        this.impactProjectionCache = impactProjectionCache;
        this.serviceNodeCache = serviceNodeCache;
        this.flowServiceMetadataResolver = flowServiceMetadataResolver;
    }

    @Override
    public Map<String, Object> getTableImpact(String tableId) {
        if (isBlank(tableId)) {
            return null;
        }
        Map<String, Object> cached = impactProjectionCache.getTableImpact(tableId);
        if (cached != null) {
            return cached;
        }
        return emptyImpactResult(new TableMeta(tableId, tableId, null, null, null));
    }

    @Override
    public Map<String, Object> getComponentImpact(String componentId) {
        if (isBlank(componentId)) {
            return null;
        }
        Map<String, Object> cached = impactProjectionCache.getComponentImpact(componentId);
        if (cached != null) {
            return cached;
        }
        return emptyComponentImpactResult(new ImpactNode(componentId, componentId, null, "public", "component"));
    }

    @Override
    public Map<String, Object> listComponentCatalog(String keyword) {
        if (!neo4jAvailable()) {
            return Map.of("total", 0, "items", List.of());
        }

        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        try (Session session = driver.session()) {
            List<Map<String, Object>> items = session.run(
                "MATCH (op:ServiceOperation) " +
                "WHERE op.nodeKind IN $kinds " +
                "  AND ($keyword = '' " +
                "       OR toLower(coalesce(op.serviceTypeId + '.' + op.serviceId, '')) CONTAINS $keyword " +
                "       OR toLower(coalesce(op.longname, op.serviceId, op.methodName, '')) CONTAINS $keyword) " +
                "RETURN op.serviceTypeId + '.' + op.serviceId AS id, " +
                "       coalesce(op.longname, op.serviceId, op.methodName) AS name, " +
                "       op.longname AS longname, " +
                "       op.domainKey AS domainKey, " +
                "       op.nodeKind AS nodeKind, " +
                "       op.serviceTypeId AS serviceTypeId " +
                "ORDER BY id",
                Values.parameters("kinds", COMPONENT_NODE_KINDS, "keyword", normalizedKeyword))
                .list(record -> node(
                    "id", stringValue(record, "id"),
                    "name", stringValue(record, "name"),
                    "longname", stringValue(record, "longname"),
                    "domainKey", stringValue(record, "domainKey"),
                    "nodeKind", stringValue(record, "nodeKind"),
                    "serviceTypeId", stringValue(record, "serviceTypeId")
                ));
            return Map.of("total", items.size(), "items", items);
        } catch (Exception e) {
            log.warn("[FlowtranImpactService] listComponentCatalog failed: {}", e.getMessage());
            return Map.of("total", 0, "items", List.of(), "error", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getServiceImpact(String serviceId) {
        if (isBlank(serviceId)) {
            return null;
        }
        Map<String, Object> cached = impactProjectionCache.getServiceImpact(serviceId);
        if (cached != null) {
            return cached;
        }
        return emptyServiceImpactResult(new ImpactNode(serviceId, serviceId, null, "public", "service"));
    }

    @Override
    public Map<String, Object> listServiceCatalog(String keyword) {
        if (!neo4jAvailable()) {
            return Map.of("total", 0, "items", List.of());
        }

        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        try (Session session = driver.session()) {
            List<Map<String, Object>> items = session.run(
                "MATCH (op:ServiceOperation) " +
                "WHERE op.nodeKind IN $kinds " +
                "  AND ($keyword = '' " +
                "       OR toLower(coalesce(op.serviceTypeId + '.' + op.serviceId, '')) CONTAINS $keyword " +
                "       OR toLower(coalesce(op.longname, op.serviceId, op.methodName, '')) CONTAINS $keyword) " +
                "RETURN op.serviceTypeId + '.' + op.serviceId AS id, " +
                "       coalesce(op.longname, op.serviceId, op.methodName) AS name, " +
                "       op.longname AS longname, " +
                "       op.domainKey AS domainKey, " +
                "       op.nodeKind AS nodeKind, " +
                "       op.serviceTypeId AS serviceTypeId " +
                "ORDER BY id",
                Values.parameters("kinds", SERVICE_NODE_KINDS, "keyword", normalizedKeyword))
                .list(record -> node(
                    "id", stringValue(record, "id"),
                    "name", stringValue(record, "name"),
                    "longname", stringValue(record, "longname"),
                    "domainKey", stringValue(record, "domainKey"),
                    "nodeKind", stringValue(record, "nodeKind"),
                    "serviceTypeId", stringValue(record, "serviceTypeId")
                ));
            return Map.of("total", items.size(), "items", items);
        } catch (Exception e) {
            log.warn("[FlowtranImpactService] listServiceCatalog failed: {}", e.getMessage());
            return Map.of("total", 0, "items", List.of(), "error", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getImpactCacheStatus() {
        return impactProjectionCache.getStatus();
    }

    public FlowtranImpactProjectionData buildProjectionData() {
        if (!neo4jAvailable()) {
            return new FlowtranImpactProjectionData(0, Map.of(), Map.of(), Map.of());
        }

        try (Session session = driver.session()) {
            Map<String, TableMeta> tableRoots = loadAllTableMetas(session);
            Map<String, ImpactNode> componentRoots = loadAllImpactRoots(session, COMPONENT_NODE_KINDS, "component");
            Map<String, ImpactNode> serviceRoots = loadAllImpactRoots(session, SERVICE_NODE_KINDS, "service");
            List<TransactionImpactModel> models = loadAllTransactionModels(session);

            Map<String, List<TransactionImpactModel>> tableIndex = new LinkedHashMap<>();
            Map<String, List<TransactionImpactModel>> componentIndex = new LinkedHashMap<>();
            Map<String, List<TransactionImpactModel>> serviceIndex = new LinkedHashMap<>();
            for (TransactionImpactModel model : models) {
                indexModel(tableIndex, model.tables.keySet(), model);
                indexModel(componentIndex, model.components.keySet(), model);
                indexModel(serviceIndex, model.services.keySet(), model);
            }

            Map<String, List<OrchestrationRef>> orchestrationRefsByService =
                loadDirectOrchestrationRefs(session, serviceRoots.keySet());

            Map<String, Map<String, Object>> tableImpacts = new LinkedHashMap<>();
            for (Map.Entry<String, TableMeta> entry : tableRoots.entrySet()) {
                String tableId = entry.getKey();
                tableImpacts.put(
                    tableId,
                    aggregateTableImpact(entry.getValue(), tableIndex.getOrDefault(tableId, List.of()), tableId)
                );
            }

            Map<String, Map<String, Object>> componentImpacts = new LinkedHashMap<>();
            for (Map.Entry<String, ImpactNode> entry : componentRoots.entrySet()) {
                String componentId = entry.getKey();
                componentImpacts.put(
                    componentId,
                    aggregateComponentImpact(entry.getValue(), componentIndex.getOrDefault(componentId, List.of()), componentId)
                );
            }

            Map<String, Map<String, Object>> serviceImpacts = new LinkedHashMap<>();
            for (Map.Entry<String, ImpactNode> entry : serviceRoots.entrySet()) {
                String serviceId = entry.getKey();
                serviceImpacts.put(
                    serviceId,
                    aggregateServiceImpact(
                        entry.getValue(),
                        serviceIndex.getOrDefault(serviceId, List.of()),
                        serviceId,
                        orchestrationRefsByService
                    )
                );
            }

            return new FlowtranImpactProjectionData(
                models.size(),
                tableImpacts,
                componentImpacts,
                serviceImpacts
            );
        } catch (Exception e) {
            log.warn("[FlowtranImpactService] buildProjectionData failed: {}", e.getMessage(), e);
            throw new IllegalStateException("build impact projection failed", e);
        }
    }

    private boolean neo4jAvailable() {
        return neo4jConfig.isEnabled() && driver != null;
    }

    private TableMeta loadTableMeta(Session session, String tableId) {
        List<Record> records = session.run(
            "MATCH (table:Table {id: $tableId}) " +
            "RETURN table.id AS id, " +
            "       coalesce(table.longname, table.id) AS longname, " +
            "       table.domainKey AS domainKey, " +
            "       coalesce(table.projectName, '') AS projectName, " +
            "       coalesce(table.daoClassName, '') AS daoClassName " +
            "LIMIT 1",
            Values.parameters("tableId", tableId)).list();
        if (records.isEmpty()) {
            return null;
        }
        Record record = records.get(0);
        return new TableMeta(
            stringValue(record, "id"),
            stringValue(record, "longname"),
            stringValue(record, "domainKey"),
            stringValue(record, "projectName"),
            stringValue(record, "daoClassName")
        );
    }

    private Map<String, TableMeta> loadAllTableMetas(Session session) {
        LinkedHashMap<String, TableMeta> result = new LinkedHashMap<>();
        session.run(
            "MATCH (table:Table) " +
            "RETURN table.id AS id, " +
            "       coalesce(table.longname, table.id) AS longname, " +
            "       table.domainKey AS domainKey, " +
            "       coalesce(table.projectName, '') AS projectName, " +
            "       coalesce(table.daoClassName, '') AS daoClassName " +
            "ORDER BY table.id")
            .forEachRemaining(record -> {
                String id = stringValue(record, "id");
                if (isBlank(id)) {
                    return;
                }
                result.put(id, new TableMeta(
                    id,
                    stringValue(record, "longname"),
                    stringValue(record, "domainKey"),
                    stringValue(record, "projectName"),
                    stringValue(record, "daoClassName")
                ));
            });
        return result;
    }

    private ImpactNode loadComponentMeta(Session session, String componentId) {
        String serviceTypeId = resolveTypeId(componentId);
        String serviceId = componentId != null && componentId.contains(".")
            ? componentId.substring(componentId.indexOf('.') + 1)
            : null;
        if (isBlank(serviceTypeId) || isBlank(serviceId)) {
            return null;
        }

        List<Record> records = session.run(
            "MATCH (op:ServiceOperation {serviceTypeId: $serviceTypeId, serviceId: $serviceId}) " +
            "WHERE op.nodeKind IN $kinds " +
            "RETURN op.serviceTypeId + '.' + op.serviceId AS code, " +
            "       coalesce(op.longname, op.serviceId, op.methodName) AS name, " +
            "       op.nodeKind AS nodeKind, " +
            "       op.domainKey AS domainKey " +
            "LIMIT 1",
            Values.parameters(
                "serviceTypeId", serviceTypeId,
                "serviceId", serviceId,
                "kinds", COMPONENT_NODE_KINDS
            )).list();
        if (records.isEmpty()) {
            return null;
        }

        Record record = records.get(0);
        return new ImpactNode(
            stringValue(record, "code"),
            stringValue(record, "name"),
            stringValue(record, "nodeKind"),
            stringValue(record, "domainKey"),
            "component"
        );
    }

    private ImpactNode loadServiceMeta(Session session, String serviceId) {
        String serviceTypeId = resolveTypeId(serviceId);
        String operationId = serviceId != null && serviceId.contains(".")
            ? serviceId.substring(serviceId.indexOf('.') + 1)
            : null;
        if (isBlank(serviceTypeId) || isBlank(operationId)) {
            return null;
        }

        List<Record> records = session.run(
            "MATCH (op:ServiceOperation {serviceTypeId: $serviceTypeId, serviceId: $serviceId}) " +
            "WHERE op.nodeKind IN $kinds " +
            "RETURN op.serviceTypeId + '.' + op.serviceId AS code, " +
            "       coalesce(op.longname, op.serviceId, op.methodName) AS name, " +
            "       op.nodeKind AS nodeKind, " +
            "       op.domainKey AS domainKey " +
            "LIMIT 1",
            Values.parameters(
                "serviceTypeId", serviceTypeId,
                "serviceId", operationId,
                "kinds", SERVICE_NODE_KINDS
            )).list();
        if (records.isEmpty()) {
            return null;
        }

        Record record = records.get(0);
        return new ImpactNode(
            stringValue(record, "code"),
            stringValue(record, "name"),
            stringValue(record, "nodeKind"),
            stringValue(record, "domainKey"),
            "service"
        );
    }

    private Map<String, ImpactNode> loadAllImpactRoots(Session session,
                                                       List<String> nodeKinds,
                                                       String nodeType) {
        LinkedHashMap<String, ImpactNode> result = new LinkedHashMap<>();
        session.run(
            "MATCH (op:ServiceOperation) " +
            "WHERE op.nodeKind IN $kinds " +
            "RETURN op.serviceTypeId + '.' + op.serviceId AS code, " +
            "       coalesce(op.longname, op.serviceId, op.methodName) AS name, " +
            "       op.nodeKind AS nodeKind, " +
            "       op.domainKey AS domainKey " +
            "ORDER BY code",
            Values.parameters("kinds", nodeKinds))
            .forEachRemaining(record -> {
                String code = stringValue(record, "code");
                if (isBlank(code)) {
                    return;
                }
                result.put(code, new ImpactNode(
                    code,
                    stringValue(record, "name"),
                    stringValue(record, "nodeKind"),
                    stringValue(record, "domainKey"),
                    nodeType
                ));
            });
        return result;
    }

    private List<TransactionMeta> loadTransactions(Session session) {
        return session.run(
            "MATCH (tx:Transaction) " +
            "RETURN tx.id AS id, " +
            "       coalesce(tx.longname, tx.id) AS longname, " +
            "       tx.domainKey AS domainKey, " +
            "       coalesce(tx.parentProject, tx.module, '') AS parentProject " +
            "ORDER BY tx.id")
            .list(record -> new TransactionMeta(
                stringValue(record, "id"),
                stringValue(record, "longname"),
                stringValue(record, "domainKey"),
                stringValue(record, "parentProject")
            ));
    }

    private List<TransactionImpactModel> loadAllTransactionModels(Session session) {
        BoundaryResolver resolver = new BoundaryResolver(session);
        ProjectionQueryCache queryCache = new ProjectionQueryCache(session);
        List<TransactionMeta> transactions = loadTransactions(session);
        Map<String, List<FlowStepRef>> flowStepsByTxId = loadAllFlowSteps(
            session,
            transactions.stream().map(tx -> tx.id).collect(Collectors.toList())
        );
        List<TransactionImpactModel> impacted = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        for (int index = 0; index < transactions.size(); index++) {
            TransactionMeta tx = transactions.get(index);
            TransactionImpactModel model = buildTransactionModel(
                tx,
                flowStepsByTxId.getOrDefault(tx.id, List.of()),
                resolver,
                queryCache
            );
            if (model != null) {
                impacted.add(model);
            }
            int processed = index + 1;
            if (processed % 50 == 0 || processed == transactions.size()) {
                log.info(
                    "[FlowtranImpactService] Phase5 tx-models {}/{} impacted={} elapsed={}ms outgoingCache={} tableCache={}",
                    processed,
                    transactions.size(),
                    impacted.size(),
                    System.currentTimeMillis() - startedAt,
                    queryCache.outgoingCacheSize(),
                    queryCache.tableTargetCacheSize()
                );
            }
        }
        return impacted;
    }

    private TransactionImpactModel buildTransactionModel(TransactionMeta tx,
                                                         List<FlowStepRef> steps,
                                                         BoundaryResolver resolver,
                                                         ProjectionQueryCache queryCache) {
        if (steps.isEmpty()) {
            return null;
        }

        LinkedHashMap<String, LogicalSeed> topServiceSeeds = new LinkedHashMap<>();
        LinkedHashMap<String, LogicalSeed> componentSeeds = new LinkedHashMap<>();
        LinkedHashSet<String> rootServiceCodes = new LinkedHashSet<>();

        LinkedHashMap<String, ImpactNode> serviceLayer = new LinkedHashMap<>();
        LinkedHashMap<String, ImpactNode> componentLayer = new LinkedHashMap<>();
        LinkedHashMap<String, TableTarget> tableLayer = new LinkedHashMap<>();

        for (FlowStepRef step : steps) {
            LogicalSeed seed = new LogicalSeed(
                step.code,
                step.name,
                step.prefix,
                step.domainKey,
                new LinkedHashSet<>(step.methodSignatures)
            );
            if (isComponentPrefix(step.prefix)) {
                componentLayer.putIfAbsent(step.code, displayNode(step.code, step.name, step.prefix, tx.domainKey, step.domainKey));
                componentSeeds.merge(step.code, seed, LogicalSeed::merge);
            } else {
                serviceLayer.putIfAbsent(step.code, displayNode(step.code, step.name, step.prefix, tx.domainKey, step.domainKey));
                topServiceSeeds.merge(step.code, seed, LogicalSeed::merge);
                rootServiceCodes.add(step.code);
            }
        }

        Map<String, List<String>> serviceToService = new LinkedHashMap<>();
        Map<String, List<String>> serviceToComponent = new LinkedHashMap<>();
        Map<String, List<String>> componentToComponent = new LinkedHashMap<>();
        Map<String, List<String>> nodeToTable = new LinkedHashMap<>();

        Map<String, Integer> processedServiceSignatureCounts = new HashMap<>();
        List<String> serviceQueue = new ArrayList<>(topServiceSeeds.keySet());
        for (int i = 0; i < serviceQueue.size(); i++) {
            String seedCode = serviceQueue.get(i);
            LogicalSeed seed = topServiceSeeds.get(seedCode);
            if (seed == null) {
                continue;
            }
            int processedSignatureCount = processedServiceSignatureCounts.getOrDefault(seedCode, 0);
            if (processedSignatureCount >= seed.methodSignatures.size()) {
                continue;
            }
            processedServiceSignatureCounts.put(seedCode, seed.methodSignatures.size());
            BoundaryResult directTargets = discoverDirectTargets(seed.methodSignatures, resolver, queryCache);
            for (LogicalSeed target : directTargets.serviceTargets.values()) {
                serviceLayer.putIfAbsent(target.code, displayNode(target.code, target.name, target.prefix, tx.domainKey, target.domainKey));
                addRelation(serviceToService, seed.code, target.code);
                LogicalSeed existing = topServiceSeeds.get(target.code);
                if (existing == null) {
                    topServiceSeeds.put(target.code, target);
                    serviceQueue.add(target.code);
                } else {
                    LogicalSeed merged = existing.merge(target);
                    topServiceSeeds.put(target.code, merged);
                    if (merged.methodSignatures.size() > existing.methodSignatures.size()) {
                        serviceQueue.add(target.code);
                    }
                }
            }
            for (LogicalSeed target : directTargets.componentTargets.values()) {
                componentLayer.putIfAbsent(target.code, displayNode(target.code, target.name, target.prefix, tx.domainKey, target.domainKey));
                LogicalSeed existing = componentSeeds.get(target.code);
                componentSeeds.put(target.code, existing == null ? target : existing.merge(target));
                addRelation(serviceToComponent, seed.code, target.code);
            }
            BoundaryResult dataTargets = discoverDataTargets(seed.methodSignatures, queryCache);
            for (TableTarget target : mergeTableTargets(directTargets, dataTargets).values()) {
                tableLayer.putIfAbsent(target.code, target);
                addRelation(nodeToTable, seed.code, target.code);
            }
        }

        Map<String, Integer> processedComponentSignatureCounts = new HashMap<>();
        List<String> componentQueue = new ArrayList<>(componentSeeds.keySet());
        for (int i = 0; i < componentQueue.size(); i++) {
            String seedCode = componentQueue.get(i);
            LogicalSeed seed = componentSeeds.get(seedCode);
            if (seed == null) {
                continue;
            }
            int processedSignatureCount = processedComponentSignatureCounts.getOrDefault(seedCode, 0);
            if (processedSignatureCount >= seed.methodSignatures.size()) {
                continue;
            }
            processedComponentSignatureCounts.put(seedCode, seed.methodSignatures.size());
            BoundaryResult directTargets = discoverDirectTargets(seed.methodSignatures, resolver, queryCache);
            for (LogicalSeed target : directTargets.componentTargets.values()) {
                componentLayer.putIfAbsent(target.code, displayNode(target.code, target.name, target.prefix, tx.domainKey, target.domainKey));
                LogicalSeed existing = componentSeeds.get(target.code);
                if (existing == null) {
                    componentSeeds.put(target.code, target);
                    componentQueue.add(target.code);
                } else {
                    LogicalSeed merged = existing.merge(target);
                    componentSeeds.put(target.code, merged);
                    if (merged.methodSignatures.size() > existing.methodSignatures.size()) {
                        componentQueue.add(target.code);
                    }
                }
                addRelation(componentToComponent, seed.code, target.code);
            }
            BoundaryResult dataTargets = discoverDataTargets(seed.methodSignatures, queryCache);
            for (TableTarget target : mergeTableTargets(directTargets, dataTargets).values()) {
                tableLayer.putIfAbsent(target.code, target);
                addRelation(nodeToTable, seed.code, target.code);
            }
        }

        Map<String, List<String>> componentTableMap = new LinkedHashMap<>();
        for (String componentCode : componentLayer.keySet()) {
            List<String> tables = nodeToTable.getOrDefault(componentCode, List.of());
            if (!tables.isEmpty()) {
                componentTableMap.put(componentCode, new ArrayList<>(tables));
            }
        }

        return new TransactionImpactModel(
            tx.id,
            firstNonBlank(tx.longname, tx.id),
            tx.id,
            tx.domainKey,
            new LinkedHashMap<>(serviceLayer),
            new LinkedHashMap<>(componentLayer),
            new LinkedHashMap<>(tableLayer),
            new LinkedHashMap<>(serviceToService),
            new LinkedHashMap<>(serviceToComponent),
            new LinkedHashMap<>(componentToComponent),
            new LinkedHashMap<>(nodeToTable),
            componentTableMap,
            new LinkedHashSet<>(rootServiceCodes)
        );
    }

    private Map<String, List<FlowStepRef>> loadAllFlowSteps(Session session, Collection<String> txIds) {
        List<String> ids = txIds.stream()
            .filter(id -> !isBlank(id))
            .distinct()
            .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<String, List<FlowStepRef>> result = new LinkedHashMap<>();
        session.run(
            "UNWIND $txIds AS txId " +
            "MATCH (tx:Transaction {id: txId})-[:HAS_FLOW]->(flow:FlowBlock) " +
            "MATCH p = (flow)-[:HAS_STEP|EXECUTES|HAS_BRANCH|NEXT*0..24]->(step) " +
            "WHERE step:FlowMethodStep OR step:FlowServiceStep " +
            "WITH DISTINCT txId, step " +
            "OPTIONAL MATCH (step)-[:RESOLVES_TO_METHOD]->(resolved:Method) " +
            "OPTIONAL MATCH (step)-[:CALLS_SERVICE]->(op:ServiceOperation) " +
            "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(stype:ServiceType) " +
            "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
            "RETURN txId, " +
            "       step.key AS stepKey, " +
            "       labels(step)[0] AS stepLabel, " +
            "       step.id AS stepId, " +
            "       coalesce(step.longname, step.id, step.methodName, step.serviceId) AS stepName, " +
            "       step.methodName AS methodName, " +
            "       step.serviceName AS serviceName, " +
            "       step.serviceTypeId AS serviceTypeId, " +
            "       step.serviceId AS serviceId, " +
            "       stype.nodeKind AS nodeKind, " +
            "       stype.domainKey AS serviceDomainKey, " +
            "       collect(DISTINCT resolved.signature) AS resolvedSignatures, " +
            "       collect(DISTINCT impl.signature) AS implSignatures " +
            "ORDER BY txId, stepKey",
            Values.parameters("txIds", ids))
            .forEachRemaining(record -> {
                String txId = stringValue(record, "txId");
                if (isBlank(txId)) {
                    return;
                }
                FlowStepRef step = toFlowStepRef(record);
                if (step == null) {
                    return;
                }
                result.computeIfAbsent(txId, ignored -> new ArrayList<>()).add(step);
            });
        return result;
    }

    private FlowStepRef toFlowStepRef(Record record) {
        String stepLabel = stringValue(record, "stepLabel");
        String code;
        String prefix;
        String domainKey = null;
        List<String> signatures;
        if ("FlowMethodStep".equals(stepLabel)) {
            code = firstNonBlank(stringValue(record, "stepId"), stringValue(record, "methodName"), stringValue(record, "stepKey"));
            prefix = "method";
            signatures = listAsStrings(record.get("resolvedSignatures"));
        } else {
            String serviceTypeId = stringValue(record, "serviceTypeId");
            String serviceName = firstNonBlank(
                stringValue(record, "serviceName"),
                buildServiceCode(serviceTypeId, stringValue(record, "serviceId")),
                stringValue(record, "stepId"));
            Optional<NodeCacheEntry> cacheEntry = serviceNodeCache.get(serviceName);
            Optional<ServiceTypeFileMeta> fileMeta = resolveServiceFileMeta(serviceTypeId);
            prefix = normalizePrefix(firstNonBlank(
                stringValue(record, "nodeKind"),
                cacheEntry.map(NodeCacheEntry::getNodeKind).orElse(null),
                fileMeta.map(ServiceTypeFileMeta::getNodeKind).orElse(null),
                "pbs"));
            domainKey = firstNonBlank(
                stringValue(record, "serviceDomainKey"),
                cacheEntry.map(NodeCacheEntry::getDomainKey).orElse(null),
                fileMeta.map(ServiceTypeFileMeta::getDomainKey).orElse(null));
            code = serviceName;
            signatures = listAsStrings(record.get("implSignatures"));
        }

        if (isBlank(code)) {
            return null;
        }
        String name = firstNonBlank(
            stringValue(record, "stepName"),
            serviceNodeCache.get(code).map(NodeCacheEntry::getServiceLongname).orElse(null),
            code);
        return new FlowStepRef(code, name, prefix, domainKey, signatures);
    }

    private BoundaryResult discoverDirectTargets(Collection<String> startSignatures,
                                                 BoundaryResolver resolver,
                                                 ProjectionQueryCache queryCache) {
        BoundaryResult result = new BoundaryResult();
        List<String> frontier = startSignatures.stream()
            .filter(sig -> !isBlank(sig))
            .distinct()
            .collect(Collectors.toList());
        Set<String> visited = new LinkedHashSet<>(frontier);

        for (int depth = 0; depth < MAX_METHOD_DEPTH && !frontier.isEmpty(); depth++) {
            Map<String, List<CallTarget>> outgoing = queryCache.loadOutgoingCalls(frontier);
            resolver.preload(outgoing, frontier);
            Set<String> nextFrontier = new LinkedHashSet<>();

            for (String signature : frontier) {
                for (CallTarget target : outgoing.getOrDefault(signature, List.of())) {
                    if ("DaoMethod".equals(target.targetLabel)) {
                        String tableId = target.targetTableId;
                        String methodName = firstNonBlank(target.targetMethodName, target.edgeMethodName);
                        if (!isBlank(tableId) && !isBlank(methodName)) {
                            TableTarget tableTarget = new TableTarget(
                                tableId,
                                firstNonBlank(target.targetTableLongname, tableId),
                                target.targetDomainKey,
                                target.targetProjectName,
                                target.targetDaoClassName
                            );
                            result.tableTargets.putIfAbsent(tableTarget.code, tableTarget);
                        }
                        continue;
                    }

                    Optional<LogicalSeed> logicalTarget;
                    if ("Method".equals(target.targetLabel)) {
                        logicalTarget = resolver.resolveByMethodSignature(target.targetSignature);
                    } else if ("Class".equals(target.targetLabel)) {
                        logicalTarget = resolver.resolveByImplementationClass(target.targetFqn, target.edgeMethodName);
                    } else if ("Interface".equals(target.targetLabel)) {
                        logicalTarget = resolver.resolveByInterfaceMethod(target.targetFqn, target.edgeMethodName);
                    } else if ("ServiceOperation".equals(target.targetLabel)) {
                        logicalTarget = resolver.resolveByServiceOperation(target.targetServiceTypeId, target.targetServiceId);
                    } else {
                        logicalTarget = Optional.empty();
                    }

                    if (logicalTarget.isPresent()) {
                        LogicalSeed seed = logicalTarget.get();
                        if (isComponentPrefix(seed.prefix)) {
                            result.componentTargets.merge(seed.code, seed, LogicalSeed::merge);
                        } else {
                            result.serviceTargets.merge(seed.code, seed, LogicalSeed::merge);
                        }
                    }

                    if ("Method".equals(target.targetLabel)
                        && !isBlank(target.targetSignature)
                        && visited.add(target.targetSignature)) {
                        nextFrontier.add(target.targetSignature);
                    }
                }
            }

            frontier = new ArrayList<>(nextFrontier);
        }

        return result;
    }

    private BoundaryResult discoverDataTargets(Collection<String> startSignatures,
                                              ProjectionQueryCache queryCache) {
        List<String> signatures = startSignatures.stream()
            .filter(sig -> !isBlank(sig))
            .distinct()
            .collect(Collectors.toList());
        if (signatures.isEmpty()) {
            return new BoundaryResult();
        }

        BoundaryResult result = new BoundaryResult();
        Map<String, List<TableTarget>> tableTargetsBySignature = queryCache.loadTableTargets(signatures);
        for (String signature : signatures) {
            for (TableTarget tableTarget : tableTargetsBySignature.getOrDefault(signature, List.of())) {
                result.tableTargets.putIfAbsent(tableTarget.code, tableTarget);
            }
        }
        return result;
    }

    private Map<String, TableTarget> mergeTableTargets(BoundaryResult primary, BoundaryResult extra) {
        Map<String, TableTarget> merged = new LinkedHashMap<>(primary.tableTargets);
        merged.putAll(extra.tableTargets);
        return merged;
    }

    private Map<String, Object> aggregateTableImpact(TableMeta rootTable,
                                                     List<TransactionImpactModel> models,
                                                     String tableId) {
        LinkedHashMap<String, Map<String, Object>> componentMap = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, Object>> serviceMap = new LinkedHashMap<>();
        LinkedHashMap<String, TxAggregate> txAggregates = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeSet = new LinkedHashSet<>();

        for (TransactionImpactModel model : models) {
            List<String> directComponentCodes = new ArrayList<>();
            for (ImpactNode component : model.components.values()) {
                List<String> tables = model.componentTableMap.getOrDefault(component.code, List.of());
                if (!tables.contains(tableId)) {
                    continue;
                }
                directComponentCodes.add(component.code);
                componentMap.putIfAbsent(component.code, component.toPayload());
                addEdge(edges, edgeSet, tableId, component.code, false);
            }

            Set<String> componentClosure = expandComponentClosure(
                directComponentCodes,
                model.componentToComponent,
                model.components.keySet()
            );
            for (String code : componentClosure) {
                ImpactNode component = model.components.get(code);
                if (component != null) {
                    componentMap.putIfAbsent(code, component.toPayload());
                }
            }
            for (Map.Entry<String, List<String>> entry : model.componentToComponent.entrySet()) {
                if (!componentClosure.contains(entry.getKey())) {
                    continue;
                }
                for (String callee : entry.getValue()) {
                    if (componentClosure.contains(callee)) {
                        addEdge(edges, edgeSet, entry.getKey(), callee, true);
                    }
                }
            }

            Set<String> matchingServices = new LinkedHashSet<>();
            for (ImpactNode service : model.services.values()) {
                List<String> tables = model.nodeToTable.getOrDefault(service.code, List.of());
                if (!tables.contains(tableId)) {
                    continue;
                }
                matchingServices.add(service.code);
                serviceMap.putIfAbsent(service.code, service.toPayload());
                addEdge(edges, edgeSet, tableId, service.code, false);
            }

            for (Map.Entry<String, List<String>> entry : model.serviceToComponent.entrySet()) {
                List<String> impactedComponents = entry.getValue().stream()
                    .filter(componentClosure::contains)
                    .collect(Collectors.toList());
                if (impactedComponents.isEmpty()) {
                    continue;
                }
                String serviceCode = entry.getKey();
                ImpactNode service = model.services.get(serviceCode);
                if (service == null) {
                    continue;
                }
                matchingServices.add(serviceCode);
                serviceMap.putIfAbsent(serviceCode, service.toPayload());
                for (String componentCode : impactedComponents) {
                    addEdge(edges, edgeSet, componentCode, serviceCode, false);
                }
            }

            boolean expanded = true;
            while (expanded) {
                expanded = false;
                for (String impactedServiceId : new ArrayList<>(matchingServices)) {
                    for (Map.Entry<String, List<String>> entry : model.serviceToService.entrySet()) {
                        String caller = entry.getKey();
                        if (matchingServices.contains(caller)) {
                            continue;
                        }
                        if (!(entry.getValue() == null ? List.<String>of() : entry.getValue()).contains(impactedServiceId)) {
                            continue;
                        }
                        ImpactNode callerNode = model.services.get(caller);
                        if (callerNode == null) {
                            continue;
                        }
                        matchingServices.add(caller);
                        serviceMap.putIfAbsent(caller, callerNode.toPayload());
                        expanded = true;
                    }
                }
            }

            for (Map.Entry<String, List<String>> entry : model.serviceToService.entrySet()) {
                if (!matchingServices.contains(entry.getKey())) {
                    continue;
                }
                for (String callee : entry.getValue()) {
                    if (matchingServices.contains(callee)) {
                        addEdge(edges, edgeSet, entry.getKey(), callee, true);
                    }
                }
            }

            if (!matchingServices.isEmpty()) {
                Set<String> directFlowServices = new LinkedHashSet<>(model.rootServiceCodes);
                directFlowServices.retainAll(matchingServices);
                if (!directFlowServices.isEmpty()) {
                    String txNameKey = firstNonBlank(model.txName, model.txId);
                    TxAggregate aggregate = txAggregates.computeIfAbsent(
                        txNameKey,
                        ignored -> new TxAggregate(txNameKey, model.txId, model.txCode, model.domainKey)
                    );
                    aggregate.absorb(model.txId, model.txCode, model.domainKey);
                    aggregate.serviceIds.addAll(directFlowServices);
                }
            }
        }

        List<Map<String, Object>> components = componentMap.values().stream()
            .sorted(Comparator.comparing(node -> String.valueOf(node.get("id"))))
            .collect(Collectors.toList());
        List<Map<String, Object>> services = serviceMap.values().stream()
            .sorted(Comparator.comparing(node -> String.valueOf(node.get("id"))))
            .collect(Collectors.toList());

        List<Map<String, Object>> transactions = new ArrayList<>();
        for (TxAggregate aggregate : txAggregates.values().stream()
            .sorted(Comparator.comparing(tx -> tx.representativeId))
            .collect(Collectors.toList())) {
            transactions.add(node(
                "id", aggregate.representativeId,
                "name", aggregate.name,
                "code", aggregate.representativeCode,
                "domainId", aggregate.domainKey,
                "nodeType", "transaction"
            ));
            for (String serviceId : aggregate.serviceIds) {
                addEdge(edges, edgeSet, serviceId, aggregate.representativeId, false);
            }
        }

        return node(
            "mode", "table",
            "root", rootTable.toPayload(),
            "levels", List.of(components, services, transactions),
            "edges", edges,
            "stats", node(
                "components", components.size(),
                "services", services.size(),
                "transactions", transactions.size()
            )
        );
    }

    private Map<String, Object> aggregateComponentImpact(ImpactNode rootComponent,
                                                         List<TransactionImpactModel> models,
                                                         String componentId) {
        LinkedHashMap<String, Map<String, Object>> serviceMap = new LinkedHashMap<>();
        LinkedHashMap<String, TxAggregate> txAggregates = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeSet = new LinkedHashSet<>();

        for (TransactionImpactModel model : models) {
            Set<String> matchingServices = new LinkedHashSet<>();

            for (Map.Entry<String, List<String>> entry : model.serviceToComponent.entrySet()) {
                if (!entry.getValue().contains(componentId)) {
                    continue;
                }
                String serviceCode = entry.getKey();
                ImpactNode service = model.services.get(serviceCode);
                if (service == null) {
                    continue;
                }
                matchingServices.add(serviceCode);
                serviceMap.putIfAbsent(serviceCode, service.toPayload());
                addEdge(edges, edgeSet, componentId, serviceCode, false);
            }

            boolean expanded = true;
            while (expanded) {
                expanded = false;
                for (String impactedServiceId : new ArrayList<>(matchingServices)) {
                    for (Map.Entry<String, List<String>> entry : model.serviceToService.entrySet()) {
                        String caller = entry.getKey();
                        if (matchingServices.contains(caller)) {
                            continue;
                        }
                        if (!entry.getValue().contains(impactedServiceId)) {
                            continue;
                        }
                        ImpactNode callerNode = model.services.get(caller);
                        if (callerNode == null) {
                            continue;
                        }
                        matchingServices.add(caller);
                        serviceMap.putIfAbsent(caller, callerNode.toPayload());
                        expanded = true;
                    }
                }
            }

            for (Map.Entry<String, List<String>> entry : model.serviceToService.entrySet()) {
                if (!matchingServices.contains(entry.getKey())) {
                    continue;
                }
                for (String callee : entry.getValue()) {
                    if (matchingServices.contains(callee)) {
                        addEdge(edges, edgeSet, entry.getKey(), callee, true);
                    }
                }
            }

            if (!matchingServices.isEmpty()) {
                Set<String> directFlowServices = new LinkedHashSet<>(model.rootServiceCodes);
                directFlowServices.retainAll(matchingServices);
                if (!directFlowServices.isEmpty()) {
                    String txNameKey = firstNonBlank(model.txName, model.txId);
                    TxAggregate aggregate = txAggregates.computeIfAbsent(
                        txNameKey,
                        ignored -> new TxAggregate(txNameKey, model.txId, model.txCode, model.domainKey)
                    );
                    aggregate.absorb(model.txId, model.txCode, model.domainKey);
                    aggregate.serviceIds.addAll(directFlowServices);
                }
            }
        }

        List<Map<String, Object>> services = serviceMap.values().stream()
            .sorted(Comparator.comparing(node -> String.valueOf(node.get("id"))))
            .collect(Collectors.toList());

        List<Map<String, Object>> transactions = new ArrayList<>();
        for (TxAggregate aggregate : txAggregates.values().stream()
            .sorted(Comparator.comparing(tx -> tx.representativeId))
            .collect(Collectors.toList())) {
            transactions.add(node(
                "id", aggregate.representativeId,
                "name", aggregate.name,
                "code", aggregate.representativeCode,
                "domainId", aggregate.domainKey,
                "nodeType", "transaction"
            ));
            for (String serviceId : aggregate.serviceIds) {
                addEdge(edges, edgeSet, serviceId, aggregate.representativeId, false);
            }
        }

        return node(
            "mode", "component",
            "root", rootComponent.toPayload(),
            "levels", List.of(services, transactions),
            "edges", edges,
            "stats", node(
                "services", services.size(),
                "transactions", transactions.size()
            )
        );
    }

    private Map<String, Object> aggregateServiceImpact(ImpactNode rootService,
                                                       List<TransactionImpactModel> models,
                                                       String serviceId,
                                                       Map<String, List<OrchestrationRef>> orchestrationRefsByService) {
        LinkedHashMap<String, Map<String, Object>> upstreamServiceMap = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, Object>> orchestrationMap = new LinkedHashMap<>();
        LinkedHashMap<String, TxAggregate> txAggregates = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeSet = new LinkedHashSet<>();

        Set<String> consideredServices = new LinkedHashSet<>();
        consideredServices.add(serviceId);

        for (TransactionImpactModel model : models) {
            if (!model.services.containsKey(serviceId)) {
                continue;
            }

            Set<String> upstreamServices = new LinkedHashSet<>();
            boolean expanded = true;
            while (expanded) {
                expanded = false;
                for (Map.Entry<String, List<String>> entry : model.serviceToService.entrySet()) {
                    String caller = entry.getKey();
                    ImpactNode callerNode = model.services.get(caller);
                    if (callerNode == null || !isCallableServiceNode(callerNode)) {
                        continue;
                    }

                    boolean reachesSelected = entry.getValue().stream()
                        .anyMatch(target -> serviceId.equals(target) || upstreamServices.contains(target));
                    if (reachesSelected && upstreamServices.add(caller)) {
                        upstreamServiceMap.putIfAbsent(caller, callerNode.toPayload());
                        expanded = true;
                    }
                }
            }

            for (Map.Entry<String, List<String>> entry : model.serviceToService.entrySet()) {
                String caller = entry.getKey();
                if (!upstreamServices.contains(caller)) {
                    continue;
                }
                for (String callee : entry.getValue()) {
                    if (serviceId.equals(callee)) {
                        addEdge(edges, edgeSet, serviceId, caller, false);
                    } else if (upstreamServices.contains(callee)) {
                        addEdge(edges, edgeSet, callee, caller, true);
                    }
                }
            }

            consideredServices.addAll(upstreamServices);
        }

        for (String sourceServiceId : consideredServices) {
            List<OrchestrationRef> refs = orchestrationRefsByService.getOrDefault(sourceServiceId, List.of());
            if (!serviceId.equals(sourceServiceId) && !upstreamServiceMap.containsKey(sourceServiceId)) {
                continue;
            }
            for (OrchestrationRef ref : refs) {
                orchestrationMap.putIfAbsent(ref.id, ref.toPayload());
                addEdge(edges, edgeSet, sourceServiceId, ref.id, false);

                String txNameKey = firstNonBlank(ref.txName, ref.txId);
                TxAggregate aggregate = txAggregates.computeIfAbsent(
                    txNameKey,
                    ignored -> new TxAggregate(txNameKey, ref.txId, ref.txId, ref.domainKey)
                );
                aggregate.absorb(ref.txId, ref.txId, ref.domainKey);
                aggregate.serviceIds.add(ref.id);
            }
        }

        List<Map<String, Object>> upstreamServices = upstreamServiceMap.values().stream()
            .sorted(Comparator.comparing(node -> String.valueOf(node.get("id"))))
            .collect(Collectors.toList());
        List<Map<String, Object>> orchestrations = orchestrationMap.values().stream()
            .sorted(Comparator.comparing(node -> String.valueOf(node.get("id"))))
            .collect(Collectors.toList());

        List<Map<String, Object>> transactions = new ArrayList<>();
        for (TxAggregate aggregate : txAggregates.values().stream()
            .sorted(Comparator.comparing(tx -> tx.representativeId))
            .collect(Collectors.toList())) {
            transactions.add(node(
                "id", aggregate.representativeId,
                "name", aggregate.name,
                "code", aggregate.representativeCode,
                "domainId", aggregate.domainKey,
                "nodeType", "transaction"
            ));
            for (String orchestrationId : aggregate.serviceIds) {
                addEdge(edges, edgeSet, orchestrationId, aggregate.representativeId, false);
            }
        }

        return node(
            "mode", "service",
            "root", rootService.toPayload(),
            "levels", List.of(upstreamServices, orchestrations, transactions),
            "edges", edges,
            "stats", node(
                "services", upstreamServices.size(),
                "upstreamServices", upstreamServices.size(),
                "orchestrations", orchestrations.size(),
                "transactions", transactions.size()
            )
        );
    }

    private <T> void indexModel(Map<String, List<T>> index,
                                Collection<String> keys,
                                T value) {
        if (keys == null || value == null) {
            return;
        }
        for (String key : keys) {
            if (isBlank(key)) {
                continue;
            }
            index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
    }

    private Map<String, List<OrchestrationRef>> loadDirectOrchestrationRefs(Session session,
                                                                            Collection<String> serviceCodes) {
        List<Map<String, String>> operations = serviceCodes.stream()
            .map(this::toServiceKey)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        if (operations.isEmpty()) {
            return Map.of();
        }

        Map<String, List<OrchestrationRef>> refs = new LinkedHashMap<>();

        session.run(
            "UNWIND $ops AS opRef " +
            "MATCH (op:ServiceOperation {serviceTypeId: opRef.serviceTypeId, serviceId: opRef.serviceId}) " +
            "MATCH (step:FlowServiceStep)-[:CALLS_SERVICE]->(op) " +
            "MATCH (tx:Transaction {id: step.txId}) " +
            "RETURN DISTINCT opRef.code AS serviceCode, " +
            "       step.key AS stepKey, " +
            "       coalesce(step.longname, step.id, step.serviceId) AS stepName, " +
            "       coalesce(op.nodeKind, 'service') AS stepType, " +
            "       coalesce(step.txId, tx.id) AS txId, " +
            "       coalesce(tx.longname, tx.id) AS txName, " +
            "       coalesce(op.domainKey, tx.domainKey) AS domainKey",
            Values.parameters("ops", operations))
            .forEachRemaining(record -> addOrchestrationRef(refs, new OrchestrationRef(
                stringValue(record, "serviceCode"),
                "ORCH:" + stringValue(record, "stepKey"),
                stringValue(record, "stepName"),
                stringValue(record, "stepType"),
                stringValue(record, "domainKey"),
                stringValue(record, "txId"),
                stringValue(record, "txName"),
                firstNonBlank(stringValue(record, "txId"), stringValue(record, "txName"))
            )));

        session.run(
            "UNWIND $ops AS opRef " +
            "MATCH (op:ServiceOperation {serviceTypeId: opRef.serviceTypeId, serviceId: opRef.serviceId})-[:IMPLEMENTS_BY]->(impl:Method) " +
            "MATCH (step:FlowMethodStep)-[:RESOLVES_TO_METHOD]->(impl) " +
            "MATCH (tx:Transaction {id: step.txId}) " +
            "RETURN DISTINCT opRef.code AS serviceCode, " +
            "       step.key AS stepKey, " +
            "       coalesce(step.longname, step.id, step.methodName) AS stepName, " +
            "       'method' AS stepType, " +
            "       coalesce(step.txId, tx.id) AS txId, " +
            "       coalesce(tx.longname, tx.id) AS txName, " +
            "       coalesce(tx.domainKey, op.domainKey) AS domainKey",
            Values.parameters("ops", operations))
            .forEachRemaining(record -> addOrchestrationRef(refs, new OrchestrationRef(
                stringValue(record, "serviceCode"),
                "ORCH:" + stringValue(record, "stepKey"),
                stringValue(record, "stepName"),
                stringValue(record, "stepType"),
                stringValue(record, "domainKey"),
                stringValue(record, "txId"),
                stringValue(record, "txName"),
                firstNonBlank(stringValue(record, "txId"), stringValue(record, "txName"))
            )));

        return refs;
    }

    private Map<String, Object> emptyImpactResult(TableMeta table) {
        return node(
            "mode", "table",
            "root", table.toPayload(),
            "levels", List.of(List.of(), List.of(), List.of()),
            "edges", List.of(),
            "stats", node(
                "components", 0,
                "services", 0,
                "transactions", 0
            )
        );
    }

    private Map<String, Object> emptyComponentImpactResult(ImpactNode component) {
        return node(
            "mode", "component",
            "root", component.toPayload(),
            "levels", List.of(List.of(), List.of()),
            "edges", List.of(),
            "stats", node(
                "services", 0,
                "transactions", 0
            )
        );
    }

    private Map<String, Object> emptyServiceImpactResult(ImpactNode service) {
        return node(
            "mode", "service",
            "root", service.toPayload(),
            "levels", List.of(List.of(), List.of(), List.of()),
            "edges", List.of(),
            "stats", node(
                "services", 0,
                "upstreamServices", 0,
                "orchestrations", 0,
                "transactions", 0
            )
        );
    }

    private Set<String> expandComponentClosure(Collection<String> seedCodes,
                                               Map<String, List<String>> componentToComponent,
                                               Collection<String> validCodes) {
        Set<String> valid = new LinkedHashSet<>(validCodes);
        Set<String> active = new LinkedHashSet<>();
        for (String code : seedCodes) {
            if (valid.contains(code)) {
                active.add(code);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String code : new ArrayList<>(active)) {
                for (String target : componentToComponent.getOrDefault(code, List.of())) {
                    if (valid.contains(target) && !active.contains(target)) {
                        active.add(target);
                        changed = true;
                    }
                }
                for (Map.Entry<String, List<String>> entry : componentToComponent.entrySet()) {
                    if (!valid.contains(entry.getKey())) {
                        continue;
                    }
                    if (entry.getValue().contains(code) && !active.contains(entry.getKey())) {
                        active.add(entry.getKey());
                        changed = true;
                    }
                }
            }
        }
        return active;
    }

    private ImpactNode displayNode(String code,
                                   String name,
                                   String prefix,
                                   String txDomainKey,
                                   String nodeDomainKey) {
        String typeId = resolveTypeId(code);
        Optional<NodeCacheEntry> cacheEntry = serviceNodeCache.get(code);
        Optional<ServiceTypeFileMeta> fileMeta = resolveServiceFileMeta(typeId);
        String resolvedPrefix = normalizePrefix(firstNonBlank(
            fileMeta.map(ServiceTypeFileMeta::getNodeKind).orElse(null),
            cacheEntry.map(NodeCacheEntry::getNodeKind).orElse(null),
            prefix
        ));
        String resolvedDomainKey = firstNonBlank(
            fileMeta.map(ServiceTypeFileMeta::getDomainKey).orElse(null),
            cacheEntry.map(NodeCacheEntry::getDomainKey).orElse(null),
            nodeDomainKey,
            txDomainKey
        );
        return new ImpactNode(
            code,
            firstNonBlank(name, code),
            resolvedPrefix,
            resolvedDomainKey,
            isComponentPrefix(resolvedPrefix) ? "component" : "service"
        );
    }

    private Optional<ServiceTypeFileMeta> resolveServiceFileMeta(String serviceTypeId) {
        if (isBlank(serviceTypeId)) {
            return Optional.empty();
        }
        return flowServiceMetadataResolver.findByTypeId(serviceTypeId);
    }

    private String resolveTypeId(String code) {
        if (isBlank(code)) {
            return null;
        }
        int split = code.indexOf('.');
        return split > 0 ? code.substring(0, split) : code;
    }

    private String buildServiceCode(String serviceTypeId, String serviceId) {
        if (isBlank(serviceTypeId) || isBlank(serviceId)) {
            return null;
        }
        return serviceTypeId + "." + serviceId;
    }

    private void addRelation(Map<String, List<String>> relations, String fromCode, String toCode) {
        if (isBlank(fromCode) || isBlank(toCode) || fromCode.equals(toCode)) {
            return;
        }
        List<String> values = relations.computeIfAbsent(fromCode, ignored -> new ArrayList<>());
        if (!values.contains(toCode)) {
            values.add(toCode);
        }
    }

    private void addEdge(List<Map<String, Object>> edges,
                         Set<String> edgeSet,
                         String from,
                         String to,
                         boolean intraLayer) {
        if (isBlank(from) || isBlank(to)) {
            return;
        }
        String key = from + "->" + to;
        if (!edgeSet.add(key)) {
            return;
        }
        Map<String, Object> edge = node("from", from, "to", to);
        if (intraLayer) {
            edge.put("isIntraLayer", true);
        }
        edges.add(edge);
    }

    private String normalizePrefix(String value) {
        if (isBlank(value)) {
            return "pbs";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isComponentPrefix(String prefix) {
        return prefix != null && prefix.startsWith("pbc");
    }

    private boolean isCallableServiceNode(ImpactNode node) {
        return node != null && "service".equals(node.nodeType) && !"method".equals(node.prefix);
    }

    private Map<String, String> toServiceKey(String code) {
        if (isBlank(code) || !code.contains(".")) {
            return null;
        }
        int split = code.indexOf('.');
        String serviceTypeId = code.substring(0, split);
        String serviceId = code.substring(split + 1);
        if (isBlank(serviceTypeId) || isBlank(serviceId)) {
            return null;
        }
        return Map.of(
            "code", code,
            "serviceTypeId", serviceTypeId,
            "serviceId", serviceId
        );
    }

    private void addOrchestrationRef(Map<String, List<OrchestrationRef>> refs,
                                     OrchestrationRef ref) {
        if (ref == null || isBlank(ref.serviceCode) || isBlank(ref.id)) {
            return;
        }
        List<OrchestrationRef> items = refs.computeIfAbsent(ref.serviceCode, ignored -> new ArrayList<>());
        boolean exists = items.stream().anyMatch(existing -> existing.id.equals(ref.id));
        if (!exists) {
            items.add(ref);
        }
    }

    private List<String> listAsStrings(Value value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        return value.asList(item -> item == null || item.isNull() ? null : item.asString()).stream()
            .filter(item -> !isBlank(item))
            .distinct()
            .collect(Collectors.toList());
    }

    private String stringValue(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private Map<String, Object> node(Object... kvs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            Object value = kvs[i + 1];
            if (value != null) {
                map.put(String.valueOf(kvs[i]), value);
            }
        }
        return map;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private final class ProjectionQueryCache {
        private final Session session;
        private final Map<String, List<CallTarget>> outgoingCallsBySignature = new HashMap<>();
        private final Map<String, List<TableTarget>> tableTargetsBySignature = new HashMap<>();

        private ProjectionQueryCache(Session session) {
            this.session = session;
        }

        private Map<String, List<CallTarget>> loadOutgoingCalls(Collection<String> methodSignatures) {
            List<String> signatures = methodSignatures.stream()
                .filter(signature -> !isBlank(signature))
                .distinct()
                .collect(Collectors.toList());
            if (signatures.isEmpty()) {
                return Map.of();
            }

            List<String> missing = signatures.stream()
                .filter(signature -> !outgoingCallsBySignature.containsKey(signature))
                .collect(Collectors.toList());
            if (!missing.isEmpty()) {
                Map<String, List<CallTarget>> loaded = new HashMap<>();
                session.run(
                    "UNWIND $signatures AS signature " +
                    "MATCH (m:Method {signature: signature})-[r:CALLS|SYS_UTIL_CALLS|SELF_CALLS|DAO_CALLS]->(target) " +
                    "OPTIONAL MATCH (table:Table)-[:EXPOSES_DAO]->(target) " +
                    "RETURN signature AS sourceSignature, " +
                    "       type(r) AS relationType, " +
                    "       r.methodName AS edgeMethodName, " +
                    "       labels(target)[0] AS targetLabel, " +
                    "       target.signature AS targetSignature, " +
                    "       target.fqn AS targetFqn, " +
                    "       target.name AS targetName, " +
                    "       target.methodName AS targetMethodName, " +
                    "       coalesce(target.daoClassName, table.daoClassName) AS targetDaoClassName, " +
                    "       coalesce(table.id, target.tableId) AS targetTableId, " +
                    "       coalesce(table.longname, target.tableLongname) AS targetTableLongname, " +
                    "       coalesce(table.domainKey, target.domainKey) AS targetDomainKey, " +
                    "       coalesce(table.projectName, target.projectName) AS targetProjectName, " +
                    "       target.serviceTypeId AS targetServiceTypeId, " +
                    "       target.serviceId AS targetServiceId",
                    Values.parameters("signatures", missing))
                    .forEachRemaining(record -> {
                        String sourceSignature = stringValue(record, "sourceSignature");
                        loaded.computeIfAbsent(sourceSignature, ignored -> new ArrayList<>()).add(new CallTarget(
                            stringValue(record, "relationType"),
                            stringValue(record, "edgeMethodName"),
                            stringValue(record, "targetLabel"),
                            stringValue(record, "targetSignature"),
                            stringValue(record, "targetFqn"),
                            stringValue(record, "targetName"),
                            stringValue(record, "targetMethodName"),
                            stringValue(record, "targetDaoClassName"),
                            stringValue(record, "targetTableId"),
                            stringValue(record, "targetTableLongname"),
                            stringValue(record, "targetDomainKey"),
                            stringValue(record, "targetProjectName"),
                            stringValue(record, "targetServiceTypeId"),
                            stringValue(record, "targetServiceId")
                        ));
                    });
                for (String signature : missing) {
                    outgoingCallsBySignature.put(signature, List.copyOf(loaded.getOrDefault(signature, List.of())));
                }
            }

            Map<String, List<CallTarget>> result = new HashMap<>();
            for (String signature : signatures) {
                result.put(signature, outgoingCallsBySignature.getOrDefault(signature, List.of()));
            }
            return result;
        }

        private Map<String, List<TableTarget>> loadTableTargets(Collection<String> methodSignatures) {
            List<String> signatures = methodSignatures.stream()
                .filter(signature -> !isBlank(signature))
                .distinct()
                .collect(Collectors.toList());
            if (signatures.isEmpty()) {
                return Map.of();
            }

            List<String> missing = signatures.stream()
                .filter(signature -> !tableTargetsBySignature.containsKey(signature))
                .collect(Collectors.toList());
            if (!missing.isEmpty()) {
                Map<String, List<TableTarget>> loaded = new HashMap<>();
                session.run(
                    "UNWIND $signatures AS signature " +
                    "MATCH (entry:Method {signature: signature}) " +
                    "MATCH p = (entry)-[:CALLS|SELF_CALLS*0.." + MAX_METHOD_DEPTH + "]->(:Method)-[:DAO_CALLS]->(dao:DaoMethod) " +
                    "OPTIONAL MATCH (table:Table)-[:EXPOSES_DAO]->(dao) " +
                    "RETURN DISTINCT signature AS sourceSignature, " +
                    "       coalesce(table.id, dao.tableId) AS tableId, " +
                    "       coalesce(table.longname, dao.tableLongname, dao.tableId) AS tableLongname, " +
                    "       coalesce(table.domainKey, dao.domainKey) AS domainKey, " +
                    "       coalesce(table.projectName, dao.projectName) AS projectName, " +
                    "       coalesce(table.daoClassName, dao.daoClassName) AS daoClassName",
                    Values.parameters("signatures", missing))
                    .forEachRemaining(record -> {
                        String tableId = stringValue(record, "tableId");
                        if (isBlank(tableId)) {
                            return;
                        }
                        String sourceSignature = stringValue(record, "sourceSignature");
                        loaded.computeIfAbsent(sourceSignature, ignored -> new ArrayList<>()).add(new TableTarget(
                            tableId,
                            firstNonBlank(stringValue(record, "tableLongname"), tableId),
                            stringValue(record, "domainKey"),
                            stringValue(record, "projectName"),
                            stringValue(record, "daoClassName")
                        ));
                    });
                for (String signature : missing) {
                    tableTargetsBySignature.put(signature, List.copyOf(loaded.getOrDefault(signature, List.of())));
                }
            }

            Map<String, List<TableTarget>> result = new HashMap<>();
            for (String signature : signatures) {
                result.put(signature, tableTargetsBySignature.getOrDefault(signature, List.of()));
            }
            return result;
        }

        private int outgoingCacheSize() {
            return outgoingCallsBySignature.size();
        }

        private int tableTargetCacheSize() {
            return tableTargetsBySignature.size();
        }
    }

    private final class BoundaryResolver {
        private final Session session;
        private final Map<String, Optional<LogicalSeed>> methodCache = new HashMap<>();
        private final Map<String, Optional<LogicalSeed>> implClassCache = new HashMap<>();
        private final Map<String, Optional<LogicalSeed>> interfaceCache = new HashMap<>();
        private final Map<String, Optional<LogicalSeed>> operationCache = new HashMap<>();

        private BoundaryResolver(Session session) {
            this.session = session;
        }

        private void preload(Map<String, List<CallTarget>> outgoingBySource,
                             Collection<String> sourceSignatures) {
            LinkedHashSet<String> methodSignatures = new LinkedHashSet<>();
            LinkedHashMap<String, LookupRef> implRefs = new LinkedHashMap<>();
            LinkedHashMap<String, LookupRef> interfaceRefs = new LinkedHashMap<>();
            LinkedHashMap<String, ServiceOperationRef> operationRefs = new LinkedHashMap<>();

            for (String sourceSignature : sourceSignatures) {
                for (CallTarget target : outgoingBySource.getOrDefault(sourceSignature, List.of())) {
                    if ("Method".equals(target.targetLabel) && !isBlank(target.targetSignature)) {
                        methodSignatures.add(target.targetSignature);
                    } else if ("Class".equals(target.targetLabel)
                        && !isBlank(target.targetFqn)
                        && !isBlank(target.edgeMethodName)) {
                        String key = lookupKey(target.targetFqn, target.edgeMethodName);
                        implRefs.putIfAbsent(key, new LookupRef(key, target.targetFqn, target.edgeMethodName));
                    } else if ("Interface".equals(target.targetLabel)
                        && !isBlank(target.targetFqn)
                        && !isBlank(target.edgeMethodName)) {
                        String key = lookupKey(target.targetFqn, target.edgeMethodName);
                        interfaceRefs.putIfAbsent(key, new LookupRef(key, target.targetFqn, target.edgeMethodName));
                    } else if ("ServiceOperation".equals(target.targetLabel)
                        && !isBlank(target.targetServiceTypeId)
                        && !isBlank(target.targetServiceId)) {
                        String key = serviceOperationKey(target.targetServiceTypeId, target.targetServiceId);
                        operationRefs.putIfAbsent(
                            key,
                            new ServiceOperationRef(key, target.targetServiceTypeId, target.targetServiceId)
                        );
                    }
                }
            }

            preloadMethodSignatures(methodSignatures);
            preloadImplementationMethods(implRefs.values());
            preloadInterfaceMethods(interfaceRefs.values());
            preloadServiceOperations(operationRefs.values());
        }

        private Optional<LogicalSeed> resolveByMethodSignature(String methodSignature) {
            if (isBlank(methodSignature)) {
                return Optional.empty();
            }
            preloadMethodSignatures(List.of(methodSignature));
            return methodCache.getOrDefault(methodSignature, Optional.empty());
        }

        private Optional<LogicalSeed> resolveByImplementationClass(String classFqn, String methodName) {
            if (isBlank(classFqn) || isBlank(methodName)) {
                return Optional.empty();
            }
            String cacheKey = classFqn + "#" + methodName;
            preloadImplementationMethods(List.of(new LookupRef(cacheKey, classFqn, methodName)));
            return implClassCache.getOrDefault(cacheKey, Optional.empty());
        }

        private Optional<LogicalSeed> resolveByInterfaceMethod(String interfaceFqn, String methodName) {
            if (isBlank(interfaceFqn) || isBlank(methodName)) {
                return Optional.empty();
            }
            String cacheKey = interfaceFqn + "#" + methodName;
            preloadInterfaceMethods(List.of(new LookupRef(cacheKey, interfaceFqn, methodName)));
            return interfaceCache.getOrDefault(cacheKey, Optional.empty());
        }

        private Optional<LogicalSeed> resolveByServiceOperation(String serviceTypeId, String serviceId) {
            if (isBlank(serviceTypeId) || isBlank(serviceId)) {
                return Optional.empty();
            }
            String cacheKey = serviceTypeId + "." + serviceId;
            preloadServiceOperations(List.of(new ServiceOperationRef(cacheKey, serviceTypeId, serviceId)));
            return operationCache.getOrDefault(cacheKey, Optional.empty());
        }

        private void preloadMethodSignatures(Collection<String> methodSignatures) {
            List<String> missing = methodSignatures.stream()
                .filter(signature -> !isBlank(signature))
                .distinct()
                .filter(signature -> !methodCache.containsKey(signature))
                .collect(Collectors.toList());
            if (missing.isEmpty()) {
                return;
            }

            Map<String, Optional<LogicalSeed>> loaded = new HashMap<>();
            for (List<String> batch : partition(missing, PRELOAD_BATCH_SIZE)) {
                session.run(
                    "UNWIND $values AS value " +
                    "MATCH (op:ServiceOperation)-[:IMPLEMENTS_BY]->(m:Method {signature: value}) " +
                    "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(stype:ServiceType) " +
                    "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
                    "WITH value, op, stype, collect(DISTINCT impl.signature) AS implSignatures " +
                    "RETURN value AS cacheKey, " +
                    "       op.serviceTypeId AS serviceTypeId, " +
                    "       op.serviceId AS serviceId, " +
                    "       coalesce(op.longname, op.serviceId, op.methodName) AS serviceName, " +
                    "       stype.nodeKind AS nodeKind, " +
                    "       stype.domainKey AS domainKey, " +
                    "       implSignatures",
                    Values.parameters("values", batch))
                    .forEachRemaining(record -> loaded.putIfAbsent(stringValue(record, "cacheKey"), toLogicalSeed(record)));
            }

            for (String signature : missing) {
                methodCache.put(signature, loaded.getOrDefault(signature, Optional.empty()));
            }
        }

        private void preloadImplementationMethods(Collection<LookupRef> refs) {
            List<LookupRef> missing = refs.stream()
                .filter(ref -> ref != null && !isBlank(ref.ownerFqn) && !isBlank(ref.methodName))
                .filter(ref -> !implClassCache.containsKey(ref.key))
                .collect(Collectors.toList());
            if (missing.isEmpty()) {
                return;
            }

            Map<String, Optional<LogicalSeed>> loaded = new HashMap<>();
            for (List<LookupRef> batch : partition(missing, PRELOAD_BATCH_SIZE)) {
                List<Map<String, String>> items = batch.stream()
                    .map(ref -> Map.of("key", ref.key, "classFqn", ref.ownerFqn, "methodName", ref.methodName))
                    .collect(Collectors.toList());
                session.run(
                    "UNWIND $items AS item " +
                    "MATCH (op:ServiceOperation)-[:IMPLEMENTS_BY]->(matched:Method {classFqn: item.classFqn, name: item.methodName}) " +
                    "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(stype:ServiceType) " +
                    "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
                    "WITH item.key AS cacheKey, op, stype, collect(DISTINCT impl.signature) AS implSignatures " +
                    "RETURN cacheKey, " +
                    "       op.serviceTypeId AS serviceTypeId, " +
                    "       op.serviceId AS serviceId, " +
                    "       coalesce(op.longname, op.serviceId, op.methodName) AS serviceName, " +
                    "       stype.nodeKind AS nodeKind, " +
                    "       stype.domainKey AS domainKey, " +
                    "       implSignatures",
                    Values.parameters("items", items))
                    .forEachRemaining(record -> loaded.putIfAbsent(stringValue(record, "cacheKey"), toLogicalSeed(record)));
            }

            for (LookupRef ref : missing) {
                implClassCache.put(ref.key, loaded.getOrDefault(ref.key, Optional.empty()));
            }
        }

        private void preloadInterfaceMethods(Collection<LookupRef> refs) {
            List<LookupRef> missing = refs.stream()
                .filter(ref -> ref != null && !isBlank(ref.ownerFqn) && !isBlank(ref.methodName))
                .filter(ref -> !interfaceCache.containsKey(ref.key))
                .collect(Collectors.toList());
            if (missing.isEmpty()) {
                return;
            }

            Map<String, Optional<LogicalSeed>> loaded = new HashMap<>();
            for (List<LookupRef> batch : partition(missing, PRELOAD_BATCH_SIZE)) {
                List<Map<String, String>> items = batch.stream()
                    .map(ref -> Map.of("key", ref.key, "interfaceFqn", ref.ownerFqn, "methodName", ref.methodName))
                    .collect(Collectors.toList());
                session.run(
                    "UNWIND $items AS item " +
                    "MATCH (stype:ServiceType {interfaceFqn: item.interfaceFqn})-[:DECLARES_OPERATION]->(op:ServiceOperation {methodName: item.methodName}) " +
                    "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
                    "WITH item.key AS cacheKey, op, stype, collect(DISTINCT impl.signature) AS implSignatures " +
                    "RETURN cacheKey, " +
                    "       op.serviceTypeId AS serviceTypeId, " +
                    "       op.serviceId AS serviceId, " +
                    "       coalesce(op.longname, op.serviceId, op.methodName) AS serviceName, " +
                    "       stype.nodeKind AS nodeKind, " +
                    "       stype.domainKey AS domainKey, " +
                    "       implSignatures",
                    Values.parameters("items", items))
                    .forEachRemaining(record -> loaded.putIfAbsent(stringValue(record, "cacheKey"), toLogicalSeed(record)));
            }

            for (LookupRef ref : missing) {
                interfaceCache.put(ref.key, loaded.getOrDefault(ref.key, Optional.empty()));
            }
        }

        private void preloadServiceOperations(Collection<ServiceOperationRef> refs) {
            List<ServiceOperationRef> missing = refs.stream()
                .filter(ref -> ref != null && !isBlank(ref.serviceTypeId) && !isBlank(ref.serviceId))
                .filter(ref -> !operationCache.containsKey(ref.key))
                .collect(Collectors.toList());
            if (missing.isEmpty()) {
                return;
            }

            Map<String, Optional<LogicalSeed>> loaded = new HashMap<>();
            for (List<ServiceOperationRef> batch : partition(missing, PRELOAD_BATCH_SIZE)) {
                List<Map<String, String>> items = batch.stream()
                    .map(ref -> Map.of(
                        "key", ref.key,
                        "serviceTypeId", ref.serviceTypeId,
                        "serviceId", ref.serviceId
                    ))
                    .collect(Collectors.toList());
                session.run(
                    "UNWIND $items AS item " +
                    "MATCH (op:ServiceOperation {serviceTypeId: item.serviceTypeId, serviceId: item.serviceId}) " +
                    "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(stype:ServiceType) " +
                    "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
                    "WITH item.key AS cacheKey, op, stype, collect(DISTINCT impl.signature) AS implSignatures " +
                    "RETURN cacheKey, " +
                    "       op.serviceTypeId AS serviceTypeId, " +
                    "       op.serviceId AS serviceId, " +
                    "       coalesce(op.longname, op.serviceId, op.methodName) AS serviceName, " +
                    "       stype.nodeKind AS nodeKind, " +
                    "       stype.domainKey AS domainKey, " +
                    "       implSignatures",
                    Values.parameters("items", items))
                    .forEachRemaining(record -> loaded.putIfAbsent(stringValue(record, "cacheKey"), toLogicalSeed(record)));
            }

            for (ServiceOperationRef ref : missing) {
                operationCache.put(ref.key, loaded.getOrDefault(ref.key, Optional.empty()));
            }
        }

        private Optional<LogicalSeed> toLogicalSeed(Record record) {
            String serviceTypeId = stringValue(record, "serviceTypeId");
            String serviceId = stringValue(record, "serviceId");
            String code = buildServiceCode(serviceTypeId, serviceId);
            if (isBlank(code)) {
                return Optional.empty();
            }

            Optional<NodeCacheEntry> cacheEntry = serviceNodeCache.get(code);
            Optional<ServiceTypeFileMeta> fileMeta = resolveServiceFileMeta(serviceTypeId);
            String name = firstNonBlank(
                stringValue(record, "serviceName"),
                cacheEntry.map(NodeCacheEntry::getServiceLongname).orElse(null),
                code
            );
            String prefix = normalizePrefix(firstNonBlank(
                stringValue(record, "nodeKind"),
                cacheEntry.map(NodeCacheEntry::getNodeKind).orElse(null),
                fileMeta.map(ServiceTypeFileMeta::getNodeKind).orElse(null),
                "pbs"
            ));
            String domainKey = firstNonBlank(
                stringValue(record, "domainKey"),
                cacheEntry.map(NodeCacheEntry::getDomainKey).orElse(null),
                fileMeta.map(ServiceTypeFileMeta::getDomainKey).orElse(null)
            );
            return Optional.of(new LogicalSeed(
                code,
                name,
                prefix,
                domainKey,
                listAsStrings(record.get("implSignatures"))
            ));
        }

        private String lookupKey(String ownerFqn, String methodName) {
            return ownerFqn + "#" + methodName;
        }

        private String serviceOperationKey(String serviceTypeId, String serviceId) {
            return serviceTypeId + "." + serviceId;
        }

        private <T> List<List<T>> partition(List<T> items, int size) {
            if (items.isEmpty()) {
                return List.of();
            }
            List<List<T>> parts = new ArrayList<>();
            for (int start = 0; start < items.size(); start += size) {
                parts.add(items.subList(start, Math.min(items.size(), start + size)));
            }
            return parts;
        }
    }

    private static final class LookupRef {
        private final String key;
        private final String ownerFqn;
        private final String methodName;

        private LookupRef(String key, String ownerFqn, String methodName) {
            this.key = key;
            this.ownerFqn = ownerFqn;
            this.methodName = methodName;
        }
    }

    private static final class ServiceOperationRef {
        private final String key;
        private final String serviceTypeId;
        private final String serviceId;

        private ServiceOperationRef(String key, String serviceTypeId, String serviceId) {
            this.key = key;
            this.serviceTypeId = serviceTypeId;
            this.serviceId = serviceId;
        }
    }

    private static final class TransactionMeta {
        private final String id;
        private final String longname;
        private final String domainKey;
        private final String parentProject;

        private TransactionMeta(String id, String longname, String domainKey, String parentProject) {
            this.id = id;
            this.longname = longname;
            this.domainKey = domainKey;
            this.parentProject = parentProject;
        }
    }

    private static final class FlowStepRef {
        private final String code;
        private final String name;
        private final String prefix;
        private final String domainKey;
        private final List<String> methodSignatures;

        private FlowStepRef(String code,
                            String name,
                            String prefix,
                            String domainKey,
                            List<String> methodSignatures) {
            this.code = code;
            this.name = name;
            this.prefix = prefix;
            this.domainKey = domainKey;
            this.methodSignatures = methodSignatures == null ? List.of() : List.copyOf(methodSignatures);
        }
    }

    private static final class LogicalSeed {
        private final String code;
        private final String name;
        private final String prefix;
        private final String domainKey;
        private final LinkedHashSet<String> methodSignatures;

        private LogicalSeed(String code,
                            String name,
                            String prefix,
                            String domainKey,
                            Collection<String> methodSignatures) {
            this.code = code;
            this.name = name;
            this.prefix = prefix;
            this.domainKey = domainKey;
            this.methodSignatures = new LinkedHashSet<>();
            if (methodSignatures != null) {
                this.methodSignatures.addAll(methodSignatures.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            }
        }

        private LogicalSeed merge(LogicalSeed other) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(methodSignatures);
            merged.addAll(other.methodSignatures);
            return new LogicalSeed(
                code,
                firstNonBlank(name, other.name, code),
                firstNonBlank(prefix, other.prefix),
                firstNonBlank(domainKey, other.domainKey),
                merged
            );
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }

    private static final class BoundaryResult {
        private final LinkedHashMap<String, LogicalSeed> serviceTargets = new LinkedHashMap<>();
        private final LinkedHashMap<String, LogicalSeed> componentTargets = new LinkedHashMap<>();
        private final LinkedHashMap<String, TableTarget> tableTargets = new LinkedHashMap<>();
    }

    private static final class CallTarget {
        private final String relationType;
        private final String edgeMethodName;
        private final String targetLabel;
        private final String targetSignature;
        private final String targetFqn;
        private final String targetName;
        private final String targetMethodName;
        private final String targetDaoClassName;
        private final String targetTableId;
        private final String targetTableLongname;
        private final String targetDomainKey;
        private final String targetProjectName;
        private final String targetServiceTypeId;
        private final String targetServiceId;

        private CallTarget(String relationType,
                           String edgeMethodName,
                           String targetLabel,
                           String targetSignature,
                           String targetFqn,
                           String targetName,
                           String targetMethodName,
                           String targetDaoClassName,
                           String targetTableId,
                           String targetTableLongname,
                           String targetDomainKey,
                           String targetProjectName,
                           String targetServiceTypeId,
                           String targetServiceId) {
            this.relationType = relationType;
            this.edgeMethodName = edgeMethodName;
            this.targetLabel = targetLabel;
            this.targetSignature = targetSignature;
            this.targetFqn = targetFqn;
            this.targetName = targetName;
            this.targetMethodName = targetMethodName;
            this.targetDaoClassName = targetDaoClassName;
            this.targetTableId = targetTableId;
            this.targetTableLongname = targetTableLongname;
            this.targetDomainKey = targetDomainKey;
            this.targetProjectName = targetProjectName;
            this.targetServiceTypeId = targetServiceTypeId;
            this.targetServiceId = targetServiceId;
        }
    }

    private static final class OrchestrationRef {
        private final String serviceCode;
        private final String id;
        private final String name;
        private final String type;
        private final String domainKey;
        private final String txId;
        private final String txName;
        private final String code;

        private OrchestrationRef(String serviceCode,
                                 String id,
                                 String name,
                                 String type,
                                 String domainKey,
                                 String txId,
                                 String txName,
                                 String code) {
            this.serviceCode = serviceCode;
            this.id = id;
            this.name = name;
            this.type = type;
            this.domainKey = domainKey;
            this.txId = txId;
            this.txName = txName;
            this.code = code;
        }

        private Map<String, Object> toPayload() {
            return node(
                "id", id,
                "name", name,
                "type", type,
                "domainId", domainKey,
                "nodeType", "orchestration",
                "txId", txId,
                "txName", txName,
                "code", code
            );
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
    }

    private static final class ImpactNode {
        private final String code;
        private final String name;
        private final String prefix;
        private final String domainKey;
        private final String nodeType;

        private ImpactNode(String code, String name, String prefix, String domainKey, String nodeType) {
            this.code = code;
            this.name = name;
            this.prefix = prefix;
            this.domainKey = domainKey;
            this.nodeType = nodeType;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", code);
            payload.put("name", name);
            payload.put("type", prefix);
            payload.put("domainId", domainKey);
            payload.put("nodeType", nodeType);
            return payload;
        }
    }

    private static final class TableMeta {
        private final String id;
        private final String longname;
        private final String domainKey;
        private final String projectName;
        private final String daoClassName;

        private TableMeta(String id, String longname, String domainKey, String projectName, String daoClassName) {
            this.id = id;
            this.longname = longname;
            this.domainKey = domainKey;
            this.projectName = projectName;
            this.daoClassName = daoClassName;
        }

        private Map<String, Object> toPayload() {
            return payloadFor(id, longname, domainKey, projectName);
        }

        private static Map<String, Object> payloadFor(String id,
                                                      String longname,
                                                      String domainKey,
                                                      String projectName) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("name", longname);
            payload.put("desc", projectName);
            payload.put("domainId", domainKey);
            payload.put("nodeType", "table");
            return payload;
        }
    }

    private static final class TableTarget {
        private final String code;
        private final String name;
        private final String domainKey;
        private final String projectName;
        private final String daoClassName;

        private TableTarget(String code,
                            String name,
                            String domainKey,
                            String projectName,
                            String daoClassName) {
            this.code = code;
            this.name = name;
            this.domainKey = domainKey;
            this.projectName = projectName;
            this.daoClassName = daoClassName;
        }
    }

    private static final class TransactionImpactModel {
        private final String txId;
        private final String txName;
        private final String txCode;
        private final String domainKey;
        private final Map<String, ImpactNode> services;
        private final Map<String, ImpactNode> components;
        private final Map<String, TableTarget> tables;
        private final Map<String, List<String>> serviceToService;
        private final Map<String, List<String>> serviceToComponent;
        private final Map<String, List<String>> componentToComponent;
        private final Map<String, List<String>> nodeToTable;
        private final Map<String, List<String>> componentTableMap;
        private final Set<String> rootServiceCodes;

        private TransactionImpactModel(String txId,
                                       String txName,
                                       String txCode,
                                       String domainKey,
                                       Map<String, ImpactNode> services,
                                       Map<String, ImpactNode> components,
                                       Map<String, TableTarget> tables,
                                       Map<String, List<String>> serviceToService,
                                       Map<String, List<String>> serviceToComponent,
                                       Map<String, List<String>> componentToComponent,
                                       Map<String, List<String>> nodeToTable,
                                       Map<String, List<String>> componentTableMap,
                                       Set<String> rootServiceCodes) {
            this.txId = txId;
            this.txName = txName;
            this.txCode = txCode;
            this.domainKey = domainKey;
            this.services = services;
            this.components = components;
            this.tables = tables;
            this.serviceToService = serviceToService;
            this.serviceToComponent = serviceToComponent;
            this.componentToComponent = componentToComponent;
            this.nodeToTable = nodeToTable;
            this.componentTableMap = componentTableMap;
            this.rootServiceCodes = rootServiceCodes;
        }
    }

    private static final class TxAggregate {
        private final String name;
        private String representativeId;
        private String representativeCode;
        private String domainKey;
        private final LinkedHashSet<String> serviceIds = new LinkedHashSet<>();

        private TxAggregate(String name, String representativeId, String representativeCode, String domainKey) {
            this.name = name;
            this.representativeId = representativeId;
            this.representativeCode = representativeCode;
            this.domainKey = domainKey;
        }

        private void absorb(String txId, String txCode, String txDomainKey) {
            if (isBlankStatic(representativeId) || compareIds(txId, representativeId) < 0) {
                representativeId = txId;
                representativeCode = txCode;
                domainKey = txDomainKey;
            }
        }

        private static int compareIds(String left, String right) {
            if (left == null && right == null) {
                return 0;
            }
            if (left == null) {
                return 1;
            }
            if (right == null) {
                return -1;
            }
            return left.compareTo(right);
        }

        private static boolean isBlankStatic(String value) {
            return value == null || value.isBlank();
        }
    }
}
