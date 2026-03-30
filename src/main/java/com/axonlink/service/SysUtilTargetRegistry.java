package com.axonlink.service;

import com.axonlink.dto.NodeCacheEntry;
import com.axonlink.service.FlowServiceMetadataResolver.ServiceTypeFileMeta;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * SysUtil.getInstance(...) 目标白名单。
 *
 * <p>仅允许采集正式服务/构件节点：
 * pbs / pcs / pbcb / pbcp / pbcc / pbct。
 *
 * <p>数据来源：
 * <ul>
 *   <li>ServiceNodeCache：构件/服务主数据</li>
 *   <li>FlowServiceMetadataResolver：pbs/pcs XML 元数据</li>
 * </ul>
 */
@Component
public class SysUtilTargetRegistry {

    private static final Set<String> SUPPORTED_NODE_KINDS = Set.of(
        "pbs", "pcs", "pbcb", "pbcp", "pbcc", "pbct"
    );

    private final ServiceNodeCache serviceNodeCache;
    private final FlowServiceMetadataResolver flowServiceMetadataResolver;

    public SysUtilTargetRegistry(ServiceNodeCache serviceNodeCache,
                                 FlowServiceMetadataResolver flowServiceMetadataResolver) {
        this.serviceNodeCache = serviceNodeCache;
        this.flowServiceMetadataResolver = flowServiceMetadataResolver;
    }

    public boolean containsTypeId(String typeId) {
        return findByTypeId(typeId).isPresent();
    }

    public void warmUp() {
        flowServiceMetadataResolver.warmUp();
    }

    public Optional<TargetMeta> findByTypeId(String rawTypeId) {
        for (String candidate : normalizeCandidates(rawTypeId)) {
            Optional<TargetMeta> meta = resolveCandidate(candidate);
            if (meta.isPresent()) {
                return meta;
            }
        }
        return Optional.empty();
    }

    private Optional<TargetMeta> resolveCandidate(String typeId) {
        Optional<NodeCacheEntry> cacheEntry = serviceNodeCache.findByTypeId(typeId);
        Optional<ServiceTypeFileMeta> fileMeta = flowServiceMetadataResolver.findByTypeId(typeId);

        String nodeKind = firstNonBlank(
            fileMeta.map(ServiceTypeFileMeta::getNodeKind).orElse(null),
            cacheEntry.map(NodeCacheEntry::getNodeKind).orElse(null)
        );
        if (isBlank(nodeKind) || !SUPPORTED_NODE_KINDS.contains(nodeKind)) {
            return Optional.empty();
        }

        return Optional.of(new TargetMeta(
            typeId,
            nodeKind,
            firstNonBlank(
                fileMeta.map(ServiceTypeFileMeta::getDomainKey).orElse(null),
                cacheEntry.map(NodeCacheEntry::getDomainKey).orElse(null)
            ),
            firstNonBlank(
                cacheEntry.map(NodeCacheEntry::getServiceName).orElse(null),
                typeId
            ),
            firstNonBlank(
                cacheEntry.map(NodeCacheEntry::getServiceLongname).orElse(null),
                typeId
            ),
            firstNonBlank(
                fileMeta.map(ServiceTypeFileMeta::getPackagePath).orElse(null),
                cacheEntry.map(NodeCacheEntry::getPackagePath).orElse(null)
            ),
            fileMeta.map(ServiceTypeFileMeta::getDefinitionFilePath).orElse(null),
            fileMeta.map(ServiceTypeFileMeta::getImplFilePath).orElse(null)
        ));
    }

    private Set<String> normalizeCandidates(String rawTypeId) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String normalized = normalize(rawTypeId);
        if (!isBlank(normalized)) {
            candidates.add(normalized);
            int lastDot = normalized.lastIndexOf('.');
            if (lastDot > 0 && lastDot + 1 < normalized.length()) {
                candidates.add(normalized.substring(lastDot + 1));
            }
        }
        return candidates;
    }

    private String normalize(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.replaceAll("<.*>", "").replaceAll("\\[]", "").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class TargetMeta {
        private final String typeId;
        private final String nodeKind;
        private final String domainKey;
        private final String serviceName;
        private final String serviceLongname;
        private final String packagePath;
        private final String definitionFilePath;
        private final String implFilePath;

        private TargetMeta(String typeId,
                           String nodeKind,
                           String domainKey,
                           String serviceName,
                           String serviceLongname,
                           String packagePath,
                           String definitionFilePath,
                           String implFilePath) {
            this.typeId = typeId;
            this.nodeKind = nodeKind;
            this.domainKey = domainKey;
            this.serviceName = serviceName;
            this.serviceLongname = serviceLongname;
            this.packagePath = packagePath;
            this.definitionFilePath = definitionFilePath;
            this.implFilePath = implFilePath;
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

        public String getServiceName() {
            return serviceName;
        }

        public String getServiceLongname() {
            return serviceLongname;
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
    }
}
