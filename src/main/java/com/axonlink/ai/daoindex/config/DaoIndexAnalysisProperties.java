package com.axonlink.ai.daoindex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAO 索引巡检模块配置。
 *
 * <p>绑定 application.yml 中 {@code dao-index-analysis.*} 节点。
 * <p>模块默认开启（不再有 enabled 总开关），需要：
 * <ul>
 *     <li>{@code scan.*} 控制从哪些工程哪些目录抽取 @Statement SQL；</li>
 *     <li>{@code targets.*} 配置被巡检的目标库（支持多环境，按 env 选择）；</li>
 *     <li>{@code result-datasource.*} 配置结果库，建议与 sunline-benchmark 共库；</li>
 *     <li>{@code concurrency.*}、{@code skip-if-analyzed-within-hours}、{@code export.*}
 *         控制编排行为、幂等窗口和报告落盘策略。</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "dao-index-analysis")
public class DaoIndexAnalysisProperties {

    private Scan scan = new Scan();
    private Map<String, Target> targets = new LinkedHashMap<>();
    private String defaultEnv = "dev";
    private ResultDatasource resultDatasource = new ResultDatasource();
    private Concurrency concurrency = new Concurrency();
    private int skipIfAnalyzedWithinHours = 24;
    /**
     * 并发幂等窗口（分钟）。
     * <p>同一 {@code sql_hash + env} 在该窗口内若已有 {@code status='DONE'} 的记录，
     * 新分析请求直接复用旧结果，避免并发下重复分析。
     * <p>应用层在 inspect 入口 SELECT 判断，命中即返回旧 itemId；未命中才走规则引擎 + 落库。
     */
    private int concurrentReuseMinutes = 5;
    private Export export = new Export();
    private Schedule schedule = new Schedule();
    private BatchTrigger batchTrigger = new BatchTrigger();
    private Batch batch = new Batch();

    public Scan getScan() { return scan; }
    public void setScan(Scan scan) { this.scan = scan; }

    public Map<String, Target> getTargets() { return targets; }
    public void setTargets(Map<String, Target> targets) { this.targets = targets; }

    public String getDefaultEnv() { return defaultEnv; }
    public void setDefaultEnv(String defaultEnv) { this.defaultEnv = defaultEnv; }

    public ResultDatasource getResultDatasource() { return resultDatasource; }
    public void setResultDatasource(ResultDatasource resultDatasource) { this.resultDatasource = resultDatasource; }

    public Concurrency getConcurrency() { return concurrency; }
    public void setConcurrency(Concurrency concurrency) { this.concurrency = concurrency; }

    public int getSkipIfAnalyzedWithinHours() { return skipIfAnalyzedWithinHours; }
    public void setSkipIfAnalyzedWithinHours(int skipIfAnalyzedWithinHours) {
        this.skipIfAnalyzedWithinHours = skipIfAnalyzedWithinHours;
    }

    public int getConcurrentReuseMinutes() { return concurrentReuseMinutes; }
    public void setConcurrentReuseMinutes(int concurrentReuseMinutes) {
        this.concurrentReuseMinutes = concurrentReuseMinutes;
    }

    public Export getExport() { return export; }
    public void setExport(Export export) { this.export = export; }

    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule; }

    public BatchTrigger getBatchTrigger() { return batchTrigger; }
    public void setBatchTrigger(BatchTrigger batchTrigger) { this.batchTrigger = batchTrigger; }

    public Batch getBatch() { return batch; }
    public void setBatch(Batch batch) { this.batch = batch; }

    /**
     * 定时任务配置。
     * <p>本模块两条 {@code @Scheduled} 方法都会在执行前检查 {@link #enabled}，
     * 设为 {@code false} 即可临时屏蔽规则批量 + LLM 回填两条 cron。
     * <p>cron 表达式（{@link #dailyCron} / {@link #dailyLlmCron}）由 yml 注入到 {@code @Scheduled}
     * 注解的 SpEL 表达式中；这里同时保留字段以便代码内引用与未来扩展（动态调度等）。
     */
    public static class Schedule {
        /** 定时任务总开关，默认开启。 */
        private boolean enabled = true;
        /** 规则引擎 + EXPLAIN 批量巡检 cron，默认每天 01:00:00。 */
        private String dailyCron = "0 0 1 * * ?";
        /** LLM 批量回填 cron，默认每天 02:00:00。 */
        private String dailyLlmCron = "0 0 2 * * ?";
        /** LLM 回填单次最多处理的 item 数（兜底防爆）。 */
        private int dailyLlmMaxItems = 2000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDailyCron() { return dailyCron; }
        public void setDailyCron(String dailyCron) { this.dailyCron = dailyCron; }
        public String getDailyLlmCron() { return dailyLlmCron; }
        public void setDailyLlmCron(String dailyLlmCron) { this.dailyLlmCron = dailyLlmCron; }
        public int getDailyLlmMaxItems() { return dailyLlmMaxItems; }
        public void setDailyLlmMaxItems(int n) { this.dailyLlmMaxItems = n; }
    }

    /**
     * 手动触发巡检任务的口令保护配置。
     *
     * <p>对应 yml：{@code dao-index-analysis.batch-trigger.token}
     * <p>{@link com.axonlink.ai.daoindex.controller.DaoIndexController#triggerBatch}
     * 在执行前会校验请求 header {@code X-DII-Trigger-Token}：
     * <ul>
     *   <li>{@link #token} 为空 → 跳过校验（仅开发环境用）</li>
     *   <li>{@link #token} 非空 + header 不匹配 → 401 拒绝</li>
     *   <li>{@link #token} 非空 + header 匹配 → 走原有 startAsync 流程</li>
     * </ul>
     */
    public static class BatchTrigger {
        /**
         * 触发口令。
         * <p>⚠️ 生产部署严禁留空：留空 = 任何人都能触发批量巡检（一次跑 30 分钟+ 且消耗 LLM 配额）。
         * <p>关闭校验仅限本地开发：{@code export DII_BATCH_TRIGGER_TOKEN=}（空字符串）。
         * <p>默认值 {@code sunline300348} 仅用于演示，部署前必须通过环境变量覆盖。
         */
        private String token = "";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    /**
     * 批量巡检完成后的链式行为配置（增强 v5）。
     *
     * <p>对应 yml：{@code dao-index-analysis.batch.auto-llm-after-batch}
     * <p>控制批量巡检 EXPLAIN 跑完后，是否在同一异步线程内同步链式触发
     * {@link com.axonlink.ai.daoindex.sqlinspect.llm.LlmEnrichService#enrich} 做 LLM 回填：
     * <ul>
     *   <li>{@code true}（默认）：一步到位，batch-analyze 跑完直接补 LLM，无需再手动触发</li>
     *   <li>{@code false}：回退老两步行为，需手动 {@code POST /llm-enrich} 或等 02:00 定时</li>
     * </ul>
     */
    public static class Batch {
        /** 批量巡检 EXPLAIN 跑完后，是否自动链式触发 LLM 回填（默认 true=一步到位；
         *  false=回退老两步行为，需手动 POST /llm-enrich 或等 02:00 定时）。 */
        private boolean autoLlmAfterBatch = true;
        public boolean isAutoLlmAfterBatch() { return autoLlmAfterBatch; }
        public void setAutoLlmAfterBatch(boolean v) { this.autoLlmAfterBatch = v; }
    }

    /** 源码扫描配置。 */
    public static class Scan {
        /** 每个工程根目录绝对路径。 */
        private List<String> projectRoots = new ArrayList<>();
        /** 模块目录 glob，例如 *-bcc。 */
        private String modulePattern = "*-bcc";
        /** 模块内生成源相对路径。 */
        private String sourceRootRelative = "target/gen";
        /** 文件回溯路径中必须存在的父目录名。 */
        private String requiredParentDir = "tables";
        /** 文件名 glob。 */
        private String filePattern = "**/*.java";
        /** 注解全限定名白名单。 */
        private List<String> annotationFqns = new ArrayList<>();
        /** 承载 SQL 的注解属性名。 */
        private String sqlAttributeName = "sql";

        public List<String> getProjectRoots() { return projectRoots; }
        public void setProjectRoots(List<String> projectRoots) { this.projectRoots = projectRoots; }

        public String getModulePattern() { return modulePattern; }
        public void setModulePattern(String modulePattern) { this.modulePattern = modulePattern; }

        public String getSourceRootRelative() { return sourceRootRelative; }
        public void setSourceRootRelative(String sourceRootRelative) { this.sourceRootRelative = sourceRootRelative; }

        public String getRequiredParentDir() { return requiredParentDir; }
        public void setRequiredParentDir(String requiredParentDir) { this.requiredParentDir = requiredParentDir; }

        public String getFilePattern() { return filePattern; }
        public void setFilePattern(String filePattern) { this.filePattern = filePattern; }

        public List<String> getAnnotationFqns() { return annotationFqns; }
        public void setAnnotationFqns(List<String> annotationFqns) { this.annotationFqns = annotationFqns; }

        public String getSqlAttributeName() { return sqlAttributeName; }
        public void setSqlAttributeName(String sqlAttributeName) { this.sqlAttributeName = sqlAttributeName; }
    }

    /** 单个目标库（被巡检库）配置。 */
    public static class Target {
        private String url;
        private String username;
        private String password;
        /**
         * JDBC 驱动类名。
         *
         * <p><b>建议留空</b>（默认）——HikariCP 会走 {@code DriverManager.getDriver(url)}
         * 自动按 URL 前缀匹配 JDBC 4.0 SPI 注册的驱动，与 sunline-benchmark 的
         * {@code DriverManager.getConnection(...)} 完全一致，兼容 CSI / GaussDB 定制版等
         * 类名非 {@code org.opengauss.Driver} 的驱动。
         *
         * <p>仅在需要强制指定类名时填写：
         * <ul>
         *   <li>openGauss 社区标准版：{@code org.opengauss.Driver}</li>
         *   <li>GaussDB 企业版：{@code com.huawei.gaussdb.jdbc.Driver}</li>
         *   <li>PostgreSQL 兼容：{@code org.postgresql.Driver}</li>
         * </ul>
         */
        private String driverClassName = "";
        private int maximumPoolSize = 4;
        private int minimumIdle = 1;
        private long connectionTimeoutMs = 10_000L;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public void setMinimumIdle(int minimumIdle) { this.minimumIdle = minimumIdle; }
        public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(long connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
    }

    /** 结果库配置（写入巡检结果的 MySQL 库）。 */
    public static class ResultDatasource {
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        private String url;
        private String username;
        private String password;
        private int maximumPoolSize = 8;
        private int minimumIdle = 2;
        private long connectionTimeoutMs = 10_000L;
        private String connectionTestQuery = "SELECT 1";

        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public void setMinimumIdle(int minimumIdle) { this.minimumIdle = minimumIdle; }
        public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(long connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
        public String getConnectionTestQuery() { return connectionTestQuery; }
        public void setConnectionTestQuery(String connectionTestQuery) { this.connectionTestQuery = connectionTestQuery; }
    }

    /** 并发与超时参数。 */
    public static class Concurrency {
        /** 工程并发度，默认为 1（禁止跨工程交叉）。 */
        private int projectParallel = 1;
        /** 单工程内 SQL 并发度。 */
        private int sqlParallelPerProject = 8;
        /** LLM 并发上限。 */
        private int llmParallel = 4;
        /** 单条 SQL 端到端超时时间（秒）。 */
        private int singleSqlTimeoutSeconds = 30;
        /** LLM 失败重试次数。 */
        private int llmRetry = 1;
        /** EXPLAIN 失败重试次数。 */
        private int explainRetry = 1;

        public int getProjectParallel() { return projectParallel; }
        public void setProjectParallel(int projectParallel) { this.projectParallel = projectParallel; }
        public int getSqlParallelPerProject() { return sqlParallelPerProject; }
        public void setSqlParallelPerProject(int sqlParallelPerProject) { this.sqlParallelPerProject = sqlParallelPerProject; }
        public int getLlmParallel() { return llmParallel; }
        public void setLlmParallel(int llmParallel) { this.llmParallel = llmParallel; }
        public int getSingleSqlTimeoutSeconds() { return singleSqlTimeoutSeconds; }
        public void setSingleSqlTimeoutSeconds(int singleSqlTimeoutSeconds) { this.singleSqlTimeoutSeconds = singleSqlTimeoutSeconds; }
        public int getLlmRetry() { return llmRetry; }
        public void setLlmRetry(int llmRetry) { this.llmRetry = llmRetry; }
        public int getExplainRetry() { return explainRetry; }
        public void setExplainRetry(int explainRetry) { this.explainRetry = explainRetry; }
    }

    /** Excel 报告导出配置。 */
    public static class Export {
        /** 报告落盘目录。 */
        private String outputDir;
        /** 报告保留天数，超过则清理。 */
        private int retainDays = 30;

        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
        public int getRetainDays() { return retainDays; }
        public void setRetainDays(int retainDays) { this.retainDays = retainDays; }
    }
}
