package com.axonlink.ai.prompt;

import com.axonlink.ai.dto.CodeExplainRequest;
import com.axonlink.ai.dto.FlowtransAttributeDefinition;
import com.axonlink.ai.dto.FlowtransTagCatalogResponse;
import com.axonlink.ai.dto.FlowtransTagDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * flowtrans.xml 标签词典加载与提示词辅助服务。
 */
@Service
public class FlowtransTagCatalogService {

    private static final String RESOURCE_PATH = "ai/flowtrans-tag-catalog.json";
    private static final Pattern TAG_PATTERN = Pattern.compile("<\\s*([A-Za-z_][\\w:.-]*)");

    private final ObjectMapper objectMapper;

    private volatile FlowtransTagCatalogResponse cachedCatalog;

    public FlowtransTagCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FlowtransTagCatalogResponse getCatalog() {
        FlowtransTagCatalogResponse local = cachedCatalog;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedCatalog == null) {
                cachedCatalog = loadCatalog();
            }
            return cachedCatalog;
        }
    }

    public boolean isFlowtransXml(CodeExplainRequest request) {
        if (request == null) {
            return false;
        }
        return hasFlowtransSuffix(request.getFileName())
                || hasFlowtransSuffix(request.getFilePath())
                || containsFlowtranRoot(request.getCodeContent());
    }

    public List<FlowtransTagDefinition> resolveRelevantTags(String xmlContent) {
        Set<String> tagNames = extractTags(xmlContent);
        List<FlowtransTagDefinition> resolved = new ArrayList<>();
        for (FlowtransTagDefinition tag : getCatalog().getTags()) {
            if (tagNames.isEmpty() || tagNames.contains(tag.getName())) {
                resolved.add(tag);
            }
        }
        return resolved;
    }

    public String renderRelevantGlossary(String xmlContent) {
        List<FlowtransTagDefinition> tags = resolveRelevantTags(xmlContent);
        if (tags.isEmpty()) {
            return "暂无可用的 flowtrans.xml 标签词典。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("【flowtrans.xml 标签词典（草稿版）】\n");
        for (FlowtransTagDefinition tag : tags) {
            builder.append("- <").append(tag.getName()).append(">");
            if (notBlank(tag.getCategory())) {
                builder.append(" [").append(tag.getCategory()).append("]");
            }
            if (notBlank(tag.getMeaningDraft())) {
                builder.append("：").append(tag.getMeaningDraft());
            }
            builder.append('\n');
            if (notBlank(tag.getBusinessMeaningDraft())) {
                builder.append("  业务视角：").append(tag.getBusinessMeaningDraft()).append('\n');
            }
            if (notBlank(tag.getTechnicalMeaningDraft())) {
                builder.append("  技术视角：").append(tag.getTechnicalMeaningDraft()).append('\n');
            }
            if (!tag.getCommonAttributes().isEmpty()) {
                builder.append("  常见属性：");
                builder.append(renderAttributes(tag.getCommonAttributes()));
                builder.append('\n');
            }
            if (!tag.getOpenQuestions().isEmpty()) {
                builder.append("  待确认：").append(String.join("；", tag.getOpenQuestions())).append('\n');
            }
        }
        builder.append("说明：以上词典是当前草稿，如果 XML 片段和词典存在冲突，请以实际片段为准，并明确指出冲突点。\n");
        return builder.toString();
    }

    private FlowtransTagCatalogResponse loadCatalog() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, FlowtransTagCatalogResponse.class);
        } catch (IOException e) {
            throw new IllegalStateException("加载 flowtrans.xml 标签词典失败：" + e.getMessage(), e);
        }
    }

    private Set<String> extractTags(String xmlContent) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (!notBlank(xmlContent)) {
            return tags;
        }
        Matcher matcher = TAG_PATTERN.matcher(xmlContent);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null || raw.startsWith("?") || raw.startsWith("!")) {
                continue;
            }
            String tag = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
            tags.add(tag);
        }
        return tags;
    }

    private String renderAttributes(List<FlowtransAttributeDefinition> attributes) {
        List<String> rendered = new ArrayList<>();
        for (FlowtransAttributeDefinition attribute : attributes) {
            String name = attribute.getName() == null ? "" : attribute.getName().trim();
            String meaning = attribute.getMeaningDraft() == null ? "" : attribute.getMeaningDraft().trim();
            if (name.isEmpty()) {
                continue;
            }
            rendered.add(name + "=" + meaning);
        }
        return String.join("；", rendered);
    }

    private boolean hasFlowtransSuffix(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).endsWith(".flowtrans.xml");
    }

    private boolean containsFlowtranRoot(String value) {
        return value != null && value.contains("<flowtran");
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
