package com.axonlink.ai.daoindex.sqlinspect.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则引擎对"SQL 谓词 vs 表的某一个索引"做出的匹配结果。
 */
public class IndexMatchResult {

    private String indexName;
    private List<String> indexColumns;
    /** 最左匹配到的列数（从 0 到 matchedColumnCount-1 连续命中）。 */
    private int matchedColumnCount;
    private int totalColumnCount;
    /** 未被谓词覆盖的索引后缀列（提示"还差哪些字段才能全覆盖"）。 */
    private List<String> unusedSuffix = new ArrayList<>();
    /**
     * ORDER BY 能否直接利用本索引顺序（免 Sort）。
     * 条件：ORDER BY 字段序列 = 索引 matched 之后紧邻的连续列。
     */
    private boolean orderByCanUseIndex;
    /** GROUP BY 能否利用本索引（类似 ORDER BY）。 */
    private boolean groupByCanUseIndex;

    public IndexMatchResult() {}

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public List<String> getIndexColumns() { return indexColumns; }
    public void setIndexColumns(List<String> indexColumns) { this.indexColumns = indexColumns; }
    public int getMatchedColumnCount() { return matchedColumnCount; }
    public void setMatchedColumnCount(int matchedColumnCount) { this.matchedColumnCount = matchedColumnCount; }
    public int getTotalColumnCount() { return totalColumnCount; }
    public void setTotalColumnCount(int totalColumnCount) { this.totalColumnCount = totalColumnCount; }
    public List<String> getUnusedSuffix() { return unusedSuffix; }
    public void setUnusedSuffix(List<String> unusedSuffix) { this.unusedSuffix = unusedSuffix; }
    public boolean isOrderByCanUseIndex() { return orderByCanUseIndex; }
    public void setOrderByCanUseIndex(boolean orderByCanUseIndex) { this.orderByCanUseIndex = orderByCanUseIndex; }
    public boolean isGroupByCanUseIndex() { return groupByCanUseIndex; }
    public void setGroupByCanUseIndex(boolean groupByCanUseIndex) { this.groupByCanUseIndex = groupByCanUseIndex; }
}
