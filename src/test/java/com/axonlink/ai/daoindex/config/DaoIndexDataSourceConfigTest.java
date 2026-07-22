package com.axonlink.ai.daoindex.config;

import com.axonlink.ai.daoindex.target.TargetDataSourceRegistry;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * datasource-fail-fast 启动建连策略回归：
 * <ul>
 *     <li>false（外网本地）：库连不上也能完成数据源/目标库注册表装配，应用可启动；</li>
 *     <li>true（默认，内网/生产）：保持 HikariCP fail-fast，连不上启动即抛异常。</li>
 * </ul>
 */
@DisplayName("DaoIndexDataSourceConfig —— datasource-fail-fast 启动建连策略")
class DaoIndexDataSourceConfigTest {

    /** 127.0.0.1:1 无服务监听，TCP 连接立刻被拒——模拟"连不上的内网库"且用例不耗时。 */
    private static final String UNREACHABLE_MYSQL_URL = "jdbc:mysql://127.0.0.1:1/benchmarkdb";
    /** 任何 classpath 上都不存在对应驱动的 URL scheme——模拟驱动缺失/URL 前缀不被识别。 */
    private static final String NO_DRIVER_URL = "jdbc:no-such-driver://127.0.0.1:1/database_dev";

    private final DaoIndexDataSourceConfig config = new DaoIndexDataSourceConfig();

    private DaoIndexAnalysisProperties props(boolean failFast) {
        DaoIndexAnalysisProperties props = new DaoIndexAnalysisProperties();
        props.setDatasourceFailFast(failFast);
        DaoIndexAnalysisProperties.ResultDatasource rd = new DaoIndexAnalysisProperties.ResultDatasource();
        rd.setUrl(UNREACHABLE_MYSQL_URL);
        rd.setUsername("u");
        rd.setPassword("p");
        rd.setConnectionTimeoutMs(300); // Hikari 允许的下限 250ms，压缩 fail-fast 用例耗时
        props.setResultDatasource(rd);
        return props;
    }

    @Test
    @DisplayName("默认值：datasource-fail-fast=true（内网行为不变的前提）")
    void defaultIsFailFast() {
        assertTrue(new DaoIndexAnalysisProperties().isDatasourceFailFast());
    }

    @Test
    @DisplayName("fail-fast=false：结果库连不上也能完成装配（外网本地启动场景）")
    void lenientInit_buildsResultDataSourceWithoutDb() {
        try (HikariDataSource ds = config.diiResultDataSource(props(false))) {
            assertNotNull(ds);
            assertEquals(-1, ds.getInitializationFailTimeout());
            assertEquals(0, ds.getMinimumIdle(), "惰性模式应清零 minimumIdle，避免后台补连反复刷失败日志");
        }
    }

    private static DaoIndexAnalysisProperties.Target target(String url) {
        DaoIndexAnalysisProperties.Target target = new DaoIndexAnalysisProperties.Target();
        target.setUrl(url);
        target.setUsername("u");
        target.setPassword("p");
        target.setConnectionTimeoutMs(300);
        return target;
    }

    @Test
    @DisplayName("fail-fast=false：目标库连不上也能惰性装配；驱动解析失败的目标降级跳过")
    void lenientInit_buildsTargetRegistryAndSkipsBrokenDriver() {
        DaoIndexAnalysisProperties props = props(false);
        props.getTargets().put("dev", target(UNREACHABLE_MYSQL_URL)); // 驱动可解析、库连不上：应正常注册
        props.getTargets().put("broken", target(NO_DRIVER_URL));      // 驱动不可解析：应跳过而非炸启动
        props.setDefaultEnv("dev");
        try (TargetDataSourceRegistry registry = config.targetDataSourceRegistry(props)) {
            assertNotNull(registry.getByEnv("dev"));
            assertEquals("dev", registry.getDefaultEnv());
            assertEquals(1, registry.allEnvs().size(), "驱动解析失败的 broken 目标应被跳过");
        }
    }

    @Test
    @DisplayName("fail-fast=false：全部目标库驱动都解析失败也不阻断启动（外网默认 opengauss URL 场景）")
    void lenientInit_toleratesAllTargetsBroken() {
        DaoIndexAnalysisProperties props = props(false);
        props.getTargets().put("dev", target(NO_DRIVER_URL));
        props.setDefaultEnv("dev");
        try (TargetDataSourceRegistry registry = config.targetDataSourceRegistry(props)) {
            assertTrue(registry.allEnvs().isEmpty(), "无可用目标时注册表为空，但不应抛异常");
        }
    }

    @Test
    @DisplayName("fail-fast=true（默认）：结果库连不上启动即抛异常（内网快速暴露配置错误）")
    void failFast_throwsWhenResultDbUnreachable() {
        assertThrows(HikariPool.PoolInitializationException.class,
                () -> config.diiResultDataSource(props(true)).close());
    }

    @Test
    @DisplayName("fail-fast=true（默认）：目标库驱动解析失败仍启动即抛（内网现状行为不变）")
    void failFast_throwsWhenTargetDriverUnresolvable() {
        DaoIndexAnalysisProperties props = props(true);
        props.getTargets().put("dev", target(NO_DRIVER_URL));
        props.setDefaultEnv("dev");
        assertThrows(RuntimeException.class, () -> config.targetDataSourceRegistry(props).close());
    }
}
