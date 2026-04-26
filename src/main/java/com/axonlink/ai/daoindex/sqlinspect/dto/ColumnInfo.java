package com.axonlink.ai.daoindex.sqlinspect.dto;

/**
 * 表的一个字段定义。来源：{@code information_schema.columns + pg_description}。
 *
 * <p>同时承载"DDL 形态信息"和"数据分布统计"，方便 LLM 一次性看全。
 */
public class ColumnInfo {

    private String name;
    /** 完整数据类型，如 {@code varchar(20)}、{@code numeric(15,2)}、{@code timestamp(0)}。 */
    private String dataType;
    /** 是否可空。 */
    private boolean nullable;
    /** 默认值（数据库存的字面量，可能是函数）。 */
    private String defaultValue;
    /** 字段在表中的序号（1-based）。 */
    private int ordinalPosition;
    /** 业务注释（中文），来自 {@code pg_description}。 */
    private String comment;

    // ── pg_stats 派生：不为空时表示数据分布统计可用 ──
    /** 不同值个数；负数为比例（如 -0.1 表示 10% 行有不同值）。 */
    private Double distinctCount;
    /** NULL 占比，0.0 ~ 1.0。 */
    private Double nullFraction;
    /**
     * 数据倾斜程度判定：HIGH（最常见值占比 > 50%）/ MEDIUM（> 20%）/ LOW。
     * 给 LLM 看的人话标签，方便它写"该字段不适合做索引最左列"之类的建议。
     */
    private String skewLevel;

    public ColumnInfo() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public int getOrdinalPosition() { return ordinalPosition; }
    public void setOrdinalPosition(int ordinalPosition) { this.ordinalPosition = ordinalPosition; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Double getDistinctCount() { return distinctCount; }
    public void setDistinctCount(Double distinctCount) { this.distinctCount = distinctCount; }
    public Double getNullFraction() { return nullFraction; }
    public void setNullFraction(Double nullFraction) { this.nullFraction = nullFraction; }
    public String getSkewLevel() { return skewLevel; }
    public void setSkewLevel(String skewLevel) { this.skewLevel = skewLevel; }
}
