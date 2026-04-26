package com.axonlink.ai.daoindex.sqlinspect.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 针对单张表的评级详情。
 */
public class TableRating {

    private String table;
    private IndexRating rating;
    private String ratingLabel;   // 差/良/优（便于前端直接展示）
    private PredicateExtract predicates;
    /** 规则引擎选中的最佳索引（可能为 null，表示任何索引都没命中）。 */
    private IndexMatchResult matchedIndex;
    /** 表上全部现存索引，便于前端展示候选。 */
    private List<IndexMeta> availableIndexes = new ArrayList<>();
    /** 评级原因（人话概述，比如"命中 idx_xxx 前 1 列，后 2 列未覆盖"）。 */
    private String reason;

    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }
    public IndexRating getRating() { return rating; }
    public void setRating(IndexRating rating) {
        this.rating = rating;
        this.ratingLabel = rating == null ? null : rating.getLabel();
    }
    public String getRatingLabel() { return ratingLabel; }
    public void setRatingLabel(String ratingLabel) { this.ratingLabel = ratingLabel; }
    public PredicateExtract getPredicates() { return predicates; }
    public void setPredicates(PredicateExtract predicates) { this.predicates = predicates; }
    public IndexMatchResult getMatchedIndex() { return matchedIndex; }
    public void setMatchedIndex(IndexMatchResult matchedIndex) { this.matchedIndex = matchedIndex; }
    public List<IndexMeta> getAvailableIndexes() { return availableIndexes; }
    public void setAvailableIndexes(List<IndexMeta> availableIndexes) { this.availableIndexes = availableIndexes; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
