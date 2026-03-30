package com.axonlink.service;

import com.axonlink.common.DomainKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 从工作区 XML 元数据中补充流程编排服务/构件的文件、类型和领域信息。
 *
 * <p>兼容多类定义文件：
 * <ul>
 *   <li>{@code *.serviceType.xml}</li>
 *   <li>{@code *.pbs.xml}</li>
 *   <li>{@code *.pcs.xml}</li>
 *   <li>{@code *.pbcb.xml}</li>
 *   <li>{@code *.pbcp.xml}</li>
 *   <li>{@code *.pbcc.xml}</li>
 *   <li>{@code *.pbct.xml}</li>
 * </ul>
 *
 * <p>同时记录实现类 XML：
 * <ul>
 *   <li>{@code *.pbsImpl.xml}</li>
 *   <li>{@code *.pcsImpl.xml}</li>
 *   <li>{@code *.pbcbImpl.xml}</li>
 *   <li>{@code *.pbcpImpl.xml}</li>
 *   <li>{@code *.pbccImpl.xml}</li>
 *   <li>{@code *.pbctImpl.xml}</li>
 * </ul>
 */
@Component
public class FlowServiceMetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(FlowServiceMetadataResolver.class);
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

    private static final Map<String, String> PARENT_PROJECT_DOMAIN_MAP = Map.of(
        "ap-parent", "ap",
        "unvr-parent", "unvr",
        "stmt-parent", "stmt",
        "medu-parent", "medu",
        "inbu-parent", "inbu",
        "dept-parent", "dept",
        "aggr-parent", "aggr"
    );

    private static final List<String> KNOWN_SUFFIXES = List.of(
        ".serviceType.xml",
        ".pbs.xml",
        ".pcs.xml",
        ".pbcb.xml",
        ".pbcp.xml",
        ".pbcc.xml",
        ".pbct.xml",
        ".pbsImpl.xml",
        ".pcsImpl.xml",
        ".pbcbImpl.xml",
        ".pbcpImpl.xml",
        ".pbccImpl.xml",
        ".pbctImpl.xml"
    );

    private static final List<String> INDEXED_SUFFIXES = List.of(
        ".serviceType.xml",
        ".pbs.xml",
        ".pcs.xml",
        ".pbcb.xml",
        ".pbcp.xml",
        ".pbcc.xml",
        ".pbct.xml",
        ".pbsImpl.xml",
        ".pcsImpl.xml",
        ".pbcbImpl.xml",
        ".pbcpImpl.xml",
        ".pbccImpl.xml",
        ".pbctImpl.xml"
    );

    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    private final Object reloadLock = new Object();

    private volatile Map<String, ServiceTypeFileMeta> cache = Map.of();
    private volatile String cachedRoots = null;
    private volatile boolean loaded = false;

    public Optional<ServiceTypeFileMeta> findByTypeId(String typeId) {
        if (isBlank(typeId)) {
            return Optional.empty();
        }
        ensureLoaded();
        return Optional.ofNullable(cache.get(typeId));
    }

    public void warmUp() {
        ensureLoaded();
    }

    public List<ServiceTypeFileMeta> allMetadata() {
        ensureLoaded();
        return List.copyOf(cache.values());
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
            cache = scan(roots);
            cachedRoots = roots;
            loaded = true;
        }
    }

    private Map<String, ServiceTypeFileMeta> scan(String roots) {
        if (roots == null || roots.isBlank()) {
            return Map.of();
        }

        List<Path> files = collectFiles(roots);
        Map<String, MutableMeta> mutable = new LinkedHashMap<>();

        for (Path file : files) {
            try {
                FileEnvelope envelope = readEnvelope(file);
                if (isBlank(envelope.typeId)) {
                    continue;
                }

                MutableMeta meta = mutable.computeIfAbsent(envelope.typeId, MutableMeta::new);
                meta.merge(envelope);
            } catch (Exception e) {
                log.debug("[FlowServiceMeta] 解析 {} 失败: {}", file.getFileName(), e.getMessage());
            }
        }

        Map<String, ServiceTypeFileMeta> result = new LinkedHashMap<>();
        for (Map.Entry<String, MutableMeta> entry : mutable.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toImmutable());
        }
        return Collections.unmodifiableMap(result);
    }

    private List<Path> collectFiles(String roots) {
        Map<String, Path> metadataRoots = new LinkedHashMap<>();
        for (String root : roots.split(",")) {
            String trimmed = root.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path ws = Path.of(trimmed);
            if (!Files.exists(ws)) {
                continue;
            }
            collectMetadataRoots(ws, metadataRoots);
        }

        List<Path> files = new ArrayList<>();
        for (Path metadataRoot : metadataRoots.values()) {
            try (Stream<Path> walk = Files.walk(metadataRoot, 6)) {
                walk.filter(Files::isRegularFile)
                    .filter(path -> hasIndexedSuffix(path.getFileName().toString()))
                    .forEach(files::add);
            } catch (Exception e) {
                log.debug("[FlowServiceMeta] 扫描 {} 失败: {}", metadataRoot, e.getMessage());
            }
        }
        files.sort(Path::compareTo);
        return files;
    }

    private void collectMetadataRoots(Path workspaceRoot, Map<String, Path> metadataRoots) {
        try (Stream<Path> walk = Files.walk(workspaceRoot, 8)) {
            walk.filter(Files::isDirectory)
                .filter(this::isMetadataRoot)
                .sorted(Path::compareTo)
                .forEach(path -> registerMetadataRoot(metadataRoots, path));
        } catch (Exception e) {
            log.debug("[FlowServiceMeta] 扫描 {} 失败: {}", workspaceRoot, e.getMessage());
        }
    }

    private boolean isMetadataRoot(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.endsWith("/src/main/resources") || normalized.endsWith("/target/classes");
    }

    private void registerMetadataRoot(Map<String, Path> metadataRoots, Path candidate) {
        String normalized = candidate.toString().replace('\\', '/');
        String suffix = normalized.endsWith("/src/main/resources") ? "/src/main/resources" : "/target/classes";
        String moduleKey = normalized.substring(0, normalized.length() - suffix.length());
        Path existing = metadataRoots.get(moduleKey);
        if (existing == null || isPreferredMetadataRoot(candidate, existing)) {
            metadataRoots.put(moduleKey, candidate);
        }
    }

    private boolean isPreferredMetadataRoot(Path candidate, Path existing) {
        String candidateText = candidate.toString().replace('\\', '/');
        String existingText = existing.toString().replace('\\', '/');
        boolean candidateIsSource = candidateText.endsWith("/src/main/resources");
        boolean existingIsSource = existingText.endsWith("/src/main/resources");
        if (candidateIsSource != existingIsSource) {
            return candidateIsSource;
        }
        return candidateText.compareTo(existingText) < 0;
    }

    private FileEnvelope readEnvelope(Path file) throws Exception {
        Document doc = parseXml(file);
        Element root = doc.getDocumentElement();
        String fileName = file.getFileName().toString();
        String typeId = root != null ? attr(root, "id") : null;
        if (isBlank(typeId)) {
            typeId = stripKnownSuffix(fileName);
        }

        String packagePath = root != null ? firstNonBlank(
            attr(root, "package"),
            attr(root, "packagePath"),
            attr(root, "package_name")
        ) : null;
        String nodeKind = inferNodeKind(fileName, typeId);
        String parentProject = detectParentProject(file);
        String domainKey = resolveDomainKey(parentProject, packagePath);
        String normalizedPath = file.toString().replace('\\', '/');

        return new FileEnvelope(
            typeId,
            packagePath,
            nodeKind,
            domainKey,
            normalizedPath,
            detectModule(file),
            parentProject,
            definitionPriority(fileName),
            isImplFile(fileName)
        );
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

    private String detectModule(Path file) {
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
            if (parts.length >= 2) {
                return parts[0] + "/" + parts[1];
            }
        }
        return "unknown";
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

    private String resolveDomainKey(String parentProject, String packagePath) {
        String fromParent = PARENT_PROJECT_DOMAIN_MAP.get(parentProject);
        if (fromParent != null) {
            return fromParent;
        }
        return DomainKeyResolver.resolve(packagePath);
    }

    private String inferNodeKind(String fileName, String typeId) {
        String lower = fileName.toLowerCase();
        String nodeKind = inferNodeKindFromLowercase(lower);
        if (nodeKind != null) {
            return nodeKind;
        }
        if (!isBlank(typeId)) {
            String loweredTypeId = typeId.toLowerCase();
            nodeKind = inferNodeKindFromLowercase(loweredTypeId);
            if (nodeKind != null) {
                return nodeKind;
            }
        }
        return null;
    }

    private int definitionPriority(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pbs.xml")
            || lower.endsWith(".pcs.xml")
            || lower.endsWith(".pbcb.xml")
            || lower.endsWith(".pbcp.xml")
            || lower.endsWith(".pbcc.xml")
            || lower.endsWith(".pbct.xml")) {
            return 2;
        }
        if (lower.endsWith(".servicetype.xml")) {
            return 1;
        }
        return 0;
    }

    private boolean hasIndexedSuffix(String fileName) {
        String lower = fileName.toLowerCase();
        for (String suffix : INDEXED_SUFFIXES) {
            if (lower.endsWith(suffix.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isImplFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pbsimpl.xml")
            || lower.endsWith(".pcsimpl.xml")
            || lower.endsWith(".pbcbimpl.xml")
            || lower.endsWith(".pbcpimpl.xml")
            || lower.endsWith(".pbccimpl.xml")
            || lower.endsWith(".pbctimpl.xml");
    }

    private String stripKnownSuffix(String fileName) {
        String lower = fileName.toLowerCase();
        for (String suffix : KNOWN_SUFFIXES) {
            String loweredSuffix = suffix.toLowerCase();
            if (lower.endsWith(loweredSuffix)) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        return fileName;
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

    private String inferNodeKindFromLowercase(String lower) {
        if (lower.endsWith(".pbs.xml") || lower.endsWith(".pbsimpl.xml") || lower.endsWith("pbs") || lower.endsWith("pbsimpl")) {
            return "pbs";
        }
        if (lower.endsWith(".pcs.xml") || lower.endsWith(".pcsimpl.xml") || lower.endsWith("pcs") || lower.endsWith("pcsimpl")) {
            return "pcs";
        }
        if (lower.endsWith(".pbcb.xml") || lower.endsWith(".pbcbimpl.xml") || lower.endsWith("pbcb") || lower.endsWith("pbcbimpl")) {
            return "pbcb";
        }
        if (lower.endsWith(".pbcp.xml") || lower.endsWith(".pbcpimpl.xml") || lower.endsWith("pbcp") || lower.endsWith("pbcpimpl")) {
            return "pbcp";
        }
        if (lower.endsWith(".pbcc.xml") || lower.endsWith(".pbccimpl.xml") || lower.endsWith("pbcc") || lower.endsWith("pbccimpl")) {
            return "pbcc";
        }
        if (lower.endsWith(".pbct.xml") || lower.endsWith(".pbctimpl.xml") || lower.endsWith("pbct") || lower.endsWith("pbctimpl")) {
            return "pbct";
        }
        return null;
    }

    public static final class ServiceTypeFileMeta {
        private final String typeId;
        private final String nodeKind;
        private final String domainKey;
        private final String packagePath;
        private final String definitionFilePath;
        private final String implFilePath;
        private final String module;
        private final String parentProject;

        private ServiceTypeFileMeta(String typeId,
                                    String nodeKind,
                                    String domainKey,
                                    String packagePath,
                                    String definitionFilePath,
                                    String implFilePath,
                                    String module,
                                    String parentProject) {
            this.typeId = typeId;
            this.nodeKind = nodeKind;
            this.domainKey = domainKey;
            this.packagePath = packagePath;
            this.definitionFilePath = definitionFilePath;
            this.implFilePath = implFilePath;
            this.module = module;
            this.parentProject = parentProject;
        }

        public String getTypeId() {
            return typeId;
        }

        public String getNodeKind() {
            return nodeKind;
        }

        public String getDomainKey() {
            return domainKey;
        }

        public String getPackagePath() {
            return packagePath;
        }

        public String getDefinitionFilePath() {
            return definitionFilePath;
        }

        public String getImplFilePath() {
            return implFilePath;
        }

        public String getModule() {
            return module;
        }

        public String getParentProject() {
            return parentProject;
        }

        public String preferredXmlPath() {
            return !isBlank(definitionFilePath) ? definitionFilePath : implFilePath;
        }
    }

    private static final class MutableMeta {
        private final String typeId;
        private String nodeKind;
        private String domainKey;
        private String packagePath;
        private String definitionFilePath;
        private String implFilePath;
        private String module;
        private String parentProject;
        private int definitionPriority;

        private MutableMeta(String typeId) {
            this.typeId = typeId;
        }

        private void merge(FileEnvelope envelope) {
            if (!isBlank(envelope.nodeKind)) {
                this.nodeKind = envelope.nodeKind;
            }
            if (!isBlank(envelope.domainKey)) {
                this.domainKey = envelope.domainKey;
            }
            if (!isBlank(envelope.packagePath)) {
                this.packagePath = envelope.packagePath;
            }
            if (!isBlank(envelope.module)) {
                this.module = envelope.module;
            }
            if (!isBlank(envelope.parentProject)) {
                this.parentProject = envelope.parentProject;
            }
            if (envelope.implFile) {
                if (isBlank(this.implFilePath)) {
                    this.implFilePath = envelope.filePath;
                }
                return;
            }
            if (isBlank(this.definitionFilePath) || envelope.definitionPriority >= this.definitionPriority) {
                this.definitionFilePath = envelope.filePath;
                this.definitionPriority = envelope.definitionPriority;
            }
        }

        private ServiceTypeFileMeta toImmutable() {
            return new ServiceTypeFileMeta(
                typeId,
                nodeKind,
                domainKey,
                packagePath,
                definitionFilePath,
                implFilePath,
                module,
                parentProject
            );
        }
    }

    private static final class FileEnvelope {
        private final String typeId;
        private final String packagePath;
        private final String nodeKind;
        private final String domainKey;
        private final String filePath;
        private final String module;
        private final String parentProject;
        private final int definitionPriority;
        private final boolean implFile;

        private FileEnvelope(String typeId,
                             String packagePath,
                             String nodeKind,
                             String domainKey,
                             String filePath,
                             String module,
                             String parentProject,
                             int definitionPriority,
                             boolean implFile) {
            this.typeId = typeId;
            this.packagePath = packagePath;
            this.nodeKind = nodeKind;
            this.domainKey = domainKey;
            this.filePath = filePath;
            this.module = module;
            this.parentProject = parentProject;
            this.definitionPriority = definitionPriority;
            this.implFile = implFile;
        }
    }
}
