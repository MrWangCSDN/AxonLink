package com.axonlink.ai.daoindex.sqlinspect.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条 SQL 的 LLM 分析结果，对应落库 dii_analysis_item.llm_* 所有字段。
 */
public class SqlLlmResult {

    /** 一句话总结（展示在 item 列表）。 */
    private String summary;
    /** 发现的问题列表。 */
    private List<LlmFinding> findings = new ArrayList<>();
    /** 建议列表（含 TABLE 级 DDL + SQL 级改写）。 */
    private List<LlmSuggestion> suggestions = new ArrayList<>();
    /** HIGH / MEDIUM / LOW */
    private String confidence;

    /** 本次调用用的 prompt 模板版本号。 */
    private String promptVersion;
    /** 本次调用用的 LLM 模型名。 */
    private String model;
    /** LLM 调用耗时 ms。 */
    private Long elapsedMs;
    /** 失败原因（成功时 null）。 */
    private String error;

    public SqlLlmResult() {}

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<LlmFinding> getFindings() { return findings; }
    public void setFindings(List<LlmFinding> findings) { this.findings = findings; }
    public List<LlmSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<LlmSuggestion> suggestions) { this.suggestions = suggestions; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
