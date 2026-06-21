package com.axonlink.service.impl;

import com.axonlink.config.Neo4jConfig;
import com.axonlink.dto.FlowtranDomain;
import com.axonlink.dto.FlowtranTransaction;
import com.axonlink.dto.NodeCacheEntry;
import com.axonlink.service.FlowtranService;
import com.axonlink.service.FlowServiceMetadataResolver;
import com.axonlink.service.ServiceNodeCache;
import com.axonlink.service.FlowServiceMetadataResolver.ServiceTypeFileMeta;
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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FlowtranServiceImpl implements FlowtranService {

    private static final Logger log = LoggerFactory.getLogger(FlowtranServiceImpl.class);
    private static final int MAX_METHOD_DEPTH = 8;

    private static final Map<String, String> DOMAIN_NAME_MAP = Map.of(
        "deposit", "存款领域",
        "loan", "贷款领域",
        "platform", "平台",
        "settlement", "结算领域",
        "public", "公共领域",
        "unvr", "通联领域",
        "aggr", "综合领域",
        "inbu", "境内业务领域",
        "medu", "中间业务领域",
        "stmt", "对账领域"
    );

    private static final Map<String, String> DOMAIN_ICON_MAP = Map.of(
        "deposit", "bank",
        "loan", "credit-card",
        "platform", "layers",
        "settlement", "exchange",
        "public", "globe"
    );

    private static final Map<String, String> FLOWTRAN_DOMAIN_NAME_OVERRIDES = Map.of(
        "ap", "平台",
        "platform", "平台",
        "dept", "DEPT",
        "unvr", "UNVR",
        "stmt", "STMT",
        "medu", "MEDU",
        "inbu", "INBU",
        "aggr", "AGGR"
    );

    private static final Map<String, String> FLOWTRAN_DOMAIN_ICON_OVERRIDES = Map.of(
        "ap", "layers",
        "platform", "layers",
        "dept", "bank",
        "unvr", "globe",
        "stmt", "file-text",
        "medu", "shuffle",
        "inbu", "briefcase",
        "aggr", "git-merge"
    );

    private final Driver driver;
    private final Neo4jConfig neo4jConfig;
    private final ServiceNodeCache serviceNodeCache;
    private final FlowServiceMetadataResolver flowServiceMetadataResolver;

    public FlowtranServiceImpl(Driver driver,
                               Neo4jConfig neo4jConfig,
                               ServiceNodeCache serviceNodeCache,
                               FlowServiceMetadataResolver flowServiceMetadataResolver) {
        this.driver = driver;
        this.neo4jConfig = neo4jConfig;
        this.serviceNodeCache = serviceNodeCache;
        this.flowServiceMetadataResolver = flowServiceMetadataResolver;
    }

    @Override
    public List<FlowtranDomain> listDomains() {
        if (!neo4jAvailable()) {
            return List.of();
        }

        try {
            List<FlowtranDomain> domains = new ArrayList<>();
            try (Session session = driver.session()) {
                session.run(
                    "MATCH (tx:Transaction) " +
                    "WHERE tx.domainKey IS NOT NULL " +
                    "RETURN tx.domainKey AS domainKey, count(tx) AS txCount " +
                    "ORDER BY domainKey")
                    .forEachRemaining(record -> {
                        String domainKey = stringValue(record, "domainKey");
                        if (isBlank(domainKey)) {
                            return;
                        }
                        FlowtranDomain domain = new FlowtranDomain();
                        domain.setDomainKey(domainKey);
                        domain.setDomainName(displayDomainName(domainKey));
                        domain.setTxCount(longValue(record, "txCount"));
                        domain.setIcon(displayDomainIcon(domainKey));
                        domains.add(domain);
                    });
            }
            domains.sort(Comparator.comparing(FlowtranDomain::getDomainKey));
            return domains;
        } catch (Exception e) {
            log.warn("[FlowtranService] listDomains failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> listTransactions(String domainKey, int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        if (!neo4jAvailable()) {
            return emptyPage(safePage, safeSize);
        }

        try (Session session = driver.session()) {
            String normalizedKeyword = keyword == null ? "" : keyword.trim().toUpperCase(Locale.ROOT);
            long total = session.run(
                "MATCH (tx:Transaction) " +
                "WHERE tx.domainKey = $domainKey " +
                "  AND ($keyword = '' " +
                "       OR toUpper(tx.id) CONTAINS $keyword " +
                "       OR toUpper(coalesce(tx.longname, '')) CONTAINS $keyword) " +
                "RETURN count(tx) AS total",
                Values.parameters("domainKey", domainKey, "keyword", normalizedKeyword))
                .single()
                .get("total")
                .asLong();

            List<FlowtranTransaction> list = new ArrayList<>();
            session.run(
                "MATCH (tx:Transaction) " +
                "WHERE tx.domainKey = $domainKey " +
                "  AND ($keyword = '' " +
                "       OR toUpper(tx.id) CONTAINS $keyword " +
                "       OR toUpper(coalesce(tx.longname, '')) CONTAINS $keyword) " +
                "RETURN tx.id AS id, " +
                "       tx.longname AS longname, " +
                "       tx.domainKey AS domainKey, " +
                "       tx.kind AS txnMode, " +
                "       coalesce(tx.parentProject, tx.module, '') AS fromJar " +
                "ORDER BY tx.id SKIP $skip LIMIT $limit",
                Values.parameters(
                    "domainKey", domainKey,
                    "keyword", normalizedKeyword,
                    "skip", (safePage - 1) * safeSize,
                    "limit", safeSize))
                .forEachRemaining(record -> {
                    FlowtranTransaction tx = new FlowtranTransaction();
                    tx.setId(stringValue(record, "id"));
                    tx.setLongname(stringValue(record, "longname"));
                    tx.setDomainKey(stringValue(record, "domainKey"));
                    tx.setTxnMode(stringValue(record, "txnMode"));
                    tx.setFromJar(stringValue(record, "fromJar"));
                    list.add(tx);
                });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", list);
            result.put("total", total);
            result.put("page", safePage);
            result.put("size", safeSize);
            return result;
        } catch (Exception e) {
            log.warn("[FlowtranService] listTransactions failed: {}", e.getMessage());
            return emptyPage(safePage, safeSize);
        }
    }

    @Override
    public Map<String, Object> getChain(String txId) {
        if (!neo4jAvailable()) {
            return null;
        }

        try (Session session = driver.session()) {
            TransactionMeta tx = loadTransaction(session, txId);
            if (tx == null) {
                return null;
            }

            List<FlowStepRef> steps = loadFlowSteps(session, txId);
            String txDomainName = displayDomainName(tx.domainKey);

            List<Map<String, Object>> orchestration = List.of(node("code", tx.id, "name", tx.longname));
            LinkedHashMap<String, LogicalSeed> topServiceSeeds = new LinkedHashMap<>();
            LinkedHashMap<String, LogicalSeed> componentSeeds = new LinkedHashMap<>();
            LinkedHashSet<String> rootServiceCodes = new LinkedHashSet<>();

            LinkedHashMap<String, Map<String, Object>> serviceLayer = new LinkedHashMap<>();
            LinkedHashMap<String, Map<String, Object>> componentLayer = new LinkedHashMap<>();
            LinkedHashMap<String, Map<String, Object>> tableLayer = new LinkedHashMap<>();
            LinkedHashMap<String, Map<String, Object>> daoLayer = new LinkedHashMap<>();

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
            BoundaryResolver resolver = new BoundaryResolver(session);
            Map<String, List<String>> serviceToService = new LinkedHashMap<>();
            Map<String, List<String>> serviceToComponent = new LinkedHashMap<>();
            Map<String, List<String>> componentToComponent = new LinkedHashMap<>();
            Map<String, List<String>> nodeToTable = new LinkedHashMap<>();
            Map<String, List<String>> tableToDao = new LinkedHashMap<>();

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
                BoundaryResult directTargets = discoverDirectTargets(session, seed.methodSignatures, resolver);
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
                    if (existing == null) {
                        componentSeeds.put(target.code, target);
                    } else {
                        componentSeeds.put(target.code, existing.merge(target));
                    }
                    addRelation(serviceToComponent, seed.code, target.code);
                }
                BoundaryResult dataTargets = discoverDataTargets(session, seed.methodSignatures);
                for (TableTarget target : mergeTableTargets(directTargets, dataTargets).values()) {
                    tableLayer.putIfAbsent(target.code, target.toNode(displayDomainName(target.domainKey)));
                    addRelation(nodeToTable, seed.code, target.code);
                }
                for (DaoTarget target : mergeDaoTargets(directTargets, dataTargets).values()) {
                    daoLayer.putIfAbsent(target.code, target.toNode(displayDomainName(target.domainKey)));
                    addRelation(tableToDao, target.tableCode, target.code);
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
                BoundaryResult directTargets = discoverDirectTargets(session, seed.methodSignatures, resolver);
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
                BoundaryResult dataTargets = discoverDataTargets(session, seed.methodSignatures);
                for (TableTarget target : mergeTableTargets(directTargets, dataTargets).values()) {
                    tableLayer.putIfAbsent(target.code, target.toNode(displayDomainName(target.domainKey)));
                    addRelation(nodeToTable, seed.code, target.code);
                }
                for (DaoTarget target : mergeDaoTargets(directTargets, dataTargets).values()) {
                    daoLayer.putIfAbsent(target.code, target.toNode(displayDomainName(target.domainKey)));
                    addRelation(tableToDao, target.tableCode, target.code);
                }
            }

            Map<String, Object> relations = new LinkedHashMap<>();
            relations.put("rootServices", new ArrayList<>(rootServiceCodes));
            relations.put("serviceToService", serviceToService);
            relations.put("serviceToComponent", serviceToComponent);
            relations.put("componentToComponent", componentToComponent);
            relations.put("nodeToTable", nodeToTable);
            relations.put("tableToDao", tableToDao);

            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("orchestration", orchestration);
            chain.put("service", new ArrayList<>(serviceLayer.values()));
            chain.put("component", new ArrayList<>(componentLayer.values()));
            chain.put("data", node(
                "table", new ArrayList<>(tableLayer.values()),
                "dao", new ArrayList<>(daoLayer.values())
            ));
            chain.put("relations", relations);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", tx.id);
            result.put("name", tx.longname);
            result.put("domain", txDomainName);
            result.put("serviceCount", (long) serviceLayer.size());
            result.put("componentCount", (long) componentLayer.size());
            result.put("tableCount", (long) tableLayer.size());
            result.put("daoCount", (long) daoLayer.size());
            result.put("layers", 5);
            result.put("chain", chain);
            return result;
        } catch (Exception e) {
            log.warn("[FlowtranService] getChain failed txId={}: {}", txId, e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> collectChainMethods(String txId) {
        if (!neo4jAvailable()) {
            return null;
        }
        // 注意：Neo4j 查询异常此处不捕获，直接上抛——由调用方（错误码物化）重试/降级判 incomplete，
        // 避免把"瞬时查询失败"当作"该交易无方法"静默写入残缺数据。
        try (Session session = driver.session()) {
            TransactionMeta tx = loadTransaction(session, txId);
            if (tx == null) {
                return null;
            }
            List<FlowStepRef> steps = loadFlowSteps(session, txId);

            // —— 复用 getChain 同一套种子 BFS（loadFlowSteps + discoverDirectTargets + BoundaryResolver），
            //    但只推进种子集合、不构建 layer/table/dao（只为拿到链路里所有构件/服务的实现方法签名）。
            LinkedHashMap<String, LogicalSeed> topServiceSeeds = new LinkedHashMap<>();
            LinkedHashMap<String, LogicalSeed> componentSeeds = new LinkedHashMap<>();
            for (FlowStepRef step : steps) {
                LogicalSeed seed = new LogicalSeed(step.code, step.name, step.prefix, step.domainKey,
                        new LinkedHashSet<>(step.methodSignatures));
                if (isComponentPrefix(step.prefix)) {
                    componentSeeds.merge(step.code, seed, LogicalSeed::merge);
                } else {
                    topServiceSeeds.merge(step.code, seed, LogicalSeed::merge);
                }
            }
            BoundaryResolver resolver = new BoundaryResolver(session);

            Map<String, Integer> svcProcessed = new HashMap<>();
            List<String> svcQueue = new ArrayList<>(topServiceSeeds.keySet());
            for (int i = 0; i < svcQueue.size(); i++) {
                LogicalSeed seed = topServiceSeeds.get(svcQueue.get(i));
                if (seed == null || svcProcessed.getOrDefault(svcQueue.get(i), 0) >= seed.methodSignatures.size()) {
                    continue;
                }
                svcProcessed.put(svcQueue.get(i), seed.methodSignatures.size());
                BoundaryResult dt = discoverDirectTargets(session, seed.methodSignatures, resolver);
                for (LogicalSeed t : dt.serviceTargets.values()) {
                    LogicalSeed existing = topServiceSeeds.get(t.code);
                    if (existing == null) {
                        topServiceSeeds.put(t.code, t);
                        svcQueue.add(t.code);
                    } else {
                        LogicalSeed merged = existing.merge(t);
                        topServiceSeeds.put(t.code, merged);
                        if (merged.methodSignatures.size() > existing.methodSignatures.size()) {
                            svcQueue.add(t.code);
                        }
                    }
                }
                for (LogicalSeed t : dt.componentTargets.values()) {
                    LogicalSeed existing = componentSeeds.get(t.code);
                    componentSeeds.put(t.code, existing == null ? t : existing.merge(t));
                }
            }

            Map<String, Integer> cmpProcessed = new HashMap<>();
            List<String> cmpQueue = new ArrayList<>(componentSeeds.keySet());
            for (int i = 0; i < cmpQueue.size(); i++) {
                LogicalSeed seed = componentSeeds.get(cmpQueue.get(i));
                if (seed == null || cmpProcessed.getOrDefault(cmpQueue.get(i), 0) >= seed.methodSignatures.size()) {
                    continue;
                }
                cmpProcessed.put(cmpQueue.get(i), seed.methodSignatures.size());
                BoundaryResult dt = discoverDirectTargets(session, seed.methodSignatures, resolver);
                for (LogicalSeed t : dt.componentTargets.values()) {
                    LogicalSeed existing = componentSeeds.get(t.code);
                    if (existing == null) {
                        componentSeeds.put(t.code, t);
                        cmpQueue.add(t.code);
                    } else {
                        LogicalSeed merged = existing.merge(t);
                        componentSeeds.put(t.code, merged);
                        if (merged.methodSignatures.size() > existing.methodSignatures.size()) {
                            cmpQueue.add(t.code);
                        }
                    }
                }
            }

            // 汇总所有种子的方法签名（= 链路里所有构件/服务的实现方法，跨接口/SysUtil/ServiceOperation 已正确解析）
            Set<String> sigs = new LinkedHashSet<>();
            topServiceSeeds.values().forEach(s -> sigs.addAll(s.methodSignatures));
            componentSeeds.values().forEach(s -> sigs.addAll(s.methodSignatures));

            List<Map<String, Object>> methods = new ArrayList<>();
            if (!sigs.isEmpty()) {
                // 从每个实现方法再沿 CALLS/SELF_CALLS/SYS_UTIL_CALLS 展开内部可达方法（抓私有校验等深处 throw）；
                // 跨服务的实现方法已由上面的种子覆盖。反查归属构件（serviceId/longname）。
                session.run(
                    "UNWIND $sigs AS sig\n"
                    + "MATCH (entry:Method {signature: sig})\n"
                    + "MATCH (entry)-[:CALLS|SELF_CALLS|SYS_UTIL_CALLS*0..8]->(r:Method)\n"
                    + "OPTIONAL MATCH (op:ServiceOperation)-[:IMPLEMENTS_BY]->(r)\n"
                    + "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(st:ServiceType)\n"
                    + "RETURN DISTINCT r.classFqn AS class_fqn, r.name AS method_name,\n"
                    + "       coalesce(op.serviceId, st.id) AS component_code,\n"
                    + "       coalesce(op.longname, st.longname) AS component_name",
                    Values.parameters("sigs", new ArrayList<>(sigs)))
                    .forEachRemaining(rec -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("classFqn", stringValue(rec, "class_fqn"));
                        m.put("methodName", stringValue(rec, "method_name"));
                        m.put("componentCode", stringValue(rec, "component_code"));
                        m.put("componentName", stringValue(rec, "component_name"));
                        methods.add(m);
                    });
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("txId", tx.id);
            result.put("txName", tx.longname);
            result.put("domainKey", tx.domainKey);
            result.put("methods", methods);
            return result;
        }
    }

    private boolean neo4jAvailable() {
        return neo4jConfig.isEnabled() && driver != null;
    }

    private Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", List.of());
        result.put("total", 0L);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    private TransactionMeta loadTransaction(Session session, String txId) {
        List<Record> records = session.run(
            "MATCH (tx:Transaction {id: $txId}) " +
            "RETURN tx.id AS id, " +
            "       coalesce(tx.longname, tx.id) AS longname, " +
            "       tx.domainKey AS domainKey, " +
            "       coalesce(tx.parentProject, tx.module, '') AS parentProject",
            Values.parameters("txId", txId)).list();
        if (records.isEmpty()) {
            return null;
        }
        Record record = records.get(0);
        return new TransactionMeta(
            stringValue(record, "id"),
            stringValue(record, "longname"),
            stringValue(record, "domainKey"),
            stringValue(record, "parentProject")
        );
    }

    private List<FlowStepRef> loadFlowSteps(Session session, String txId) {
        List<Record> records = session.run(
            "MATCH (tx:Transaction {id: $txId})-[:HAS_FLOW]->(flow:FlowBlock) " +
            "MATCH p = (flow)-[:HAS_STEP|EXECUTES|HAS_BRANCH|NEXT*0..24]->(step) " +
            "WHERE step:FlowMethodStep OR step:FlowServiceStep " +
            "WITH DISTINCT step " +
            "OPTIONAL MATCH (step)-[:RESOLVES_TO_METHOD]->(resolved:Method) " +
            "OPTIONAL MATCH (step)-[:CALLS_SERVICE]->(op:ServiceOperation) " +
            "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(stype:ServiceType) " +
            "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
            "RETURN step.key AS stepKey, " +
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
            "ORDER BY stepKey",
            Values.parameters("txId", txId)).list();

        List<FlowStepRef> steps = new ArrayList<>();
        for (Record record : records) {
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

            String name = firstNonBlank(
                stringValue(record, "stepName"),
                serviceNodeCache.get(code).map(NodeCacheEntry::getServiceLongname).orElse(null),
                code);
            steps.add(new FlowStepRef(code, name, prefix, domainKey, signatures));
        }
        return steps;
    }
    private BoundaryResult discoverDirectTargets(Session session,
                                                 Collection<String> startSignatures,
                                                 BoundaryResolver resolver) {
        BoundaryResult result = new BoundaryResult();
        List<String> frontier = startSignatures.stream()
            .filter(sig -> !isBlank(sig))
            .distinct()
            .collect(Collectors.toList());
        Set<String> visited = new LinkedHashSet<>(frontier);

        for (int depth = 0; depth < MAX_METHOD_DEPTH && !frontier.isEmpty(); depth++) {
            Map<String, List<CallTarget>> outgoing = loadOutgoingCalls(session, frontier);
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

                            String daoCode = !isBlank(target.targetDaoClassName)
                                ? target.targetDaoClassName + "#" + methodName
                                : tableId + "#" + methodName;
                            DaoTarget daoTarget = new DaoTarget(
                                daoCode,
                                methodName,
                                tableId,
                                target.targetDaoClassName,
                                target.targetDomainKey
                            );
                            result.daoTargets.putIfAbsent(daoTarget.code, daoTarget);
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

    private BoundaryResult discoverDataTargets(Session session, Collection<String> startSignatures) {
        List<String> signatures = startSignatures.stream()
            .filter(sig -> !isBlank(sig))
            .distinct()
            .collect(Collectors.toList());
        if (signatures.isEmpty()) {
            return new BoundaryResult();
        }

        BoundaryResult result = new BoundaryResult();
        session.run(
            "UNWIND $signatures AS signature " +
            "MATCH (entry:Method {signature: signature}) " +
            "MATCH p = (entry)-[:CALLS|SELF_CALLS*0.." + MAX_METHOD_DEPTH + "]->(:Method)-[:DAO_CALLS]->(dao:DaoMethod) " +
            "OPTIONAL MATCH (table:Table)-[:EXPOSES_DAO]->(dao) " +
            "RETURN DISTINCT coalesce(table.id, dao.tableId) AS tableId, " +
            "       coalesce(table.longname, dao.tableLongname, dao.tableId) AS tableLongname, " +
            "       coalesce(table.domainKey, dao.domainKey) AS domainKey, " +
            "       coalesce(table.projectName, dao.projectName) AS projectName, " +
            "       coalesce(table.daoClassName, dao.daoClassName) AS daoClassName, " +
            "       dao.id AS daoId, " +
            "       dao.methodName AS daoMethodName",
            Values.parameters("signatures", signatures))
            .forEachRemaining(record -> {
                String tableId = stringValue(record, "tableId");
                String daoId = stringValue(record, "daoId");
                String daoMethodName = stringValue(record, "daoMethodName");
                if (isBlank(tableId) || isBlank(daoId) || isBlank(daoMethodName)) {
                    return;
                }
                TableTarget tableTarget = new TableTarget(
                    tableId,
                    firstNonBlank(stringValue(record, "tableLongname"), tableId),
                    stringValue(record, "domainKey"),
                    stringValue(record, "projectName"),
                    stringValue(record, "daoClassName")
                );
                result.tableTargets.putIfAbsent(tableTarget.code, tableTarget);

                DaoTarget daoTarget = new DaoTarget(
                    daoId,
                    daoMethodName,
                    tableId,
                    stringValue(record, "daoClassName"),
                    stringValue(record, "domainKey")
                );
                result.daoTargets.putIfAbsent(daoTarget.code, daoTarget);
            });
        return result;
    }

    private Map<String, TableTarget> mergeTableTargets(BoundaryResult primary, BoundaryResult extra) {
        Map<String, TableTarget> merged = new LinkedHashMap<>(primary.tableTargets);
        merged.putAll(extra.tableTargets);
        return merged;
    }

    private Map<String, DaoTarget> mergeDaoTargets(BoundaryResult primary, BoundaryResult extra) {
        Map<String, DaoTarget> merged = new LinkedHashMap<>(primary.daoTargets);
        merged.putAll(extra.daoTargets);
        return merged;
    }

    private Map<String, List<CallTarget>> loadOutgoingCalls(Session session, Collection<String> methodSignatures) {
        if (methodSignatures.isEmpty()) {
            return Map.of();
        }

        Map<String, List<CallTarget>> result = new HashMap<>();
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
            Values.parameters("signatures", methodSignatures))
            .forEachRemaining(record -> {
                String sourceSignature = stringValue(record, "sourceSignature");
                result.computeIfAbsent(sourceSignature, ignored -> new ArrayList<>()).add(new CallTarget(
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
        return result;
    }

    private Map<String, Object> displayNode(String code,
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
        Map<String, Object> node = node(
            "prefix", resolvedPrefix,
            "code", code,
            "name", name,
            "domainKey", resolvedDomainKey
        );
        if (!isBlank(resolvedDomainKey)) {
            node.put("domain", displayDomainName(resolvedDomainKey));
        }
        return node;
    }

    private String displayDomainName(String domainKey) {
        if (isBlank(domainKey)) {
            return domainKey;
        }
        return FLOWTRAN_DOMAIN_NAME_OVERRIDES.getOrDefault(
            domainKey,
            DOMAIN_NAME_MAP.getOrDefault(domainKey, domainKey)
        );
    }

    private String displayDomainIcon(String domainKey) {
        if (isBlank(domainKey)) {
            return "folder";
        }
        return FLOWTRAN_DOMAIN_ICON_OVERRIDES.getOrDefault(
            domainKey,
            DOMAIN_ICON_MAP.getOrDefault(domainKey, "folder")
        );
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

    private String buildServiceCode(String serviceTypeId, String serviceId) {
        if (isBlank(serviceTypeId) || isBlank(serviceId)) {
            return null;
        }
        return serviceTypeId + "." + serviceId;
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

    private String normalizePrefix(String value) {
        if (isBlank(value)) {
            return "pbs";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isComponentPrefix(String prefix) {
        return prefix != null && prefix.startsWith("pbc");
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

    private long longValue(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? 0L : value.asLong();
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

    private String stripDaoSuffix(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.endsWith("Dao") ? value.substring(0, value.length() - 3) : value;
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

        private Optional<LogicalSeed> resolveByMethodSignature(String methodSignature) {
            if (isBlank(methodSignature)) {
                return Optional.empty();
            }
            return methodCache.computeIfAbsent(methodSignature, key -> resolveServiceOperation(
                "MATCH (op:ServiceOperation)-[:IMPLEMENTS_BY]->(m:Method {signature: $value}) " +
                "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(stype:ServiceType) " +
                "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
                "RETURN op.serviceTypeId AS serviceTypeId, " +
                "       op.serviceId AS serviceId, " +
                "       coalesce(op.longname, op.serviceId, op.methodName) AS serviceName, " +
                "       stype.nodeKind AS nodeKind, " +
                "       stype.domainKey AS domainKey, " +
                "       collect(DISTINCT impl.signature) AS implSignatures " +
                "LIMIT 1",
                Values.parameters("value", key)));
        }

        private Optional<LogicalSeed> resolveByImplementationClass(String classFqn, String methodName) {
            if (isBlank(classFqn) || isBlank(methodName)) {
                return Optional.empty();
            }
            String cacheKey = classFqn + "#" + methodName;
            return implClassCache.computeIfAbsent(cacheKey, key -> resolveServiceOperation(
                "MATCH (op:ServiceOperation)-[:IMPLEMENTS_BY]->(matched:Method {classFqn: $classFqn, name: $methodName}) " +
                "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(stype:ServiceType) " +
                "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
                "RETURN op.serviceTypeId AS serviceTypeId, " +
                "       op.serviceId AS serviceId, " +
                "       coalesce(op.longname, op.serviceId, op.methodName) AS serviceName, " +
                "       stype.nodeKind AS nodeKind, " +
                "       stype.domainKey AS domainKey, " +
                "       collect(DISTINCT impl.signature) AS implSignatures " +
                "LIMIT 1",
                Values.parameters("classFqn", classFqn, "methodName", methodName)));
        }

        private Optional<LogicalSeed> resolveByInterfaceMethod(String interfaceFqn, String methodName) {
            if (isBlank(interfaceFqn) || isBlank(methodName)) {
                return Optional.empty();
            }
            String cacheKey = interfaceFqn + "#" + methodName;
            return interfaceCache.computeIfAbsent(cacheKey, key -> resolveServiceOperation(
                "MATCH (stype:ServiceType {interfaceFqn: $interfaceFqn})-[:DECLARES_OPERATION]->(op:ServiceOperation {methodName: $methodName}) " +
                "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
                "RETURN op.serviceTypeId AS serviceTypeId, " +
                "       op.serviceId AS serviceId, " +
                "       coalesce(op.longname, op.serviceId, op.methodName) AS serviceName, " +
                "       stype.nodeKind AS nodeKind, " +
                "       stype.domainKey AS domainKey, " +
                "       collect(DISTINCT impl.signature) AS implSignatures " +
                "LIMIT 1",
                Values.parameters("interfaceFqn", interfaceFqn, "methodName", methodName)));
        }

        private Optional<LogicalSeed> resolveByServiceOperation(String serviceTypeId, String serviceId) {
            if (isBlank(serviceTypeId) || isBlank(serviceId)) {
                return Optional.empty();
            }
            String cacheKey = serviceTypeId + "." + serviceId;
            return operationCache.computeIfAbsent(cacheKey, key -> resolveServiceOperation(
                "MATCH (op:ServiceOperation {serviceTypeId: $serviceTypeId, serviceId: $serviceId}) " +
                "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(stype:ServiceType) " +
                "OPTIONAL MATCH (op)-[:IMPLEMENTS_BY]->(impl:Method) " +
                "RETURN op.serviceTypeId AS serviceTypeId, " +
                "       op.serviceId AS serviceId, " +
                "       coalesce(op.longname, op.serviceId, op.methodName) AS serviceName, " +
                "       stype.nodeKind AS nodeKind, " +
                "       stype.domainKey AS domainKey, " +
                "       collect(DISTINCT impl.signature) AS implSignatures " +
                "LIMIT 1",
                Values.parameters("serviceTypeId", serviceTypeId, "serviceId", serviceId)));
        }

        private Optional<LogicalSeed> resolveServiceOperation(String cypher, Value parameters) {
            List<Record> records = session.run(cypher, parameters).list();
            if (records.isEmpty()) {
                return Optional.empty();
            }

            Record record = records.get(0);
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
                methodSignatures.stream()
                    .filter(sig -> sig != null && !sig.isBlank())
                    .forEach(this.methodSignatures::add);
            }
        }

        private LogicalSeed merge(LogicalSeed other) {
            LinkedHashSet<String> mergedSignatures = new LinkedHashSet<>(methodSignatures);
            mergedSignatures.addAll(other.methodSignatures);
            return new LogicalSeed(
                code,
                firstNonBlank(name, other.name),
                firstNonBlank(prefix, other.prefix),
                firstNonBlank(domainKey, other.domainKey),
                mergedSignatures
            );
        }

        private static String firstNonBlank(String left, String right) {
            if (left != null && !left.isBlank()) {
                return left;
            }
            return right;
        }
    }

    private static final class BoundaryResult {
        private final Map<String, LogicalSeed> serviceTargets = new LinkedHashMap<>();
        private final Map<String, LogicalSeed> componentTargets = new LinkedHashMap<>();
        private final Map<String, TableTarget> tableTargets = new LinkedHashMap<>();
        private final Map<String, DaoTarget> daoTargets = new LinkedHashMap<>();
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

        private Map<String, Object> toNode(String domainName) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("code", code);
            node.put("name", name);
            node.put("tableId", code);
            node.put("tableLongname", name);
            node.put("domainKey", domainKey);
            node.put("projectName", projectName);
            node.put("daoClassName", daoClassName);
            if (domainName != null && !domainName.isBlank()) {
                node.put("domain", domainName);
            }
            return node;
        }
    }

    private static final class DaoTarget {
        private final String code;
        private final String name;
        private final String tableCode;
        private final String daoClassName;
        private final String domainKey;

        private DaoTarget(String code,
                          String name,
                          String tableCode,
                          String daoClassName,
                          String domainKey) {
            this.code = code;
            this.name = name;
            this.tableCode = tableCode;
            this.daoClassName = daoClassName;
            this.domainKey = domainKey;
        }

        private Map<String, Object> toNode(String domainName) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("prefix", "dao");
            node.put("code", code);
            node.put("name", name);
            node.put("methodName", name);
            node.put("tableCode", tableCode);
            node.put("daoClassName", daoClassName);
            node.put("domainKey", domainKey);
            if (domainName != null && !domainName.isBlank()) {
                node.put("domain", domainName);
            }
            return node;
        }
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
}
