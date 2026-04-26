package com.axonlink.service;

import com.axonlink.common.DomainKeyResolver;
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
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 解析 workspace 中的 *.tables.xml，建立表 -> Dao -> 外层生成类的映射。
 */
@Component
public class TableMetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(TableMetadataResolver.class);
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

    private static final Set<String> ALLOWED_DOMAINS = Set.of("deposit", "loan", "settlement", "public", "platform");

    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    private final MetadataResourceScanner metadataResourceScanner;
    private final Object reloadLock = new Object();

    private volatile Map<String, TableMeta> daoClassIndex = Map.of();
    private volatile Map<String, TableMeta> tableIdIndex = Map.of();
    private volatile List<TableMeta> allTables = List.of();
    private volatile String cachedRoots = null;
    private volatile boolean loaded = false;

    public TableMetadataResolver(MetadataResourceScanner metadataResourceScanner) {
        this.metadataResourceScanner = metadataResourceScanner;
    }

    public Optional<TableMeta> findByDaoClassName(String daoClassName) {
        if (isBlank(daoClassName)) {
            return Optional.empty();
        }
        ensureLoaded();
        return Optional.ofNullable(daoClassIndex.get(daoClassName));
    }

    public Optional<TableMeta> findByTableId(String tableId) {
        if (isBlank(tableId)) {
            return Optional.empty();
        }
        ensureLoaded();
        return Optional.ofNullable(tableIdIndex.get(tableId));
    }

    public List<TableMeta> allMetadata() {
        ensureLoaded();
        return allTables;
    }

    public void warmUp() {
        ensureLoaded();
    }

    private void ensureLoaded() {
        String roots = workspaceRoots == null ? "" : workspaceRoots.trim();
        if (loaded && Objects.equals(cachedRoots, roots)) {
            return;
        }
        synchronized (reloadLock) {
            if (loaded && Objects.equals(cachedRoots, roots)) {
                return;
            }
            ScanResult scanResult = scan(roots);
            daoClassIndex = scanResult.daoClassIndex();
            tableIdIndex = scanResult.tableIdIndex();
            allTables = scanResult.allTables();
            cachedRoots = roots;
            loaded = true;
        }
    }

    private ScanResult scan(String roots) {
        if (roots == null || roots.isBlank()) {
            return new ScanResult(Map.of(), Map.of(), List.of());
        }

        List<Path> files = collectFiles(roots);
        Map<Path, List<TableMeta>> parsedTables = new ConcurrentHashMap<>();
        files.parallelStream().forEach(file -> {
            try {
                List<TableMeta> metas = parseTables(file);
                if (!metas.isEmpty()) {
                    parsedTables.put(file, metas);
                }
            } catch (Exception e) {
                log.debug("[TableMeta] 解析 {} 失败: {}", file.getFileName(), e.getMessage());
            }
        });
        Map<String, TableMeta> byDaoClass = new LinkedHashMap<>();
        Map<String, TableMeta> byTableId = new LinkedHashMap<>();
        List<TableMeta> collected = new ArrayList<>();

        for (Path file : files) {
            List<TableMeta> metas = parsedTables.get(file);
            if (metas == null) {
                continue;
            }
            for (TableMeta meta : metas) {
                if (!ALLOWED_DOMAINS.contains(meta.domainKey())) {
                    continue;
                }
                collected.add(meta);
                registerFirstSeen(byDaoClass, meta.daoClassName(), meta);
                registerFirstSeen(byTableId, meta.tableId(), meta);
            }
        }

        return new ScanResult(
            Collections.unmodifiableMap(byDaoClass),
            Collections.unmodifiableMap(byTableId),
            List.copyOf(collected)
        );
    }

    private List<Path> collectFiles(String roots) {
        return metadataResourceScanner.collectMetadataFiles(
            roots,
            8,
            8,
            path -> path.getFileName().toString().toLowerCase().endsWith(".tables.xml")
                && path.toString().replace('\\', '/').contains("/tables/")
        );
    }

    private List<TableMeta> parseTables(Path file) throws Exception {
        Document doc = parseXml(file);
        Element root = doc.getDocumentElement();
        if (root == null || !"schema".equalsIgnoreCase(root.getTagName())) {
            return List.of();
        }

        String schemaId = attr(root, "id");
        String packagePath = firstNonBlank(
            attr(root, "package"),
            attr(root, "packagePath"),
            attr(root, "package_name")
        );
        if (isBlank(schemaId) || isBlank(packagePath)) {
            return List.of();
        }

        String moduleName = detectModuleName(file);
        String domainKey = resolveTableDomain(moduleName, packagePath, file);
        String outerClassName = schemaId;
        String outerClassFqn = packagePath + "." + schemaId;
        String xmlFilePath = file.toString().replace('\\', '/');

        List<TableMeta> result = new ArrayList<>();
        NodeList childNodes = root.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node child = childNodes.item(i);
            if (!(child instanceof Element element) || !"table".equalsIgnoreCase(element.getTagName())) {
                continue;
            }
            String tableId = attr(element, "id");
            if (isBlank(tableId)) {
                continue;
            }
            String tableLongname = firstNonBlank(attr(element, "longname"), attr(element, "name"), tableId);
            result.add(new TableMeta(
                tableId,
                tableLongname,
                domainKey,
                moduleName,
                xmlFilePath,
                packagePath,
                schemaId,
                outerClassName,
                outerClassFqn,
                deriveDaoClassName(tableId)
            ));
        }
        return result;
    }

    private String resolveTableDomain(String moduleName, String packagePath, Path file) {
        String resolvedByModule = DomainKeyResolver.resolveProject(moduleName);
        if (resolvedByModule != null && ALLOWED_DOMAINS.contains(resolvedByModule)) {
            return resolvedByModule;
        }

        String normalizedPackage = normalizeText(packagePath);
        String normalizedFilePath = normalizeText(file.toString());
        String normalizedModuleName = normalizeText(moduleName);

        String matchedByPackage = detectDomainFromText(normalizedPackage);
        if (matchedByPackage != null) {
            return matchedByPackage;
        }

        String matchedByFilePath = detectDomainFromText(normalizedFilePath);
        if (matchedByFilePath != null) {
            return matchedByFilePath;
        }

        if (isPublicSupportModule(normalizedModuleName)
            || isPublicSupportPath(normalizedPackage)
            || isPublicSupportPath(normalizedFilePath)) {
            return "public";
        }

        String fallback = DomainKeyResolver.resolve(packagePath);
        return ALLOWED_DOMAINS.contains(fallback) ? fallback : "public";
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

    private String detectModuleName(Path file) {
        String normalized = file.toString().replace('\\', '/');
        int sourceIdx = normalized.indexOf("/src/main/resources/");
        if (sourceIdx < 0) {
            sourceIdx = normalized.indexOf("/target/classes/");
        }
        if (sourceIdx < 0) {
            return detectParentProject(file);
        }
        String prefix = normalized.substring(0, sourceIdx);
        int lastSlash = prefix.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == prefix.length() - 1) {
            return detectParentProject(file);
        }
        return prefix.substring(lastSlash + 1);
    }

    private void registerFirstSeen(Map<String, TableMeta> index, String key, TableMeta meta) {
        if (isBlank(key)) {
            return;
        }
        TableMeta existing = index.putIfAbsent(key, meta);
        if (existing != null && !existing.equals(meta)) {
            log.debug("[TableMeta] 忽略重复键 {} -> {}，保留 {}", key, meta.xmlFilePath(), existing.xmlFilePath());
        }
    }

    private static String deriveDaoClassName(String tableId) {
        if (isBlank(tableId)) {
            return tableId;
        }
        return Character.toUpperCase(tableId.charAt(0)) + tableId.substring(1) + "Dao";
    }

    private static String attr(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase()
            .replace('\\', '/')
            .replace('.', '/')
            .replace('-', '/')
            .replace('_', '/');
    }

    private static String detectDomainFromText(String normalizedText) {
        if (normalizedText.isBlank()) {
            return null;
        }
        if (containsAny(normalizedText,
            "/dept/",
            "/deptbase/",
            "/ltdept",
            "/itdept",
            "/kdp")) {
            return "deposit";
        }
        if (containsAny(normalizedText,
            "/loan/",
            "/loanbase/",
            "/lnacct",
            "/lnctrct",
            "/lntran",
            "/kln")) {
            return "loan";
        }
        if (containsAny(normalizedText,
            "/sett/",
            "/settbase/",
            "/stt",
            "/kst")) {
            return "settlement";
        }
        if (containsToken(normalizedText, "comm") || containsToken(normalizedText, "common")) {
            return "public";
        }
        return null;
    }

    private static boolean isPublicSupportModule(String normalizedModuleName) {
        return containsToken(normalizedModuleName, "comm")
            || containsToken(normalizedModuleName, "unvr")
            || containsToken(normalizedModuleName, "stmt")
            || containsToken(normalizedModuleName, "medu")
            || containsToken(normalizedModuleName, "inbu");
    }

    private static boolean isPublicSupportPath(String normalizedText) {
        return containsToken(normalizedText, "common")
            || containsToken(normalizedText, "comm")
            || containsToken(normalizedText, "unvr")
            || containsToken(normalizedText, "apcptable")
            || containsToken(normalizedText, "sysdbtable")
            || containsToken(normalizedText, "syscommfieldtable")
            || containsToken(normalizedText, "stmt");
    }

    private static boolean containsToken(String normalizedText, String token) {
        return normalizedText.equals(token)
            || normalizedText.startsWith(token + "/")
            || normalizedText.endsWith("/" + token)
            || normalizedText.contains("/" + token + "/");
    }

    private static boolean containsAny(String normalizedText, String... patterns) {
        for (String pattern : patterns) {
            if (normalizedText.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private record ScanResult(
        Map<String, TableMeta> daoClassIndex,
        Map<String, TableMeta> tableIdIndex,
        List<TableMeta> allTables
    ) {}

    public record TableMeta(
        String tableId,
        String tableLongname,
        String domainKey,
        String projectName,
        String xmlFilePath,
        String packagePath,
        String schemaId,
        String outerClassName,
        String outerClassFqn,
        String daoClassName
    ) {}
}
