package com.axonlink.ai.daoindex.sqlinspect.er.dto;

import java.util.List;

/**
 * 一条推断出的表关系（隐式外键）。
 *
 * <p>语义：{@code toTable} 通过 {@code joinColumns}（= {@code fromTable} 的某个键列集）
 * 引用 {@code fromTable}。即 {@code fromTable} 是被引用的「1」端，{@code toTable} 是「N」端。
 */
public class ErRelation {

    /** 被引用表（键拥有者，1 端）。 */
    private String fromTable;
    /** 引用表（N 端）。 */
    private String toTable;
    /** 关联列（小写，= fromTable 的键列）。 */
    private List<String> joinColumns;
    /** 键类型：PK / UNIQUE。 */
    private String keyType;
    /** 键列数：1=单列，≥2=联合。 */
    private int keyColCount;
    /** 置信度：HIGH / MEDIUM / LOW。 */
    private String confidence;

    public ErRelation() {}

    public ErRelation(String fromTable, String toTable, List<String> joinColumns,
                      String keyType, int keyColCount, String confidence) {
        this.fromTable = fromTable;
        this.toTable = toTable;
        this.joinColumns = joinColumns;
        this.keyType = keyType;
        this.keyColCount = keyColCount;
        this.confidence = confidence;
    }

    /** join 列拼成逗号分隔字符串（落库 join_columns 列用）。 */
    public String joinColumnsCsv() {
        return joinColumns == null ? "" : String.join(",", joinColumns);
    }

    public String getFromTable() { return fromTable; }
    public void setFromTable(String fromTable) { this.fromTable = fromTable; }
    public String getToTable() { return toTable; }
    public void setToTable(String toTable) { this.toTable = toTable; }
    public List<String> getJoinColumns() { return joinColumns; }
    public void setJoinColumns(List<String> joinColumns) { this.joinColumns = joinColumns; }
    public String getKeyType() { return keyType; }
    public void setKeyType(String keyType) { this.keyType = keyType; }
    public int getKeyColCount() { return keyColCount; }
    public void setKeyColCount(int keyColCount) { this.keyColCount = keyColCount; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
}
