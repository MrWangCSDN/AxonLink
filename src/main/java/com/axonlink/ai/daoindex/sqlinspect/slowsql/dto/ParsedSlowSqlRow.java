package com.axonlink.ai.daoindex.sqlinspect.slowsql.dto;

/**
 * 慢SQL v2 聚合行：同一 (serviceName, abstractHash) 在一次导入文件内的汇总。
 *
 * <p>导入解析阶段在内存 Map 里按键聚合，累计每行提供的执行次数。
 * 若耗时更大则用该行的耗时、参数和来源替换代表字段。
 * 领域（domain）与重复轮次（repeatRounds）在解析完成后由 Service 统一回填。
 */
public class ParsedSlowSqlRow {

    public final String serviceName;   // A列 微服务
    public final String abstractSql;   // B列 抽象SQL
    public final String abstractHash;  // SHA-256(trim(abstractSql))
    public final String bizType;       // serviceName 后缀派生：联机/批量/热点账户/其他

    public String domain = "其他";      // E列解析领域（Service 回填）
    public long maxTimeCostMs;          // 组内最大耗时
    public String maxTimeCostRaw;       // 最大耗时原文
    public String execParams;           // 最大耗时那行的 C列
    public String sourceLocation;       // 最大耗时那行的 E列
    public int execCount;               // 本轮出现次数
    public String repeatRounds;         // 历史出现轮次清单（逗号升序；Service 回填）

    public ParsedSlowSqlRow(String serviceName, String bizType,
                            String abstractSql, String abstractHash,
                            long timeCostMs, String timeCostRaw,
                            String execParams, String sourceLocation) {
        this.serviceName = serviceName;
        this.bizType = bizType;
        this.abstractSql = abstractSql;
        this.abstractHash = abstractHash;
        this.maxTimeCostMs = timeCostMs;
        this.maxTimeCostRaw = timeCostRaw;
        this.execParams = execParams;
        this.sourceLocation = sourceLocation;
        this.execCount = 1;
    }

    public ParsedSlowSqlRow(String serviceName, String bizType,
                            String abstractSql, String abstractHash,
                            long timeCostMs, String timeCostRaw,
                            String execParams, String sourceLocation, int executionCount) {
        this(serviceName, bizType, abstractSql, abstractHash, timeCostMs, timeCostRaw, execParams, sourceLocation);
        this.execCount = executionCount;
    }

    /** 聚合吃进同键的又一次出现：计数 +1；耗时更大则替换代表字段。 */
    public void absorb(long timeCostMs, String timeCostRaw, String execParams, String sourceLocation) {
        absorb(timeCostMs, timeCostRaw, execParams, sourceLocation, 1);
    }

    /** 合并同类SQL的输入次数，代表字段仍来自耗时最大的输入行。 */
    public void absorb(long timeCostMs, String timeCostRaw, String execParams, String sourceLocation,
                       int executionCount) {
        this.execCount = Math.addExact(this.execCount, executionCount);
        if (timeCostMs > this.maxTimeCostMs) {
            this.maxTimeCostMs = timeCostMs;
            this.maxTimeCostRaw = timeCostRaw;
            this.execParams = execParams;
            this.sourceLocation = sourceLocation;
        }
    }
}
