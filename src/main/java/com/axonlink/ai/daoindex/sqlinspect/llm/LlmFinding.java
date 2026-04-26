package com.axonlink.ai.daoindex.sqlinspect.llm;

/**
 * LLM 发现的一个问题。
 *
 * <p>结构化字段对前端/后端渲染最友好，不是自然语言。
 */
public class LlmFinding {

    /**
     * 发现类型。标准常量：
     * <ul>
     *   <li>{@code INDEX_NOT_FULLY_COVERED} — 索引未覆盖所有谓词字段</li>
     *   <li>{@code POTENTIAL_INDEX_MERGE} — 多个索引可以合并</li>
     *   <li>{@code REDUNDANT_INDEX} — 索引完全冗余</li>
     *   <li>{@code OVERSIZED_INDEX_KEY} — 索引键长 > 200</li>
     *   <li>{@code INDEX_COUNT_WARNING} — 索引数量过多</li>
     *   <li>{@code MISSING_HOT_PATH} — 热点访问路径未建索引</li>
     *   <li>{@code IMPLICIT_CAST} — 隐式类型转换导致索引失效</li>
     *   <li>{@code LOW_SELECTIVITY} — 选择性差的字段参与索引</li>
     *   <li>{@code OTHER} — 其他</li>
     * </ul>
     */
    private String type;
    /** HIGH / MEDIUM / LOW */
    private String severity;
    /** 人话描述（30~80 字）。 */
    private String description;
    /** 证据引用（指向语料里具体字段，如 "matchedIndex.matchedColumnCount=1/4"）。 */
    private String evidence;

    public LlmFinding() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
}
