package com.axonlink.ai.daoindex.health;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DAO 索引巡检模块自检报告。
 *
 * <p>所有字段都是人类可读的字符串状态（OK / FAIL:xxx / SKIPPED），
 * 方便直接打日志或序列化给 /health 接口。
 */
public class DaoIndexHealthReport {

    /** 每个 env 的目标库连接状态：OK / FAIL:xxx。 */
    private Map<String, String> targetDb = new LinkedHashMap<>();
    /** 结果库连接状态。 */
    private String resultDb = "UNKNOWN";
    /** 结果库表是否存在。 */
    private Map<String, String> resultTables = new LinkedHashMap<>();
    /** LLM 客户端是否就绪（此 PR 只做装配检查）。 */
    private String llm = "UNKNOWN";
    /** 每个 env 是否支持 EXPLAIN (GENERIC_PLAN)。 */
    private Map<String, Boolean> explainGenericPlanSupported = new LinkedHashMap<>();
    /** 是否全部检查通过。 */
    private boolean overallOk;

    public Map<String, String> getTargetDb() { return targetDb; }
    public void setTargetDb(Map<String, String> targetDb) { this.targetDb = targetDb; }

    public String getResultDb() { return resultDb; }
    public void setResultDb(String resultDb) { this.resultDb = resultDb; }

    public Map<String, String> getResultTables() { return resultTables; }
    public void setResultTables(Map<String, String> resultTables) { this.resultTables = resultTables; }

    public String getLlm() { return llm; }
    public void setLlm(String llm) { this.llm = llm; }

    public Map<String, Boolean> getExplainGenericPlanSupported() { return explainGenericPlanSupported; }
    public void setExplainGenericPlanSupported(Map<String, Boolean> explainGenericPlanSupported) {
        this.explainGenericPlanSupported = explainGenericPlanSupported;
    }

    public boolean isOverallOk() { return overallOk; }
    public void setOverallOk(boolean overallOk) { this.overallOk = overallOk; }
}
