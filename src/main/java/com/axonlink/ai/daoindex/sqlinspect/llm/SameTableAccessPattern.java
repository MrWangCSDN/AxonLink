package com.axonlink.ai.daoindex.sqlinspect.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 同张表的访问模式汇总（喂 LLM 的"全局视角"语料）。
 */
public class SameTableAccessPattern {

    private String table;
    /** 该表涉及的 SQL 总数（在 dii_analysis_item 里统计）。 */
    private int totalSqlCount;
    /** 按评级分组统计：POOR=x, GOOD=y, EXCELLENT=z ... */
    private java.util.Map<String, Integer> ratingCounts = new java.util.LinkedHashMap<>();
    /** 按"谓词字段集"分组的访问模式（TOP 10）。 */
    private List<PredicateBucket> byPredicate = new ArrayList<>();

    public SameTableAccessPattern() {}
    public SameTableAccessPattern(String table) { this.table = table; }

    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }
    public int getTotalSqlCount() { return totalSqlCount; }
    public void setTotalSqlCount(int totalSqlCount) { this.totalSqlCount = totalSqlCount; }
    public java.util.Map<String, Integer> getRatingCounts() { return ratingCounts; }
    public void setRatingCounts(java.util.Map<String, Integer> ratingCounts) { this.ratingCounts = ratingCounts; }
    public List<PredicateBucket> getByPredicate() { return byPredicate; }
    public void setByPredicate(List<PredicateBucket> byPredicate) { this.byPredicate = byPredicate; }

    /** 一组"谓词字段"的访问模式统计。 */
    public static class PredicateBucket {
        /** WHERE 条件字段列表（按位置归一，比如 [acct_no, trans_date]）。 */
        public List<String> fields;
        /** 这种谓词组合有多少条 SQL 用到。 */
        public int sqlCount;
        /** 其中 POOR / GOOD / EXCELLENT 各多少条。 */
        public java.util.Map<String, Integer> ratingDist;

        public PredicateBucket() {}
        public PredicateBucket(List<String> fields, int sqlCount,
                               java.util.Map<String, Integer> ratingDist) {
            this.fields = fields;
            this.sqlCount = sqlCount;
            this.ratingDist = ratingDist;
        }
    }
}
