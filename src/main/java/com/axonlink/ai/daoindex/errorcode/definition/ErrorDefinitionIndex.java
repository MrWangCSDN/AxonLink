package com.axonlink.ai.daoindex.errorcode.definition;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads workspace error XML definitions into immutable lookup maps for Excel export. */
@Component
public final class ErrorDefinitionIndex {

    private static final Logger log = LoggerFactory.getLogger(ErrorDefinitionIndex.class);
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", "target", "build");

    private final String workspaceRoots;
    private volatile Snapshot snapshot = Snapshot.empty();

    public ErrorDefinitionIndex(@Value("${project.workspace-roots:}") String workspaceRoots) {
        this.workspaceRoots = workspaceRoots == null ? "" : workspaceRoots;
    }

    /** Rebuilds the complete index and publishes it atomically after all readable files are parsed. */
    @PostConstruct
    public void reload() {
        snapshot = buildSnapshot();
    }

    /**
     * Two-segment scopes use the full key; one-segment scopes use the unique errors.id suffix.
     * Unsupported shapes and ambiguous keys deliberately return empty.
     */
    public Optional<ErrorDefinition> lookup(String errorScope, String errorCode) {
        String scope = trim(errorScope);
        String code = trim(errorCode);
        if (scope.isEmpty() || code.isEmpty() || code.contains(".")) {
            return Optional.empty();
        }
        String[] scopeParts = scope.split("\\.", -1);
        if (scopeParts.length == 2 && !scopeParts[0].isBlank() && !scopeParts[1].isBlank()) {
            return Optional.ofNullable(snapshot.exact().get(scope + "." + code));
        }
        if (scopeParts.length == 1 && !scopeParts[0].isBlank()) {
            return Optional.ofNullable(snapshot.suffix().get(scope + "." + code));
        }
        return Optional.empty();
    }

    private Snapshot buildSnapshot() {
        CandidateMap exact = new CandidateMap();
        CandidateMap suffix = new CandidateMap();
        List<Path> files = discoverFiles();
        DocumentBuilderFactory factory;
        try {
            factory = secureDocumentBuilderFactory();
        } catch (Exception e) {
            log.error("[error-code-definition] XML parser security configuration failed; index is empty", e);
            return Snapshot.empty();
        }

        int parsedFiles = 0;
        for (Path file : files) {
            try {
                parseFile(factory, file, exact, suffix);
                parsedFiles++;
            } catch (Exception e) {
                log.warn("[error-code-definition] failed to parse file={}: {}", file, e.getMessage());
            }
        }

        Snapshot built = new Snapshot(exact.snapshot(), suffix.snapshot());
        log.info("[error-code-definition] index loaded discoveredFiles={} parsedFiles={} exact={} suffix={} "
                        + "ambiguousExact={} ambiguousSuffix={}",
                files.size(), parsedFiles, built.exact().size(), built.suffix().size(),
                exact.ambiguityCount(), suffix.ambiguityCount());
        return built;
    }

    private List<Path> discoverFiles() {
        List<Path> files = new ArrayList<>();
        if (workspaceRoots.isBlank()) {
            log.warn("[error-code-definition] project.workspace-roots is empty; index is empty");
            return files;
        }
        for (String configuredRoot : workspaceRoots.split(",")) {
            String value = configuredRoot.trim();
            if (value.isEmpty()) {
                continue;
            }
            Path root;
            try {
                root = Path.of(value);
            } catch (InvalidPathException e) {
                log.warn("[error-code-definition] workspace root is invalid root={}: {}", value, e.getMessage());
                continue;
            }
            if (containsExcludedDirectory(root)) {
                log.warn("[error-code-definition] workspace root is excluded root={}", root);
                continue;
            }
            if (!Files.isDirectory(root)) {
                log.warn("[error-code-definition] workspace root is unavailable root={}", root);
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".error.xml")) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        log.warn("[error-code-definition] failed to visit path={}: {}", file, exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.warn("[error-code-definition] failed to scan workspace root={}: {}", root, e.getMessage());
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static boolean containsExcludedDirectory(Path path) {
        for (Path segment : path.toAbsolutePath().normalize()) {
            if (EXCLUDED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static void parseFile(DocumentBuilderFactory factory, Path file,
                                  CandidateMap exact, CandidateMap suffix) throws Exception {
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
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
        });
        Document document = builder.parse(file.toFile());
        Element errorConf = document.getDocumentElement();
        if (errorConf == null || !"errorConf".equals(elementName(errorConf))) {
            return;
        }
        String confId = trim(errorConf.getAttribute("id"));
        if (confId.isEmpty()) {
            return;
        }
        for (Element errors : directChildren(errorConf, "errors")) {
            String errorsId = trim(errors.getAttribute("id"));
            if (errorsId.isEmpty()) {
                continue;
            }
            for (Element error : directChildren(errors, "error")) {
                String errorId = trim(error.getAttribute("id"));
                if (errorId.isEmpty()) {
                    continue;
                }
                ErrorDefinition definition = new ErrorDefinition(
                        error.getAttribute("errorCode"), error.getAttribute("message"));
                exact.add(confId + "." + errorsId + "." + errorId, definition);
                suffix.add(errorsId + "." + errorId, definition);
            }
        }
    }

    private static List<Element> directChildren(Element parent, String expectedName) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && expectedName.equals(elementName(element))) {
                children.add(element);
            }
        }
        return children;
    }

    private static String elementName(Element element) {
        String localName = element.getLocalName();
        return localName == null ? element.getNodeName() : localName;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record ErrorDefinition(String errorCode, String message) {
    }

    private record Snapshot(Map<String, ErrorDefinition> exact,
                            Map<String, ErrorDefinition> suffix) {
        private static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of());
        }
    }

    private static final class CandidateMap {
        private final Map<String, ErrorDefinition> candidates = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();

        private void add(String key, ErrorDefinition definition) {
            if (ambiguous.contains(key)) {
                return;
            }
            ErrorDefinition existing = candidates.putIfAbsent(key, definition);
            if (existing != null && !existing.equals(definition)) {
                candidates.remove(key);
                ambiguous.add(key);
            }
        }

        private Map<String, ErrorDefinition> snapshot() {
            return Map.copyOf(candidates);
        }

        private int ambiguityCount() {
            return ambiguous.size();
        }
    }
}
