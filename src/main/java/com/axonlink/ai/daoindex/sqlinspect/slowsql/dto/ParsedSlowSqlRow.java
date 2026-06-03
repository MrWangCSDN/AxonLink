package com.axonlink.ai.daoindex.sqlinspect.slowsql.dto;

/** 慢SQL单行解析结果（一次出现）。 */
public class ParsedSlowSqlRow {
    public final String serviceName;   // 第0列原值（留底）
    public final String domain;        // 派生中文领域
    public final String bizType;       // 派生类型
    public final long timeCostMs;
    public final String timeCostRaw;
    public final String abstractSql;
    public final String abstractHash;
    public final String execParams;

    public ParsedSlowSqlRow(String serviceName, String domain, String bizType,
                            long timeCostMs, String timeCostRaw,
                            String abstractSql, String abstractHash, String execParams) {
        this.serviceName = serviceName;
        this.domain = domain;
        this.bizType = bizType;
        this.timeCostMs = timeCostMs;
        this.timeCostRaw = timeCostRaw;
        this.abstractSql = abstractSql;
        this.abstractHash = abstractHash;
        this.execParams = execParams;
    }
}
