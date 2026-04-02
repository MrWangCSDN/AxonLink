package com.axonlink.ai.tool;

import com.axonlink.ai.dto.CodeSnippet;
import com.axonlink.config.Neo4jConfig;
import com.axonlink.service.ProjectIndexer;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 源码片段工具。
 *
 * <p>优先按交易链路定位真实实现方法，再抽取对应 Java 方法片段。
 * 对于未命中实现方法的节点，保留降级空间，便于后续继续补 XML 级回退逻辑。
 */
@Component
public class SourceSnippetTool {

    private final ProjectIndexer projectIndexer;
    private final Neo4jConfig neo4jConfig;
    private final Driver driver;

    public SourceSnippetTool(ProjectIndexer projectIndexer,
                             Neo4jConfig neo4jConfig,
                             ObjectProvider<Driver> driverProvider) {
        this.projectIndexer = projectIndexer;
        this.neo4jConfig = neo4jConfig;
        this.driver = driverProvider.getIfAvailable();
    }

    public List<CodeSnippet> collectTransactionSnippets(String txId,
                                                        Map<String, Object> chain,
                                                        List<String> selectedPath,
                                                        int limit,
                                                        int maxCharsPerSnippet) {
        List<CodeSnippet> snippets = new ArrayList<>();
        if (limit <= 0 || chain == null || chain.isEmpty() || !neo4jAvailable()) {
            return snippets;
        }

        LinkedHashMap<String, String> nodePrefixes = collectNodePrefixes(chain);
        List<String> orderedCodes = orderNodeCodes(chain, nodePrefixes, selectedPath);

        for (String nodeCode : orderedCodes) {
            if (snippets.size() >= limit) {
                break;
            }
            Optional<CodeSnippet> snippet = resolveSnippet(txId, nodeCode, nodePrefixes.get(nodeCode), maxCharsPerSnippet);
            snippet.ifPresent(snippets::add);
        }
        return snippets;
    }

    private Optional<CodeSnippet> resolveSnippet(String txId,
                                                 String nodeCode,
                                                 String prefix,
                                                 int maxCharsPerSnippet) {
        Optional<ResolvedMethod> resolved = isMethodPrefix(prefix)
                ? resolveTransactionMethod(txId, nodeCode)
                : resolveServiceImplementation(nodeCode);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        ResolvedMethod method = resolved.get();
        try {
            Path path = Paths.get(method.filePath()).toAbsolutePath().normalize();
            if (!Files.exists(path) || !Files.isReadable(path)) {
                return Optional.empty();
            }
            String source = Files.readString(path);
            Optional<ExtractedMethod> extracted = extractMethod(source, method.methodName(), maxCharsPerSnippet);
            if (extracted.isEmpty()) {
                return Optional.empty();
            }

            ExtractedMethod methodBody = extracted.get();
            CodeSnippet snippet = new CodeSnippet();
            snippet.setNodeCode(nodeCode);
            snippet.setFilePath(path.toString());
            snippet.setMethodName(method.methodName());
            snippet.setLanguage("java");
            snippet.setStartLine(methodBody.startLine());
            snippet.setEndLine(methodBody.endLine());
            snippet.setContent(methodBody.content());
            return Optional.of(snippet);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<ResolvedMethod> resolveTransactionMethod(String txId, String nodeCode) {
        if (isBlank(txId) || isBlank(nodeCode)) {
            return Optional.empty();
        }
        try (Session session = driver.session()) {
            List<Record> records = session.run(
                    "MATCH (tx:Transaction {id: $txId})-[:HAS_FLOW]->(flow:FlowBlock) " +
                            "MATCH p = (flow)-[:HAS_STEP|EXECUTES|HAS_BRANCH|NEXT*0..24]->(step:FlowMethodStep) " +
                            "WHERE step.id = $nodeCode OR step.methodName = $nodeCode " +
                            "WITH DISTINCT step " +
                            "MATCH (step)-[:RESOLVES_TO_METHOD]->(m:Method) " +
                            "RETURN m.classFqn AS classFqn, m.name AS methodName " +
                            "ORDER BY CASE WHEN step.id = $nodeCode THEN 0 ELSE 1 END " +
                            "LIMIT 1",
                    Values.parameters("txId", txId, "nodeCode", nodeCode)).list();
            if (records.isEmpty()) {
                return Optional.empty();
            }
            return toResolvedMethod(records.get(0), "classFqn", "methodName");
        }
    }

    private Optional<ResolvedMethod> resolveServiceImplementation(String nodeCode) {
        if (isBlank(nodeCode) || !nodeCode.contains(".")) {
            return Optional.empty();
        }
        String serviceTypeId = nodeCode.substring(0, nodeCode.indexOf('.'));
        String serviceId = nodeCode.substring(nodeCode.indexOf('.') + 1);

        try (Session session = driver.session()) {
            List<Record> records = session.run(
                    "MATCH (op:ServiceOperation {serviceTypeId: $serviceTypeId, serviceId: $serviceId})-[:IMPLEMENTS_BY]->(m:Method) " +
                            "RETURN m.classFqn AS classFqn, m.name AS methodName " +
                            "ORDER BY m.classFqn, m.name " +
                            "LIMIT 1",
                    Values.parameters("serviceTypeId", serviceTypeId, "serviceId", serviceId)).list();
            if (records.isEmpty()) {
                return Optional.empty();
            }
            return toResolvedMethod(records.get(0), "classFqn", "methodName");
        }
    }

    private Optional<ResolvedMethod> toResolvedMethod(Record record, String classFqnKey, String methodNameKey) {
        String classFqn = stringValue(record.get(classFqnKey));
        String methodName = stringValue(record.get(methodNameKey));
        if (isBlank(classFqn) || isBlank(methodName)) {
            return Optional.empty();
        }

        Optional<Path> file = projectIndexer.findByFqn(classFqn);
        if (file.isEmpty()) {
            String className = classFqn.contains(".")
                    ? classFqn.substring(classFqn.lastIndexOf('.') + 1)
                    : classFqn;
            file = projectIndexer.findBySimpleName(className);
        }
        if (file.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedMethod(file.get().toString(), methodName));
    }

    private Optional<ExtractedMethod> extractMethod(String source, String methodName, int maxCharsPerSnippet) {
        try {
            ParserConfiguration configuration = new ParserConfiguration();
            configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return Optional.empty();
            }

            CompilationUnit cu = result.getResult().get();
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (!methodName.equals(method.getNameAsString())) {
                    continue;
                }
                int startLine = method.getBegin().map(p -> p.line).orElse(-1);
                int endLine = method.getEnd().map(p -> p.line).orElse(-1);
                String content = method.toString();
                if (maxCharsPerSnippet > 0 && content.length() > maxCharsPerSnippet) {
                    content = content.substring(0, maxCharsPerSnippet) + "\n// ... snippet truncated ...";
                }
                return Optional.of(new ExtractedMethod(startLine, endLine, content));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, String> collectNodePrefixes(Map<String, Object> chain) {
        LinkedHashMap<String, String> prefixes = new LinkedHashMap<>();
        for (String key : List.of("service", "component")) {
            Object value = chain.get(key);
            if (!(value instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> node)) {
                    continue;
                }
                Object code = node.get("code");
                if (code == null) {
                    continue;
                }
                prefixes.put(String.valueOf(code), stringValue(node.get("prefix")));
            }
        }
        return prefixes;
    }

    @SuppressWarnings("unchecked")
    private List<String> orderNodeCodes(Map<String, Object> chain,
                                        Map<String, String> nodePrefixes,
                                        List<String> selectedPath) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (selectedPath != null) {
            for (String item : selectedPath) {
                if (!isBlank(item) && nodePrefixes.containsKey(item)) {
                    ordered.add(item);
                }
            }
        }

        Object relationsValue = chain.get("relations");
        if (relationsValue instanceof Map<?, ?> relations) {
            Object roots = relations.get("rootServices");
            if (roots instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && nodePrefixes.containsKey(String.valueOf(item))) {
                        ordered.add(String.valueOf(item));
                    }
                }
            }
        }

        for (String code : nodePrefixes.keySet()) {
            ordered.add(code);
        }
        return new ArrayList<>(ordered);
    }

    private boolean neo4jAvailable() {
        return neo4jConfig.isEnabled() && driver != null;
    }

    private boolean isMethodPrefix(String prefix) {
        return "method".equalsIgnoreCase(prefix);
    }

    private String stringValue(Value value) {
        return value == null || value.isNull() ? null : value.asString();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ResolvedMethod(String filePath, String methodName) {}
    private record ExtractedMethod(int startLine, int endLine, String content) {}
}
