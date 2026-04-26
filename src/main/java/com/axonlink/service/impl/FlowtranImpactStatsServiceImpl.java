package com.axonlink.service.impl;

import com.axonlink.config.Neo4jConfig;
import com.axonlink.service.FlowtranImpactStatsService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FlowtranImpactStatsServiceImpl implements FlowtranImpactStatsService {

    private static final List<String> COMPONENT_KINDS = List.of("pbcb", "pbcp", "pbcc", "pbct");
    private static final List<String> SERVICE_KINDS = List.of("pbs", "pcs", "service");
    private static final List<String> ALL_SERVICE_OPERATION_KINDS = List.of("pbcb", "pbcp", "pbcc", "pbct", "pbs", "pcs", "service");

    private final Driver driver;
    private final Neo4jConfig neo4jConfig;

    public FlowtranImpactStatsServiceImpl(Driver driver, Neo4jConfig neo4jConfig) {
        this.driver = driver;
        this.neo4jConfig = neo4jConfig;
    }

    @Override
    public Map<String, Object> getImpactStats() {
        Map<String, Long> componentBreakdown = zeroCounts(COMPONENT_KINDS);
        Map<String, Long> serviceBreakdown = zeroCounts(SERVICE_KINDS);
        if (!neo4jConfig.isEnabled() || driver == null) {
            return result(0L, componentBreakdown, serviceBreakdown);
        }

        try (Session session = driver.session()) {
            long tableCount = session.run("MATCH (t:Table) RETURN count(t) AS total")
                .single()
                .get("total")
                .asLong();

            session.run(
                "MATCH (op:ServiceOperation) " +
                "WHERE op.nodeKind IN $kinds " +
                "RETURN op.nodeKind AS nodeKind, count(op) AS total " +
                "ORDER BY nodeKind",
                Values.parameters("kinds", ALL_SERVICE_OPERATION_KINDS)
            ).list().forEach(record -> {
                String nodeKind = record.get("nodeKind").asString("");
                long total = record.get("total").asLong();
                if (componentBreakdown.containsKey(nodeKind)) {
                    componentBreakdown.put(nodeKind, total);
                }
                if (serviceBreakdown.containsKey(nodeKind)) {
                    serviceBreakdown.put(nodeKind, total);
                }
            });

            return result(tableCount, componentBreakdown, serviceBreakdown);
        } catch (Exception e) {
            Map<String, Object> result = result(0L, componentBreakdown, serviceBreakdown);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private Map<String, Object> result(long tableCount,
                                       Map<String, Long> componentBreakdown,
                                       Map<String, Long> serviceBreakdown) {
        long componentCount = componentBreakdown.values().stream().mapToLong(Long::longValue).sum();
        long serviceCount = serviceBreakdown.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableCount", tableCount);
        result.put("componentCount", componentCount);
        result.put("serviceCount", serviceCount);
        result.put("componentBreakdown", componentBreakdown);
        result.put("serviceBreakdown", serviceBreakdown);
        return result;
    }

    private Map<String, Long> zeroCounts(List<String> nodeKinds) {
        Map<String, Long> counts = new LinkedHashMap<>();
        nodeKinds.forEach(nodeKind -> counts.put(nodeKind, 0L));
        return counts;
    }
}
