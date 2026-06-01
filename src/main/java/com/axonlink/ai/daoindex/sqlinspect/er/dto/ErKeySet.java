package com.axonlink.ai.daoindex.sqlinspect.er.dto;

import java.util.List;

/**
 * 一张表的一个键集合（主键 或 某个唯一索引）。
 *
 * <p>ER 推断的「源」：表 A 的某个 ErKeySet 的全部列若出现在表 B 中，则推断 A←B 关系。
 * <p>{@code columns} 已归一为小写、按索引列序排列（推断只用集合语义，顺序仅供展示）。
 */
public class ErKeySet {

    /** 键类型：{@code PK} / {@code UNIQUE}。 */
    private String keyType;
    /** 键列（小写，按列序）。 */
    private List<String> columns;

    public ErKeySet() {}

    public ErKeySet(String keyType, List<String> columns) {
        this.keyType = keyType;
        this.columns = columns;
    }

    public String getKeyType() { return keyType; }
    public void setKeyType(String keyType) { this.keyType = keyType; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
}
