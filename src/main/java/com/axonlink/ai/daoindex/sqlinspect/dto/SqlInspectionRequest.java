package com.axonlink.ai.daoindex.sqlinspect.dto;

/**
 * 单条 SQL 分析请求。
 */
public class SqlInspectionRequest {
    /** 要分析的 SQL 原文。 */
    private String sql;
    /** 目标库环境：dev/sit/uat，对应 dao-index-analysis.targets.*，留空则用 default-env。 */
    private String env;

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
}
