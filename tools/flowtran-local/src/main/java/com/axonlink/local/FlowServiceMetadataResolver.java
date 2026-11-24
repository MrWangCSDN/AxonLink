package com.axonlink.local;

import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.axonlink.local.Graph.firstNonBlank;

/** 提取自 AxonLink FlowServiceMetadataResolver，每次扫描从源码 XML 建立类型索引。 */
final class FlowServiceMetadataResolver {
    private static final String GENERIC_SUFFIX = ".servicetype.xml";
    private static final Map<String, String> KINDS_BY_SUFFIX = Map.ofEntries(
        Map.entry(GENERIC_SUFFIX, "service"),
        Map.entry(".pbs.xml", "pbs"), Map.entry(".pbsimpl.xml", "pbs"),
        Map.entry(".pcs.xml", "pcs"), Map.entry(".pcsimpl.xml", "pcs"),
        Map.entry(".pbcb.xml", "pbcb"), Map.entry(".pbcbimpl.xml", "pbcb"),
        Map.entry(".pbcp.xml", "pbcp"), Map.entry(".pbcpimpl.xml", "pbcp"),
        Map.entry(".pbcc.xml", "pbcc"), Map.entry(".pbccimpl.xml", "pbcc"),
        Map.entry(".pbct.xml", "pbct"), Map.entry(".pbctimpl.xml", "pbct")
    );

    record ServiceTypeFileMeta(String typeId, String nodeKind, String domainKey, String packagePath,
                               String preferredXmlPath) {}

    private final Map<String, ServiceTypeFileMeta> metadata = new HashMap<>();

    FlowServiceMetadataResolver(MetadataResourceScanner resources, String workspaceRoots,
                                SourceLayout layout, Graph graph, ScanProgress progress) {
        progress.begin("服务类型元数据解析");
        List<Path> files = resources.collectMetadataFiles(workspaceRoots, 8, 6,
            path -> suffix(path.getFileName().toString()) != null);
        Map<String, MutableMeta> records = new HashMap<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            progress.file(file, i + 1, files.size());
            try {
                String fileName = file.getFileName().toString();
                String suffix = suffix(fileName);
                Element root = XmlFiles.read(file).getDocumentElement();
                String typeId = firstNonBlank(attr(root, "id"), fileName.substring(0, fileName.length() - suffix.length()));
                if (typeId == null) {
                    continue;
                }
                String pkg = firstNonBlank(attr(root, "package"), attr(root, "packagePath"), attr(root, "package_name"));
                String domain = DomainKeyResolver.resolveByProjectOrPackage(layout.parentProject(file), pkg);
                int priority = suffix.endsWith("impl.xml") ? 0 : GENERIC_SUFFIX.equals(suffix) ? 1 : 2;
                records.computeIfAbsent(typeId, ignored -> new MutableMeta())
                    .merge(KINDS_BY_SUFFIX.get(suffix), domain, pkg, file.toString(), priority);
            } catch (Exception e) {
                graph.report(XmlFiles.issue(file, "SERVICE_XML", e));
            }
        }
        records.forEach((typeId, meta) -> metadata.put(typeId, new ServiceTypeFileMeta(
            typeId, meta.kind, meta.domain, meta.packagePath, firstNonBlank(meta.definitionFile, meta.implFile))));
        progress.finish("candidateFiles=%d indexedTypes=%d".formatted(files.size(), metadata.size()));
    }

    Optional<ServiceTypeFileMeta> findByTypeId(String typeId) {
        return Optional.ofNullable(metadata.get(typeId));
    }

    private static String suffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return KINDS_BY_SUFFIX.keySet().stream().filter(lower::endsWith).findFirst().orElse(null);
    }

    private static String attr(Element root, String name) {
        return root == null ? null : firstNonBlank(root.getAttribute(name).trim());
    }

    private static final class MutableMeta {
        private String kind;
        private String domain;
        private String packagePath;
        private String definitionFile;
        private String implFile;
        private int definitionPriority;

        private void merge(String kind, String domain, String packagePath, String file, int priority) {
            this.kind = firstNonBlank(kind, this.kind);
            this.domain = firstNonBlank(domain, this.domain);
            this.packagePath = firstNonBlank(packagePath, this.packagePath);
            if (priority == 0) {
                this.implFile = firstNonBlank(this.implFile, file);
            } else if (priority >= this.definitionPriority) {
                this.definitionFile = file;
                this.definitionPriority = priority;
            }
        }
    }
}
