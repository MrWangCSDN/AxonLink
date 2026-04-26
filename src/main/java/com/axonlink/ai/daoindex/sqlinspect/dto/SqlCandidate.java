package com.axonlink.ai.daoindex.sqlinspect.dto;

/**
 * 批量巡检的 SQL 候选项。
 * 由扫描器从源码 {@code @Statement(sql=...)} 里抽取。
 */
public class SqlCandidate {

    private String sql;
    private String projectName;
    private String classFqn;
    private String methodName;
    private String sourceFile;

    public SqlCandidate() {}

    public SqlCandidate(String sql, String projectName, String classFqn, String sourceFile) {
        this.sql = sql;
        this.projectName = projectName;
        this.classFqn = classFqn;
        this.sourceFile = sourceFile;
    }

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getClassFqn() { return classFqn; }
    public void setClassFqn(String classFqn) { this.classFqn = classFqn; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
}
