package com.axonlink.service;

import com.axonlink.config.Neo4jConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FlowtransMetaGraphBuilderTest {

    @Test
    void branchStepsWithoutIdsKeepDistinctKeysAndUseBusinessNames() throws Exception {
        FlowtransMetaGraphBuilder builder = new FlowtransMetaGraphBuilder(
            null,
            new Neo4jConfig(),
            mock(ServiceNodeCache.class),
            new MetadataResourceScanner()
        );
        Element flow = parse("""
            <flow>
              <case longname="调用信贷PBS">
                <when test="first">
                  <service serviceName="LoanQueryPbs.queryAccount"/>
                  <method method="prepareLoanResult"/>
                </when>
              </case>
              <case longname="调用存款PBS">
                <when test="second">
                  <service serviceName="DepositQueryPbs.queryAccount"/>
                  <method method="prepareDepositResult"/>
                </when>
              </case>
            </flow>
            """);

        ReflectionTestUtils.invokeMethod(
            builder,
            "parseContainer",
            "TX:TC028:FLOW",
            "HAS_STEP",
            "TC028",
            "com.example.TC028",
            flow,
            new LinkedHashMap<String, List<String>>(),
            new LinkedHashMap<>()
        );

        List<Map<String, Object>> serviceSteps = field(builder, "serviceStepNodes");
        List<Map<String, Object>> methodSteps = field(builder, "methodStepNodes");

        assertEquals(2, distinctKeyCount(serviceSteps),
            "different case/when branches must not merge service steps that omit id");
        assertEquals(2, distinctKeyCount(methodSteps),
            "different case/when branches must not merge method steps that omit id");
        assertTrue(keys(serviceSteps).stream().anyMatch(key -> key.contains("LoanQueryPbs.queryAccount")));
        assertTrue(keys(serviceSteps).stream().anyMatch(key -> key.contains("DepositQueryPbs.queryAccount")));
        assertTrue(keys(methodSteps).stream().anyMatch(key -> key.contains("prepareLoanResult")));
        assertTrue(keys(methodSteps).stream().anyMatch(key -> key.contains("prepareDepositResult")));
        assertTrue(keys(serviceSteps).stream().noneMatch(key -> key.contains(":CASE:") || key.contains(":WHEN:")),
            "encoded parent paths must not make service keys look like case/when nodes");
        assertTrue(keys(methodSteps).stream().noneMatch(key -> key.contains(":CASE:") || key.contains(":WHEN:")),
            "encoded parent paths must not make method keys look like case/when nodes");
    }

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return document.getDocumentElement();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> field(FlowtransMetaGraphBuilder builder, String name) {
        return (List<Map<String, Object>>) ReflectionTestUtils.getField(builder, name);
    }

    private static long distinctKeyCount(List<Map<String, Object>> nodes) {
        return keys(nodes).stream().distinct().count();
    }

    private static List<String> keys(List<Map<String, Object>> nodes) {
        return nodes.stream().map(node -> String.valueOf(node.get("key"))).toList();
    }
}
