package com.axonlink.ai.daoindex.sqlinspect.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * EXPLAIN (GENERIC_PLAN, FORMAT JSON) 解析后的精简视图。
 *
 * <p>同时保留原始 JSON（{@link #rawPlan}）和派生指标，方便：
 * <ul>
 *   <li>LLM 拿原始 JSON 做解释</li>
 *   <li>规则代码用 {@link #hasSeqScan}/{@link #topCost} 等做派生评级</li>
 * </ul>
 */
public class ExplainResult {

    /** EXPLAIN 是否成功跑出来；false 时下面字段都是默认值，{@link #errorMessage} 含失败原因。 */
    private boolean success;
    /** 失败原因；成功时为 null。 */
    private String errorMessage;

    /** 顶层 Plan 节点的 Total Cost（GaussDB cost 估算单位）。 */
    private double topCost;
    /** 顶层 Plan Rows（估算返回行数）。 */
    private long topPlanRows;
    /** 顶层 Plan Width（估算行宽 bytes）。 */
    private int topPlanWidth;

    /** 计划里出现的所有 Node Type，去重，按出现顺序。 */
    private List<String> nodeTypes = new ArrayList<>();
    /** 是否含 Seq Scan 节点（含 Parallel Seq Scan / Bitmap Heap Scan 等同义判断）。 */
    private boolean hasSeqScan;
    /** 是否含 Index Scan / Index Only Scan。 */
    private boolean hasIndexScan;
    /** 是否含 Sort 节点（说明排序未走索引顺序）。 */
    private boolean hasSort;
    /** 是否含 Hash Join / Merge Join / Nested Loop（任意一种）。 */
    private boolean hasJoin;
    /**
     * 顶层是否含 {@code One-Time Filter: "false"}。
     *
     * <p>这个标记出现时说明优化器认为整条 SQL 的 WHERE 为恒假（如 {@code WHERE x = NULL}），
     * 查询会被直接短路返回 0 行，里层的 Seq Scan / Index Scan 不会真实执行。
     * 这种计划 <b>不代表真实运行表现</b>，是 runtime_rating 派生时必须排除的假阳性。
     *
     * <p>常见触发场景：带 {@code ?} 或 {@code $n} 参数的 SQL 在非 PREPARE 上下文下被 EXPLAIN，
     * 参数被当作 NULL 字面量处理。
     */
    private boolean oneTimeFilterFalse;

    /** EXPLAIN 中出现的全部表名（节点 Relation Name 字段）。 */
    private List<String> scannedTables = new ArrayList<>();
    /** EXPLAIN 中实际被使用的索引名（Index Name 字段）。 */
    private List<String> usedIndexes = new ArrayList<>();

    /** EXPLAIN 执行耗时 ms（含网络往返）。 */
    private long elapsedMs;

    /** 原始 JSON 输出（落库到 explain_plan 字段，喂 LLM 时用）。 */
    private JsonNode rawPlan;

    public ExplainResult() {}

    public static ExplainResult failed(String errorMessage, long elapsedMs) {
        ExplainResult r = new ExplainResult();
        r.success = false;
        r.errorMessage = errorMessage;
        r.elapsedMs = elapsedMs;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public double getTopCost() { return topCost; }
    public void setTopCost(double topCost) { this.topCost = topCost; }
    public long getTopPlanRows() { return topPlanRows; }
    public void setTopPlanRows(long topPlanRows) { this.topPlanRows = topPlanRows; }
    public int getTopPlanWidth() { return topPlanWidth; }
    public void setTopPlanWidth(int topPlanWidth) { this.topPlanWidth = topPlanWidth; }
    public List<String> getNodeTypes() { return nodeTypes; }
    public void setNodeTypes(List<String> nodeTypes) { this.nodeTypes = nodeTypes; }
    public boolean isHasSeqScan() { return hasSeqScan; }
    public void setHasSeqScan(boolean hasSeqScan) { this.hasSeqScan = hasSeqScan; }
    public boolean isHasIndexScan() { return hasIndexScan; }
    public void setHasIndexScan(boolean hasIndexScan) { this.hasIndexScan = hasIndexScan; }
    public boolean isHasSort() { return hasSort; }
    public void setHasSort(boolean hasSort) { this.hasSort = hasSort; }
    public boolean isHasJoin() { return hasJoin; }
    public void setHasJoin(boolean hasJoin) { this.hasJoin = hasJoin; }
    public boolean isOneTimeFilterFalse() { return oneTimeFilterFalse; }
    public void setOneTimeFilterFalse(boolean oneTimeFilterFalse) { this.oneTimeFilterFalse = oneTimeFilterFalse; }
    public List<String> getScannedTables() { return scannedTables; }
    public void setScannedTables(List<String> scannedTables) { this.scannedTables = scannedTables; }
    public List<String> getUsedIndexes() { return usedIndexes; }
    public void setUsedIndexes(List<String> usedIndexes) { this.usedIndexes = usedIndexes; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public JsonNode getRawPlan() { return rawPlan; }
    public void setRawPlan(JsonNode rawPlan) { this.rawPlan = rawPlan; }
}
