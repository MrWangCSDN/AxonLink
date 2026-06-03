package com.axonlink.ai.daoindex.sqlinspect.slowsql.dto;

/** 慢SQL单行解析结果（一次出现）。 */
public class ParsedSlowSqlRow {
    public final String domain;
    public final long timeCostMs;
    public final String timeCostRaw;
    public final String abstractSql;
    public final String abstractHash;
    public final String execParams;

    public ParsedSlowSqlRow(String domain, long timeCostMs, String timeCostRaw,
                            String abstractSql, String abstractHash, String execParams) {
        this.domain = domain;
        this.timeCostMs = timeCostMs;
        this.timeCostRaw = timeCostRaw;
        this.abstractSql = abstractSql;
        this.abstractHash = abstractHash;
        this.execParams = execParams;
    }
}
