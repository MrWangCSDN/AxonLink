package com.axonlink.ai.daoindex.sqlinspect.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条 SQL 分析结果（2a.1：规则引擎判定的索引命中评级，暂不含 LLM 解释和 EXPLAIN）。
 */
public class SqlInspectionResult {

    /** 分析的 SQL 规范化文本。 */
    private String sql;
    /** 目标库环境。 */
    private String env;
    /** SQL 内容 SHA-256（规范化后计算）。 */
    private String sqlHash;
    /** 整体评级（多表场景取最差档）。 */
    private IndexRating overallRating;
    private String overallRatingLabel;
    /** 每张表的评级详情。 */
    private List<TableRating> tableRatings = new ArrayList<>();
    /** 规则引擎的处理警告（不可解析字段、未识别谓词等）。 */
    private List<String> warnings = new ArrayList<>();
    /** 规则引擎处理耗时 ms。 */
    private long ruleEngineElapsedMs;
    /** 本次新落库产生的 itemId（无新记录则为 0）。 */
    private long itemId;
    /** 命中 5 分钟幂等窗口时，复用的旧 itemId（未命中则为 null）。 */
    private Long reusedItemId;

    // ── EXPLAIN 派生（V8 新增）──
    /** EXPLAIN 真实计划派生的运行时评级（POOR/GOOD/EXCELLENT/null=未跑/失败）。 */
    private IndexRating runtimeRating;
    private String runtimeRatingLabel;
    /** 规则评级与 runtime 评级是否分歧。 */
    private boolean disagreement;
    /** 分歧原因人话说明，便于审计。 */
    private String disagreementReason;
    /** EXPLAIN 原始 JSON 文本（落库到 explain_plan 字段）。 */
    private String explainPlanJson;
    /** EXPLAIN 顶层 cost。 */
    private Double explainTopCost;
    /** EXPLAIN 估算行数。 */
    private Long explainEstRows;
    /** EXPLAIN 是否含 Seq Scan。 */
    private Boolean explainHasSeqScan;
    /** EXPLAIN 执行耗时 ms。 */
    private Long explainElapsedMs;
    /** EXPLAIN 失败原因。 */
    private String explainError;

    // ── 表元数据（V8 新增，喂 LLM 用）──
    /** 涉及表的统计信息 JSON（行数、大小等）。 */
    private String tableStatsJson;
    /** 涉及列的统计信息 JSON（基数、NULL 比例、倾斜度）。 */
    private String columnStatsJson;
    /** 涉及表的字段定义 + 业务注释 JSON。 */
    private String tableDdlJson;

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getSqlHash() { return sqlHash; }
    public void setSqlHash(String sqlHash) { this.sqlHash = sqlHash; }
    public IndexRating getOverallRating() { return overallRating; }
    public void setOverallRating(IndexRating overallRating) {
        this.overallRating = overallRating;
        this.overallRatingLabel = overallRating == null ? null : overallRating.getLabel();
    }
    public String getOverallRatingLabel() { return overallRatingLabel; }
    public void setOverallRatingLabel(String overallRatingLabel) { this.overallRatingLabel = overallRatingLabel; }
    public List<TableRating> getTableRatings() { return tableRatings; }
    public void setTableRatings(List<TableRating> tableRatings) { this.tableRatings = tableRatings; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
    public long getRuleEngineElapsedMs() { return ruleEngineElapsedMs; }
    public void setRuleEngineElapsedMs(long ruleEngineElapsedMs) { this.ruleEngineElapsedMs = ruleEngineElapsedMs; }
    public long getItemId() { return itemId; }
    public void setItemId(long itemId) { this.itemId = itemId; }
    public Long getReusedItemId() { return reusedItemId; }
    public void setReusedItemId(Long reusedItemId) { this.reusedItemId = reusedItemId; }

    public IndexRating getRuntimeRating() { return runtimeRating; }
    public void setRuntimeRating(IndexRating runtimeRating) {
        this.runtimeRating = runtimeRating;
        this.runtimeRatingLabel = runtimeRating == null ? null : runtimeRating.getLabel();
    }
    public String getRuntimeRatingLabel() { return runtimeRatingLabel; }
    public void setRuntimeRatingLabel(String runtimeRatingLabel) { this.runtimeRatingLabel = runtimeRatingLabel; }
    public boolean isDisagreement() { return disagreement; }
    public void setDisagreement(boolean disagreement) { this.disagreement = disagreement; }
    public String getDisagreementReason() { return disagreementReason; }
    public void setDisagreementReason(String disagreementReason) { this.disagreementReason = disagreementReason; }
    public String getExplainPlanJson() { return explainPlanJson; }
    public void setExplainPlanJson(String explainPlanJson) { this.explainPlanJson = explainPlanJson; }
    public Double getExplainTopCost() { return explainTopCost; }
    public void setExplainTopCost(Double explainTopCost) { this.explainTopCost = explainTopCost; }
    public Long getExplainEstRows() { return explainEstRows; }
    public void setExplainEstRows(Long explainEstRows) { this.explainEstRows = explainEstRows; }
    public Boolean getExplainHasSeqScan() { return explainHasSeqScan; }
    public void setExplainHasSeqScan(Boolean explainHasSeqScan) { this.explainHasSeqScan = explainHasSeqScan; }
    public Long getExplainElapsedMs() { return explainElapsedMs; }
    public void setExplainElapsedMs(Long explainElapsedMs) { this.explainElapsedMs = explainElapsedMs; }
    public String getExplainError() { return explainError; }
    public void setExplainError(String explainError) { this.explainError = explainError; }
    public String getTableStatsJson() { return tableStatsJson; }
    public void setTableStatsJson(String tableStatsJson) { this.tableStatsJson = tableStatsJson; }
    public String getColumnStatsJson() { return columnStatsJson; }
    public void setColumnStatsJson(String columnStatsJson) { this.columnStatsJson = columnStatsJson; }
    public String getTableDdlJson() { return tableDdlJson; }
    public void setTableDdlJson(String tableDdlJson) { this.tableDdlJson = tableDdlJson; }
}
