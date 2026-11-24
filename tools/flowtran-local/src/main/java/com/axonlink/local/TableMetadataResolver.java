package com.axonlink.local;

// 提取自 AxonLink service/TableMetadataResolver.java。

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 解析 workspace 中的 *.tables.xml，建立表 -> Dao -> 外层生成类的映射。
 */
final class TableMetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(TableMetadataResolver.class);
    private static final Set<String> ALLOWED_DOMAINS = Set.of("deposit", "loan", "settlement", "public", "platform");

    private final String workspaceRoots;
    private final MetadataResourceScanner metadataResourceScanner;
    private final ScanProgress progress;
    private final ScanResult metadata;

    TableMetadataResolver(MetadataResourceScanner metadataResourceScanner, String workspaceRoots,
                          Graph graph, ScanProgress progress) {
        this.metadataResourceScanner = metadataResourceScanner;
        this.workspaceRoots = workspaceRoots;
        this.progress = progress;
        this.metadata = scan(workspaceRoots, graph);
    }

    Optional<TableMeta> findByDaoClassName(String daoClassName) {
        return isBlank(daoClassName) ? Optional.empty() : Optional.ofNullable(metadata.daoClassIndex().get(daoClassName));
    }

    List<TableMeta> allMetadata() {
        return metadata.allTables();
    }

    private ScanResult scan(String roots, Graph graph) {
        progress.begin("表定义 XML 解析");
        Map<String, TableMeta> byDaoClass = new LinkedHashMap<>();
        List<TableMeta> collected = new ArrayList<>();
        List<Path> files = collectFiles(roots);
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            progress.file(file, i + 1, files.size());
            List<TableMeta> metas;
            try {
                metas = parseTables(file);
            } catch (Exception e) {
                graph.report(XmlFiles.issue(file, "TABLE_XML", e));
                continue;
            }
            for (TableMeta meta : metas) {
                if (!ALLOWED_DOMAINS.contains(meta.domainKey())) {
                    continue;
                }
                collected.add(meta);
                registerFirstSeen(byDaoClass, meta.daoClassName(), meta);
            }
        }

        progress.finish("candidateFiles=%d tableDefinitions=%d daoClasses=%d"
            .formatted(files.size(), collected.size(), byDaoClass.size()));
        return new ScanResult(
            Collections.unmodifiableMap(byDaoClass),
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
        Document doc = XmlFiles.read(file);
        Element root = doc.getDocumentElement();
        if (root == null || !"schema".equalsIgnoreCase(root.getTagName())) {
            progress.skip(file, -1, "表定义需要 schema 根元素");
            return List.of();
        }

        String schemaId = attr(root, "id");
        String packagePath = firstNonBlank(
            attr(root, "package"),
            attr(root, "packagePath"),
            attr(root, "package_name")
        );
        if (isBlank(schemaId) || isBlank(packagePath)) {
            progress.skip(file, -1, "schema 缺少 id 或包名");
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
            log.debug("[scan={}] 跳过重复 DAO 定义 dao={} file={} kept={}",
                progress.id(), key, meta.xmlFilePath(), existing.xmlFilePath());
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
