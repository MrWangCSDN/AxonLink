package com.axonlink.controller;

import com.axonlink.config.Neo4jConfig;
import com.axonlink.service.ProjectIndexer;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 源码查看 + 跨文件导航接口。
 */
@RestController
@RequestMapping("/api/source")
public class SourceController {

    @Autowired
    private ProjectIndexer projectIndexer;

    @Autowired(required = false)
    private Driver driver;

    @Autowired
    private Neo4jConfig neo4jConfig;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSource(
            @RequestParam String filePath,
            @RequestParam(required = false, defaultValue = "") String methodName) {
        try {
            Path path = preferSourceMetadataPath(Paths.get(filePath).toAbsolutePath().normalize());
            String normalized = path.toString().toLowerCase(Locale.ROOT);
            if (!normalized.endsWith(".java") && !normalized.endsWith(".xml")) {
                return ResponseEntity.badRequest().build();
            }
            if (!Files.exists(path) || !Files.isReadable(path)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(buildResponse(path, methodName));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/find")
    public ResponseEntity<Map<String, Object>> findClass(
            @RequestParam(required = false, defaultValue = "") String currentFilePath,
            @RequestParam(required = false, defaultValue = "") String className,
            @RequestParam(required = false, defaultValue = "") String fqn,
            @RequestParam(required = false, defaultValue = "") String methodName) {
        try {
            if (!fqn.isBlank()) {
                Optional<Path> byFqn = projectIndexer.findByFqn(fqn);
                if (byFqn.isPresent()) {
                    return ResponseEntity.ok(buildResponse(byFqn.get(), methodName));
                }
                String simpleFromFqn = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                Optional<Path> bySimple = projectIndexer.findBySimpleName(simpleFromFqn);
                if (bySimple.isPresent()) {
                    return ResponseEntity.ok(buildResponse(bySimple.get(), methodName));
                }
                return ResponseEntity.notFound().build();
            }

            if (!className.isBlank()) {
                Optional<Path> indexed = projectIndexer.findBySimpleName(className);
                if (indexed.isPresent()) {
                    return ResponseEntity.ok(buildResponse(indexed.get(), methodName));
                }

                if (!currentFilePath.isBlank()) {
                    Path current = Paths.get(currentFilePath).toAbsolutePath().normalize();
                    Path sourceRoot = deriveSourceRoot(current);
                    try (Stream<Path> walk = Files.walk(sourceRoot)) {
                        Optional<Path> found = walk
                                .filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().equals(className + ".java"))
                                .findFirst();
                        if (found.isPresent()) {
                            return ResponseEntity.ok(buildResponse(found.get(), methodName));
                        }
                    }
                }
            }

            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/resolve/flow")
    public ResponseEntity<Map<String, Object>> resolveFlowSource(
            @RequestParam String txId,
            @RequestParam String nodeCode,
            @RequestParam(required = false, defaultValue = "") String nodeType,
            @RequestParam(required = false, defaultValue = "") String nodePrefix) {
        try {
            if (!neo4jConfig.isEnabled() || driver == null) {
                return ResponseEntity.notFound().build();
            }
            if ("orchestration".equalsIgnoreCase(nodeType)) {
                return resolveTransactionXml(txId);
            }
            if ("method".equalsIgnoreCase(nodePrefix)) {
                return resolveTransactionMethod(txId, nodeCode);
            }
            if (nodeCode.contains(".")) {
                return resolveServiceTypeXml(nodeCode);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/index/stats")
    public ResponseEntity<Map<String, Object>> getIndexStats() {
        Map<String, Object> stats = projectIndexer.getStats();
        stats.put("indexed", projectIndexer.isIndexed());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/index/progress")
    public ResponseEntity<Map<String, Object>> getIndexProgress() {
        return ResponseEntity.ok(projectIndexer.getIndexProgress());
    }

    @PostMapping("/index/refresh")
    public ResponseEntity<Map<String, Object>> refreshIndex() {
        return ResponseEntity.ok(projectIndexer.refresh());
    }

    @GetMapping("/index/classes")
    public ResponseEntity<Map<String, String>> listIndexedClasses() {
        Map<String, String> result = new LinkedHashMap<>();
        projectIndexer.getAllClasses()
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> result.put(e.getKey(), e.getValue().toString()));
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> resolveTransactionXml(String txId) {
        try (Session session = driver.session()) {
            List<Record> records = session.run(
                    "MATCH (tx:Transaction {id: $txId}) " +
                    "RETURN tx.filePath AS filePath LIMIT 1",
                    Values.parameters("txId", txId)).list();
            if (records.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            String filePath = stringValue(records.get(0), "filePath");
            if (isBlank(filePath)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(pointer(preferSourceMetadataPath(filePath).toString(), "", "<flowtran", "xml"));
        }
    }

    private ResponseEntity<Map<String, Object>> resolveTransactionMethod(String txId, String nodeCode) {
        try (Session session = driver.session()) {
            List<Record> records = session.run(
                    "MATCH (tx:Transaction {id: $txId})-[:HAS_FLOW]->(flow:FlowBlock) " +
                    "MATCH p = (flow)-[:HAS_STEP|EXECUTES|HAS_BRANCH|NEXT*0..24]->(step:FlowMethodStep) " +
                    "WHERE step.id = $nodeCode OR step.methodName = $nodeCode " +
                    "WITH DISTINCT step " +
                    "OPTIONAL MATCH (step)-[:RESOLVES_TO_METHOD]->(m:Method) " +
                    "RETURN step.id AS stepId, step.methodName AS methodName, m.classFqn AS classFqn " +
                    "ORDER BY CASE WHEN step.id = $nodeCode THEN 0 ELSE 1 END " +
                    "LIMIT 1",
                    Values.parameters("txId", txId, "nodeCode", nodeCode)).list();
            if (records.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Record record = records.get(0);
            String classFqn = stringValue(record, "classFqn");
            String methodName = firstNonBlank(stringValue(record, "methodName"), stringValue(record, "stepId"));
            if (isBlank(classFqn)) {
                return ResponseEntity.notFound().build();
            }

            Optional<Path> file = projectIndexer.findByFqn(classFqn);
            if (file.isEmpty()) {
                String className = classFqn.contains(".") ? classFqn.substring(classFqn.lastIndexOf('.') + 1) : classFqn;
                file = projectIndexer.findBySimpleName(className);
            }
            if (file.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(pointer(file.get().toString(), methodName, "", "java"));
        }
    }

    private ResponseEntity<Map<String, Object>> resolveServiceTypeXml(String nodeCode) {
        String serviceTypeId = splitLeft(nodeCode);
        String serviceId = splitRight(nodeCode);
        if (isBlank(serviceTypeId) || isBlank(serviceId)) {
            return ResponseEntity.notFound().build();
        }

        try (Session session = driver.session()) {
            List<Record> records = session.run(
                    "MATCH (stype:ServiceType {id: $serviceTypeId})-[:DECLARES_OPERATION]->(op:ServiceOperation {serviceId: $serviceId}) " +
                    "RETURN stype.filePath AS filePath, op.serviceId AS serviceId LIMIT 1",
                    Values.parameters("serviceTypeId", serviceTypeId, "serviceId", serviceId)).list();
            if (records.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            String filePath = stringValue(records.get(0), "filePath");
            String resolvedServiceId = stringValue(records.get(0), "serviceId");
            if (isBlank(filePath)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(pointer(preferSourceMetadataPath(filePath).toString(), "", "<service id=\"" + resolvedServiceId + "\"", "xml"));
        }
    }

    private Map<String, Object> buildResponse(Path path, String methodName) throws IOException {
        String source = Files.readString(path);
        String filename = path.getFileName().toString();
        String language = filename.toLowerCase(Locale.ROOT).endsWith(".xml") ? "xml" : "java";

        String pkg = "";
        if ("java".equals(language)) {
            for (String line : source.split("\n", 20)) {
                String t = line.trim();
                if (t.startsWith("package ")) {
                    pkg = t.replace("package ", "").replace(";", "").trim();
                    break;
                }
            }
        }

        String kind = "xml".equals(language) ? "xml" : "class";
        if ("java".equals(language)) {
            if (source.contains(" interface ")) {
                kind = "interface";
            } else if (source.contains(" enum ")) {
                kind = "enum";
            } else if (source.contains("abstract class")) {
                kind = "abstract class";
            }
        }

        List<Map<String, Object>> symbols;
        Map<String, String> typeMap;
        if ("java".equals(language) && projectIndexer.isIndexed() && !projectIndexer.getSymbols(path).isEmpty()) {
            symbols = projectIndexer.getSymbols(path);
            typeMap = projectIndexer.getTypeMap(path);
        } else if ("java".equals(language)) {
            ParsedInfo info = parseJava(source);
            symbols = info.symbols();
            typeMap = info.typeMap();
        } else {
            symbols = List.of();
            typeMap = Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", filename);
        result.put("source", source);
        result.put("filePath", path.toString());
        result.put("language", language);
        result.put("pkg", pkg);
        result.put("kind", kind);
        result.put("symbols", symbols);
        result.put("typeMap", typeMap);
        if (!methodName.isBlank()) {
            result.put("entryMethod", methodName);
        }
        return result;
    }

    private ParsedInfo parseJava(String source) {
        List<Map<String, Object>> symbols = new ArrayList<>();
        Map<String, String> typeMap = new LinkedHashMap<>();
        try {
            ParserConfiguration cfg = new ParserConfiguration();
            cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            ParseResult<CompilationUnit> result = new JavaParser(cfg).parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return new ParsedInfo(symbols, typeMap);
            }

            CompilationUnit cu = result.getResult().get();
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                    addTypeSym(n, n.isInterface() ? "interface" : "class", symbols);
                    super.visit(n, arg);
                }

                @Override
                public void visit(EnumDeclaration n, Void arg) {
                    addTypeSym(n, "enum", symbols);
                    super.visit(n, arg);
                }

                @Override
                public void visit(MethodDeclaration n, Void arg) {
                    super.visit(n, arg);
                    Map<String, Object> sym = new LinkedHashMap<>();
                    sym.put("name", n.getNameAsString());
                    sym.put("line", n.getName().getBegin().map(p -> p.line).orElse(-1));
                    sym.put("type", "method");
                    symbols.add(sym);
                }

                @Override
                public void visit(FieldDeclaration n, Void arg) {
                    super.visit(n, arg);
                    for (VariableDeclarator v : n.getVariables()) {
                        typeMap.put(v.getNameAsString(), v.getTypeAsString().replaceAll("<.*", "").trim());
                    }
                }

                private void addTypeSym(TypeDeclaration<?> n, String declType, List<Map<String, Object>> out) {
                    Map<String, Object> sym = new LinkedHashMap<>();
                    sym.put("name", n.getNameAsString());
                    sym.put("line", n.getName().getBegin().map(p -> p.line).orElse(-1));
                    sym.put("type", declType);
                    out.add(sym);
                }
            }, null);
        } catch (Exception ignored) {
        }
        return new ParsedInfo(symbols, typeMap);
    }

    private Path deriveSourceRoot(Path filePath) {
        String s = filePath.toString().replace('\\', '/');
        int idx = s.indexOf("/src/main/java/");
        if (idx >= 0) {
            return Path.of(s.substring(0, idx + "/src/main/java".length()));
        }
        idx = s.indexOf("/src/main/kotlin/");
        if (idx >= 0) {
            return Path.of(s.substring(0, idx + "/src/main/kotlin".length()));
        }
        return filePath.getParent();
    }

    private Map<String, Object> pointer(String filePath, String methodName, String locateText, String language) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", filePath);
        result.put("methodName", methodName);
        result.put("locateText", locateText);
        result.put("language", language);
        return result;
    }

    private String stringValue(Record record, String key) {
        return record.get(key).isNull() ? null : record.get(key).asString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String splitLeft(String serviceName) {
        if (serviceName == null || !serviceName.contains(".")) {
            return serviceName == null ? "" : serviceName;
        }
        return serviceName.substring(0, serviceName.indexOf('.'));
    }

    private String splitRight(String serviceName) {
        if (serviceName == null || !serviceName.contains(".")) {
            return "";
        }
        return serviceName.substring(serviceName.indexOf('.') + 1);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Path preferSourceMetadataPath(Path originalPath) {
        String normalized = originalPath.toString().replace('\\', '/');
        if (!normalized.endsWith(".xml")) {
            return originalPath;
        }
        String candidateText = normalized.replace("/target/classes/", "/src/main/resources/");
        if (candidateText.equals(normalized)) {
            return originalPath;
        }
        Path candidate = Paths.get(candidateText).toAbsolutePath().normalize();
        return Files.exists(candidate) ? candidate : originalPath;
    }

    private Path preferSourceMetadataPath(String originalPath) {
        return preferSourceMetadataPath(Paths.get(originalPath).toAbsolutePath().normalize());
    }

    private record ParsedInfo(List<Map<String, Object>> symbols, Map<String, String> typeMap) {}
}
