package com.axonlink.ai.daoindex.health;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import com.axonlink.ai.provider.LlmClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块启动自检。
 *
 * <p>启动后立即跑一次 {@link #check()}，把结果以 {@code [dii-health]} 前缀打到 INFO 日志；
 * 控制器也可以按需调用 {@link #check()} 返回结构化报告。
 *
 * <p>此 PR 只做"能连通、能建会话、依赖 Bean 存在"级别的验证；
 * 真正的 EXPLAIN 语法解析和 LLM 调用会在后续 PR 的 debug 接口里做。
 */
@Component
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class DaoIndexHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DaoIndexHealthIndicator.class);

    private static final String[] REQUIRED_RESULT_TABLES = {"dii_analysis_task", "dii_analysis_item"};

    private final DaoIndexAnalysisProperties props;
    private final TargetDataSourceRegistry targetRegistry;
    private final JdbcTemplate resultJdbcTemplate;
    private final ObjectProvider<LlmClient> llmClientProvider;

    public DaoIndexHealthIndicator(DaoIndexAnalysisProperties props,
                                   TargetDataSourceRegistry targetRegistry,
                                   JdbcTemplate diiResultJdbcTemplate,
                                   ObjectProvider<LlmClient> llmClientProvider) {
        this.props = props;
        this.targetRegistry = targetRegistry;
        this.resultJdbcTemplate = diiResultJdbcTemplate;
        this.llmClientProvider = llmClientProvider;
    }

    @PostConstruct
    public void runOnStartup() {
        try {
            DaoIndexHealthReport report = check();
            log.info("[dii-health] overallOk={}", report.isOverallOk());
            log.info("[dii-health] targetDb={}", report.getTargetDb());
            log.info("[dii-health] resultDb={} tables={}", report.getResultDb(), report.getResultTables());
            log.info("[dii-health] llm={}", report.getLlm());
            log.info("[dii-health] explainGenericPlanSupported={}", report.getExplainGenericPlanSupported());
        } catch (Exception e) {
            log.error("[dii-health] 启动自检异常：{}", e.getMessage(), e);
        }
    }

    /** 执行一次完整自检，结果不打日志，由调用方决定打日志或返回响应。 */
    public DaoIndexHealthReport check() {
        DaoIndexHealthReport report = new DaoIndexHealthReport();
        checkTargets(report);
        checkResultDb(report);
        checkLlm(report);
        boolean ok = "OK".equals(report.getResultDb())
                && report.getTargetDb().values().stream().allMatch("OK"::equals)
                && report.getResultTables().values().stream().allMatch("OK"::equals);
        report.setOverallOk(ok);
        return report;
    }

    private void checkTargets(DaoIndexHealthReport report) {
        Map<String, String> status = new LinkedHashMap<>();
        Map<String, Boolean> genericPlanSupport = new LinkedHashMap<>();
        for (String env : targetRegistry.allEnvs()) {
            DataSource ds;
            try {
                ds = targetRegistry.getByEnv(env);
            } catch (Exception e) {
                status.put(env, "FAIL: " + e.getMessage());
                genericPlanSupport.put(env, null);
                continue;
            }
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                    rs.next();
                }
                status.put(env, "OK");
                genericPlanSupport.put(env, tryExplainGenericPlan(stmt));
            } catch (Exception e) {
                status.put(env, "FAIL: " + safeMessage(e));
                genericPlanSupport.put(env, null);
            }
        }
        report.setTargetDb(status);
        report.setExplainGenericPlanSupported(genericPlanSupport);
    }

    private Boolean tryExplainGenericPlan(Statement stmt) {
        try (ResultSet rs = stmt.executeQuery(
                "EXPLAIN (GENERIC_PLAN, FORMAT JSON) SELECT 1")) {
            rs.next();
            return Boolean.TRUE;
        } catch (Exception e) {
            // 部分 GaussDB 版本不支持 GENERIC_PLAN 关键字，失败不影响主链路。
            log.debug("[dii-health] EXPLAIN (GENERIC_PLAN) 探测失败：{}", e.getMessage());
            return Boolean.FALSE;
        }
    }

    private void checkResultDb(DaoIndexHealthReport report) {
        try {
            Integer one = resultJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            report.setResultDb(Integer.valueOf(1).equals(one) ? "OK" : "FAIL: unexpected reply " + one);
        } catch (Exception e) {
            report.setResultDb("FAIL: " + safeMessage(e));
            for (String tbl : REQUIRED_RESULT_TABLES) {
                report.getResultTables().put(tbl, "SKIPPED: result-db down");
            }
            return;
        }
        for (String tbl : REQUIRED_RESULT_TABLES) {
            try {
                Integer cnt = resultJdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() AND table_name = ?",
                        Integer.class, tbl);
                report.getResultTables().put(tbl,
                        (cnt != null && cnt > 0) ? "OK" : "FAIL: missing, 请执行 DDL 建表");
            } catch (Exception e) {
                report.getResultTables().put(tbl, "FAIL: " + safeMessage(e));
            }
        }
    }

    private void checkLlm(DaoIndexHealthReport report) {
        LlmClient client = llmClientProvider.getIfAvailable();
        if (client == null) {
            report.setLlm("SKIPPED: LlmClient bean 不存在（未启用 AI 模块？）");
        } else {
            report.setLlm("CONFIGURED: " + client.getClass().getSimpleName());
        }
    }

    public DaoIndexAnalysisProperties getProps() {
        return props;
    }

    private static String safeMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
