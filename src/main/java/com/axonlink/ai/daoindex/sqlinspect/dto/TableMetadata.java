package com.axonlink.ai.daoindex.sqlinspect.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 单张表的完整元数据：DDL（字段 + 注释）+ 统计信息（行数 + 大小 + 列分布）。
 *
 * <p>这是给 LLM 的"语料"主体。LLM 拿到后能：
 * <ul>
 *   <li>判定隐式类型转换（参数类型 vs 字段类型）</li>
 *   <li>基于业务注释理解字段语义</li>
 *   <li>基于行数判断 Seq Scan 是否合理</li>
 *   <li>基于字段基数推荐索引列顺序</li>
 * </ul>
 */
public class TableMetadata {

    /** Schema 名（如 {@code ccbs_uat_db}）。 */
    private String schemaName;
    /** 表名（不带 schema 前缀）。 */
    private String tableName;
    /** 表注释（中文业务含义）。 */
    private String tableComment;

    // ── 统计 ──
    /** 实际行数（pg_stat_user_tables.n_live_tup）。 */
    private Long liveTuples;
    /** 表占用空间字节（含索引；纯表用 pg_relation_size）。 */
    private Long totalSizeBytes;
    /** 数据量等级：SMALL（<1万）/ MEDIUM（<100万）/ LARGE（>=100万）。 */
    private String sizeBucket;
    /** 最近一次 ANALYZE 时间（pg_stat_user_tables.last_analyze），帮 LLM 判断统计信息新鲜度。 */
    private String lastAnalyzeTime;

    // ── 字段定义 ──
    private List<ColumnInfo> columns = new ArrayList<>();

    /** 收集时遇到的警告（如 pg_stats 无数据），不影响主流程。 */
    private List<String> warnings = new ArrayList<>();

    public TableMetadata() {}

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getTableComment() { return tableComment; }
    public void setTableComment(String tableComment) { this.tableComment = tableComment; }
    public Long getLiveTuples() { return liveTuples; }
    public void setLiveTuples(Long liveTuples) { this.liveTuples = liveTuples; }
    public Long getTotalSizeBytes() { return totalSizeBytes; }
    public void setTotalSizeBytes(Long totalSizeBytes) { this.totalSizeBytes = totalSizeBytes; }
    public String getSizeBucket() { return sizeBucket; }
    public void setSizeBucket(String sizeBucket) { this.sizeBucket = sizeBucket; }
    public String getLastAnalyzeTime() { return lastAnalyzeTime; }
    public void setLastAnalyzeTime(String lastAnalyzeTime) { this.lastAnalyzeTime = lastAnalyzeTime; }
    public List<ColumnInfo> getColumns() { return columns; }
    public void setColumns(List<ColumnInfo> columns) { this.columns = columns; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
