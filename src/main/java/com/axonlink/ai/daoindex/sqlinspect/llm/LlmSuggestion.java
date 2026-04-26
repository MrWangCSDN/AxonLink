package com.axonlink.ai.daoindex.sqlinspect.llm;

/**
 * LLM 给出的一条建议。
 *
 * <p>按 {@link #scope} 分两档：
 * <ul>
 *   <li>{@code TABLE} — 表维度的 DDL 操作（CREATE_INDEX / DROP_INDEX / MERGE_INDEX）。
 *       同张表上的其他 SQL 可能会共享此建议；DBA 执行一次 DDL 会影响多条 SQL。</li>
 *   <li>{@code SQL} — 仅本 SQL 相关的修复（REWRITE_SQL / NO_ACTION / CODE_LEVEL）。</li>
 * </ul>
 */
public class LlmSuggestion {

    /** TABLE / SQL */
    private String scope;
    /**
     * 建议类型常量：
     * <ul>
     *   <li>{@code CREATE_INDEX} — 表级</li>
     *   <li>{@code DROP_INDEX} — 表级（删冗余）</li>
     *   <li>{@code MERGE_INDEX} — 表级（合并多个索引）</li>
     *   <li>{@code ALTER_INDEX_FIELD_ORDER} — 表级（调整列序）</li>
     *   <li>{@code REWRITE_SQL} — SQL 级</li>
     *   <li>{@code FIX_IMPLICIT_CAST} — SQL 或 DAO 代码级</li>
     *   <li>{@code CODE_LEVEL} — 调用方代码修改建议</li>
     *   <li>{@code NO_ACTION} — 无需处理</li>
     * </ul>
     */
    private String type;
    /** 建议优先级，1=最高。一般按 priority 升序执行。 */
    private Integer priority;

    // ── 表级 DDL 字段 ──
    /** {@code CREATE INDEX ...} / {@code DROP INDEX ...} / {@code ALTER ...} 等完整 DDL。 */
    private String ddl;
    /** DDL 预估索引键长度（由程序兜底校验 LLM 的建议是否超 200 限额）。 */
    private Integer estimatedKeyLength;
    /** 是否超 200 限额（由 {@link IndexSizeEstimator} 校验）。 */
    private Boolean exceedsLengthLimit;

    // ── SQL 级字段 ──
    /** 改写后的 SQL。 */
    private String newSql;

    // ── 通用 ──
    /** 人话理由。 */
    private String reason;
    /** 风险告警（系统自动追加，如"此索引键长已超限，不建议执行"）。 */
    private String riskWarning;

    public LlmSuggestion() {}

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public String getDdl() { return ddl; }
    public void setDdl(String ddl) { this.ddl = ddl; }
    public Integer getEstimatedKeyLength() { return estimatedKeyLength; }
    public void setEstimatedKeyLength(Integer estimatedKeyLength) { this.estimatedKeyLength = estimatedKeyLength; }
    public Boolean getExceedsLengthLimit() { return exceedsLengthLimit; }
    public void setExceedsLengthLimit(Boolean exceedsLengthLimit) { this.exceedsLengthLimit = exceedsLengthLimit; }
    public String getNewSql() { return newSql; }
    public void setNewSql(String newSql) { this.newSql = newSql; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRiskWarning() { return riskWarning; }
    public void setRiskWarning(String riskWarning) { this.riskWarning = riskWarning; }
}
