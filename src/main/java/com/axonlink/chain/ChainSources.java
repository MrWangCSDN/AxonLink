package com.axonlink.chain;

import com.axonlink.service.ProjectIndexer;
import com.axonlink.service.SourceFiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.axonlink.chain.ChainResult.fields;

@Component
final class ChainSources {
    private final List<Path> roots;
    private final ProjectIndexer indexer;

    ChainSources(@Value("${project.workspace-roots:}") String workspaceRoots,
                 @Value("${project.source-roots:}") String sourceRoots, ProjectIndexer indexer) {
        this.indexer = indexer;
        this.roots = Arrays.stream((workspaceRoots + "," + sourceRoots).split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).map(Path::of)
            .map(p -> p.toAbsolutePath().normalize()).distinct().toList();
    }

    String display(String file) {
        if (file == null) return null;
        Path path;
        try { path = Path.of(file).toAbsolutePath().normalize(); }
        catch (java.nio.file.InvalidPathException ex) { return null; }
        for (Path root : roots) if (path.startsWith(root)) {
            Path name = root.getFileName();
            return (name == null ? root.relativize(path) : name.resolve(root.relativize(path))).toString().replace('\\','/');
        }
        return path.getFileName() == null ? null : path.getFileName().toString();
    }

    Path readable(String file) throws IOException {
        if (file == null) return null;
        Path path = SourceFiles.preferred(Path.of(file).toAbsolutePath().normalize());
        if (!Files.isRegularFile(path)) return null;
        Path real = path.toRealPath();
        String name = real.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".java") && !name.endsWith(".xml")) return null;
        for (Path root : roots) {
            if (Files.isDirectory(root) && real.startsWith(root.toRealPath()) && Files.isReadable(real)) return real;
        }
        return null;
    }

    Map<String,Object> callSite(ChainGraph.Edge edge) {
        if (!ChainGraph.CALL.contains(edge.type())) return null;
        var result = fields("file", display(edge.from().file), "line", edge.line() > 0 ? edge.line() : null);
        if (edge.line() > 0) result.put("sourceRef", "call:" + edge.line() + ":" + edge.from().id);
        if (edge.rawTarget() != null) result.put("rawTarget", edge.rawTarget().id);
        if (edge.method() != null) result.put("methodName", edge.method());
        return result;
    }

    Map<String,Object> context(ChainGraph graph, String ref, int radius, ChainResult.Notes notes) {
        int line;
        ChainGraph.Node node;
        boolean call = ref.startsWith("call:");
        if (call) {
            String[] parts = ref.split(":",3);
            try {
                if (parts.length != 3) throw new NumberFormatException();
                line = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ex) { throw invalidRef(); }
            node = graph.require(parts[2]);
            if (line < 1 || graph.edges(graph.outgoing,node).stream().noneMatch(
                    e -> ChainGraph.CALL.contains(e.type()) && e.line() == line)) throw invalidRef();
        } else {
            if (!ref.startsWith("def:")) throw invalidRef();
            node = graph.require(ref.substring(4));
            line = node.line;
        }
        String file = node.file;
        if (node.kind.equals("dao")) {
            Optional<Path> javaFile = node.text("outerClassFqn") == null ? Optional.empty() : indexer.findByFqn(node.text("outerClassFqn"));
            if (javaFile.isEmpty() && node.text("outerClassName") != null) javaFile = indexer.findBySimpleName(node.text("outerClassName"));
            file = javaFile.map(Path::toString).orElse(file);
        }
        String language = file == null ? null : file.toLowerCase(Locale.ROOT).endsWith(".xml") ? "xml" : "java";
        var data = fields("sourceRef", ref, "locationKind", call ? "callSite" : "definition", "file", display(file),
            "language", language, "focusLine", line > 0 ? line : null, "startLine", null, "endLine", null, "text", null);
        try {
            Path path = readable(file);
            if (path != null && !(node.kind.equals("dao") && "xml".equals(language))) {
                data.put("file", display(path.toString()));
                if (graph.snapshotAt != null && Files.getLastModifiedTime(path).toInstant()
                        .isAfter(java.time.Instant.parse(graph.snapshotAt))) {
                    notes.add("SOURCE_CHANGED","工作区文件晚于图构建时间，片段来自当前文件",node.id);
                }
                int focus = line > 0 ? line : definitionLine(path, node);
                if (focus > 0) {
                    int start = Math.max(1,focus - radius);
                    List<String> lines;
                    try (var stream = Files.lines(path)) {
                        lines = stream.skip(start - 1L).limit(focus - start + radius + 1L).toList();
                    }
                    if (start + lines.size() > focus) {
                        String snippet = String.join("\n", lines);
                        if (snippet.length() > 32768) {
                            notes.truncated = true;
                            notes.add("LIMIT_REACHED","源码行过长，仅返回聚焦行及其前 32768 个字符",node.id);
                            String focused = lines.get(focus - start);
                            data.putAll(fields("focusLine",focus,"startLine",focus,"endLine",focus,
                                "text",focused.substring(0,Math.min(focused.length(),32768))));
                        } else {
                            data.putAll(fields("focusLine", focus, "startLine", start, "endLine", start + lines.size() - 1,
                                "text", snippet));
                        }
                        return data;
                    }
                }
            }
        } catch (Exception ex) {
            // 当前位置仍可用于定位原扫描结果。
        }
        notes.add("SOURCE_UNAVAILABLE","当前扫描工作区无法读取或定位该源码片段",node.id);
        return data;
    }

    private static int definitionLine(Path path, ChainGraph.Node node) throws Exception {
        if (path.toString().toLowerCase(Locale.ROOT).endsWith(".xml")) {
            String id = switch (node.kind) {
                case "service","component" -> node.text("serviceId");
                case "dao" -> node.text("tableId");
                default -> node.text("id");
            };
            String tag = switch (node.kind) {
                case "service","component","flowServiceStep" -> "service";
                case "transaction" -> "flowtran";
                case "serviceType" -> "serviceType";
                case "table","dao" -> "table";
                case "flowBlock" -> "flow";
                case "flowCase" -> "case";
                case "flowWhen" -> "when";
                case "flowMethodStep" -> "method";
                default -> null;
            };
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            try (var input = Files.newInputStream(path)) {
                var xml = factory.createXMLStreamReader(input);
                try {
                    int matched = 0;
                    while (xml.hasNext()) if (xml.next() == XMLStreamConstants.START_ELEMENT &&
                        Objects.equals(tag, xml.getLocalName()) && Objects.equals(id, xml.getAttributeValue(null,"id"))) {
                        if (matched > 0) return 0;
                        matched = xml.getLocation().getLineNumber();
                    }
                    return matched;
                } finally { xml.close(); }
            }
        } else {
            var parser = new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17));
            var unit = parser.parse(path).getResult();
            if (unit.isEmpty()) return 0;
            for (var type : unit.get().findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)) {
                if (node.kind.equals("dao") && type.getNameAsString().equals(node.text("daoClassName"))) {
                    var methods = type.getMethodsByName(node.text("methodName"));
                    return methods.size() == 1 ? methods.get(0).getBegin().map(p -> p.line).orElse(0) : 0;
                }
                if ((node.kind.equals("class") || node.kind.equals("interface")) &&
                    type.getNameAsString().equals(node.text("simpleName"))) return type.getBegin().map(p -> p.line).orElse(0);
            }
        }
        return 0;
    }

    private static ChainResult.Problem invalidRef() {
        return new ChainResult.Problem("INVALID_ARGUMENT","源码引用无效或调用行与当前图不匹配");
    }
}
