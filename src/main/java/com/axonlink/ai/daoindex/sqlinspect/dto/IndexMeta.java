package com.axonlink.ai.daoindex.sqlinspect.dto;

import java.util.List;

/**
 * 目标库单张表的一个索引的元数据。
 *
 * <p>数据来源：{@code pg_index + pg_attribute + pg_class + pg_namespace} 联合查询。
 * 仅包含规则引擎做最左匹配所需的字段。
 */
public class IndexMeta {

    /** 索引所在 schema。 */
    private String schemaName;
    /** 索引所在表名（不带 schema）。 */
    private String tableName;
    /** 索引名。 */
    private String indexName;
    /** 按位置顺序的列名列表（最左在 0 位）。 */
    private List<String> columns;
    /** 是否唯一索引。 */
    private boolean unique;
    /** 是否主键索引。 */
    private boolean primary;
    /** 索引类型：btree / hash / gin / ... */
    private String indexType;

    public IndexMeta() {}

    public IndexMeta(String schemaName, String tableName, String indexName, List<String> columns,
                     boolean unique, boolean primary, String indexType) {
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.indexName = indexName;
        this.columns = columns;
        this.unique = unique;
        this.primary = primary;
        this.indexType = indexType;
    }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public boolean isUnique() { return unique; }
    public void setUnique(boolean unique) { this.unique = unique; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public String getIndexType() { return indexType; }
    public void setIndexType(String indexType) { this.indexType = indexType; }
}
