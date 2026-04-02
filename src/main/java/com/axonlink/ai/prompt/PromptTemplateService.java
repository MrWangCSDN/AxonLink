package com.axonlink.ai.prompt;

import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.AnalysisMode;
import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.RuleFinding;
import com.axonlink.config.AiAnalysisConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板管理。
 */
@Service
public class PromptTemplateService {

    private final AiAnalysisConfig config;

    public PromptTemplateService(AiAnalysisConfig config) {
        this.config = config;
    }

    public AnalysisPrompt buildPrompt(AnalysisContext context, List<RuleFinding> findings) {
        AnalysisPrompt prompt = new AnalysisPrompt();
        prompt.setPromptVersion(resolvePromptVersion(context.getMode()));
        prompt.setSystemPrompt(systemPrompt(context.getMode()));
        prompt.setUserPrompt(userPrompt(context, findings));
        return prompt;
    }

    private String resolvePromptVersion(AnalysisMode mode) {
        return mode == AnalysisMode.BUSINESS
                ? config.getBusinessPromptVersion()
                : config.getTechnicalPromptVersion();
    }

    private String systemPrompt(AnalysisMode mode) {
        return switch (mode) {
            case BUSINESS -> "你是交易链路业务解读助手，必须站在业务视角解释交易目的、关键服务职责和数据含义，并且最终回答必须全程使用简体中文。";
            case TECHNICAL -> "你是交易链路技术检查助手，必须结合调用链、代码片段和规则证据输出技术风险判断，并且最终回答必须全程使用简体中文。";
            case FULL -> "你是交易链路综合分析助手，需要同时输出业务解读和技术检查，并清楚区分两者；最终回答必须全程使用简体中文。";
        };
    }

    private String userPrompt(AnalysisContext context, List<RuleFinding> findings) {
        Map<String, Object> metadata = context.getMetadata();
        return """
                请严格按以下 Markdown 结构输出：
                ## 总览
                ## 业务解读
                ## 技术检查
                ## 建议

                特别要求：最终回答必须全程使用简体中文。

                请基于以下交易链路上下文做分析。

                交易号: %s
                分析模式: %s
                聚焦点: %s
                服务数量: %s
                构件数量: %s
                表数量: %s
                已抽取代码片段数: %s
                规则命中数: %s

                请输出结构化结论，并明确区分业务解读与技术检查。
                """.formatted(
                context.getTxId(),
                context.getMode().name(),
                context.getFocus(),
                metadata.getOrDefault("serviceCount", 0),
                metadata.getOrDefault("componentCount", 0),
                metadata.getOrDefault("tableCount", 0),
                context.getCodeSnippets().size(),
                findings.size());
    }
}
