package com.axonlink.ai.daoindex.sqlinspect.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单张表从 SQL 解析出来的谓词抽取结果。
 *
 * <p>2a.1 只处理等值谓词；范围 / LIKE / 函数失效等在 2a.2 追加。
 */
public class PredicateExtract {

    /** 表名（规范化为小写、不带 schema）。 */
    private String tableName;
    /** 等值谓词字段集合：{@code WHERE a = ?} / {@code a IN (...)} / {@code a = a2} (JOIN) */
    private Set<String> equalityColumns = new LinkedHashSet<>();
    /** 2a.2 预留：范围谓词字段。 */
    private Set<String> rangeColumns = new LinkedHashSet<>();
    /** 2a.2 预留：排序字段（含方向，简化先存字段名）。 */
    private List<String> orderByColumns = new ArrayList<>();
    /** 2a.2 预留：GROUP BY 字段。 */
    private List<String> groupByColumns = new ArrayList<>();
    /** 2a.2 预留：因函数 / 表达式包裹而失效的字段（只提示，不参与匹配）。 */
    private Set<String> functionWrappedColumns = new LinkedHashSet<>();

    public PredicateExtract() {}

    public PredicateExtract(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public Set<String> getEqualityColumns() { return equalityColumns; }
    public void setEqualityColumns(Set<String> equalityColumns) { this.equalityColumns = equalityColumns; }
    public Set<String> getRangeColumns() { return rangeColumns; }
    public void setRangeColumns(Set<String> rangeColumns) { this.rangeColumns = rangeColumns; }
    public List<String> getOrderByColumns() { return orderByColumns; }
    public void setOrderByColumns(List<String> orderByColumns) { this.orderByColumns = orderByColumns; }
    public List<String> getGroupByColumns() { return groupByColumns; }
    public void setGroupByColumns(List<String> groupByColumns) { this.groupByColumns = groupByColumns; }
    public Set<String> getFunctionWrappedColumns() { return functionWrappedColumns; }
    public void setFunctionWrappedColumns(Set<String> functionWrappedColumns) { this.functionWrappedColumns = functionWrappedColumns; }
}
