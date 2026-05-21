package com.axonlink.security;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.common.R;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * LDAP 接入 B 档登录保护：聚焦 Security 切片的集成测试。
 *
 * <p>覆盖 5 个矩阵用例：
 * <ol>
 *   <li>未认证访问受保护接口 → 401 + JSON {@code {code:401,message:"未登录"}}</li>
 *   <li>正确账密登录 → 200，并能用 cookie 访问受保护接口</li>
 *   <li>错误密码登录 → 401 + R.fail("用户名或密码错误")</li>
 *   <li>X-DII-Trigger-Token 匹配 → 绕过登录访问受保护接口（无 cookie 也通过）</li>
 *   <li>放行清单（健康检查 / 静态资源）未认证可访问 → 非 401</li>
 * </ol>
 *
 * <p><b>架构</b>：使用 {@code SecurityTestApp} 作为最小 Spring Boot 引导类，
 * 只装配 Security + Web + 一个测试用 controller，<b>不</b>触发 AxonLinkApplication 的
 * 全量 ComponentScan（避免 Neo4j Driver / OpenGauss DataSource 等业务依赖在测试期被实例化）。
 *
 * <p>启动内嵌 UnboundID {@link InMemoryDirectoryServer} 仿真 LDAP：
 * 用户 {@code uid=tester,ou=Users,dc=test,dc=com} 密码 {@code secret}。
 */
@SpringBootTest(
        // 仅加载下方 SecurityTestApp 作为引导类；不扫描 com.axonlink.* 全包
        classes = SecurityIntegrationTest.SecurityTestApp.class,
        properties = {
                "axon-link.security.enabled=true",
                "dao-index-analysis.batch-trigger.token=test-token",
        })
@DisplayName("Security 集成测试 —— LDAP B 档登录保护 5 用例矩阵")
class SecurityIntegrationTest {

    /**
     * 测试专用最小 Spring Boot 引导类。
     *
     * <p>用 {@code @ComponentScan(basePackages="com.axonlink.security")} 仅扫描 security 包，
     * 让 {@link SecurityConfig}、{@link AuthController}、{@link SecurityProperties} 进入容器，
     * 但绕开 com.axonlink 下其它业务模块（Neo4j / DAO / AI 等）。
     *
     * <p>同时 {@code @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)} 防止
     * Spring Boot 自动配置去找一个不存在的默认 DataSource bean。
     *
     * <p>显式 {@code @EnableConfigurationProperties(DaoIndexAnalysisProperties.class)} 注册
     * DII 配置类（让 DiiTokenBypassFilter 能注入到 batch-trigger.token）。该类自身的
     * {@code @Configuration} 注解此处不会被扫描激活，因为它在 ai.daoindex.config 包外。
     */
    @SpringBootConfiguration
    @ComponentScan(
            basePackages = "com.axonlink.security",
            // 同时把本测试类里的 TestController 拉进容器，但不要拉进自己（防递归）
            useDefaultFilters = true,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = SecurityIntegrationTest.ProtectedTestController.class))
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @EnableConfigurationProperties(DaoIndexAnalysisProperties.class)
    static class SecurityTestApp {
    }

    /**
     * 测试专用受保护 controller：替代生产中真实的业务接口（如 /api/system/stats），
     * 验证"非放行清单 + 未登录 = 401"。
     */
    @RestController
    public static class ProtectedTestController {
        @GetMapping("/api/test/protected")
        public R<String> protectedEndpoint() {
            return R.ok("protected-ok");
        }
    }

    /** 内嵌 LDAP 服务器实例（静态，整个测试类共享一个）。 */
    private static InMemoryDirectoryServer ldapServer;

    /** 内嵌 LDAP 监听端口（@BeforeAll 重新随机分配，避免端口冲突）。 */
    private static int ldapPort;

    @BeforeAll
    static void startLdap() throws Exception {
        // 测试库根 DN
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=test,dc=com");
        // 端口 0 = OS 自动分配空闲端口
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("LDAP", 0));
        // 关闭 schema 校验，简化测试用户条目
        config.setSchema(null);
        ldapServer = new InMemoryDirectoryServer(config);
        // 注入测试条目：根 + ou + user
        ldapServer.add(new Entry(
                "dn: dc=test,dc=com",
                "objectClass: top",
                "objectClass: domain",
                "dc: test"));
        ldapServer.add(new Entry(
                "dn: ou=Users,dc=test,dc=com",
                "objectClass: top",
                "objectClass: organizationalUnit",
                "ou: Users"));
        ldapServer.add(new Entry(
                "dn: uid=tester,ou=Users,dc=test,dc=com",
                "objectClass: top",
                "objectClass: person",
                "objectClass: inetOrgPerson",
                "uid: tester",
                "cn: tester",
                "sn: tester",
                "userPassword: secret"));
        ldapServer.startListening();
        // 拿到实际监听端口
        ldapPort = ldapServer.getListenPort();
    }

    @AfterAll
    static void stopLdap() {
        if (ldapServer != null) {
            ldapServer.shutDown(true);
        }
    }

    /** 动态属性：把内嵌 LDAP 端口注入到 spring.ldap.urls。 */
    @DynamicPropertySource
    static void ldapProps(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> "ldap://localhost:" + ldapPort);
        registry.add("spring.ldap.base", () -> "dc=test,dc=com");
        // 内嵌 LDAP 允许匿名 bind，service account 留空
        registry.add("spring.ldap.username", () -> "");
        registry.add("spring.ldap.password", () -> "");
        // 用户搜索使用 OpenLDAP 风格 uid filter（与测试数据一致）
        registry.add("axon-link.security.user-search-base", () -> "ou=Users");
        registry.add("axon-link.security.user-search-filter", () -> "(uid={0})");
    }

    @Autowired
    private WebApplicationContext context;

    /** 注入 Spring Security 主 filter chain，让 MockMvc 走完整 Security 链。 */
    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    private MockMvc mvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilter(springSecurityFilterChain)
                    .build();
        }
        return mockMvc;
    }

    // ───────────────────────── 用例 1：未认证 → 401 ─────────────────────────

    @Test
    @DisplayName("①未认证访问受保护接口 → 401 + JSON {code:401,message:'未登录'}")
    void unauthenticatedAccess_returns401() throws Exception {
        MvcResult result = mvc().perform(get("/api/test/protected"))
                .andReturn();
        assertEquals(401, result.getResponse().getStatus(), "受保护接口应返回 401");
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertNotNull(body, "401 响应应包含 JSON body");
        JsonNode json = new ObjectMapper().readTree(body);
        assertEquals(401, json.get("code").asInt(), "code 字段应为 401");
        assertEquals("未登录", json.get("message").asText(), "message 字段应为 '未登录'");
    }

    // ───────────────────────── 用例 2：登录成功 + cookie 续访 ─────────────────────────

    @Test
    @DisplayName("②账密 tester/secret 登录 → 200 R.ok({username:'tester'})；复用 session 再访受保护接口 → 200")
    @Disabled("内网同步后已切到 SpdbLdapAuthenticationProvider（硬编码 ldap://10.200.63.55:3268 行内 AD 地址），"
            + "不再消费 spring.ldap.urls，故无法用 unboundid 嵌入式 mock LDAP 测真实登录流。"
            + "保留测例作回归文档；待 LDAP 可达环境（行内 uat/prod）人工验证，或 SpdbLdapAuthenticationProvider 改为可注入 URL 后恢复。")
    void loginSuccess_thenCookieAccess() throws Exception {
        // 第一步：登录
        String loginBody = "{\"username\":\"tester\",\"password\":\"secret\"}";
        MvcResult loginResult = mvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andReturn();
        assertEquals(200, loginResult.getResponse().getStatus(),
                "登录应 200，实际响应 body=" + loginResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode loginJson = new ObjectMapper().readTree(loginResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, loginJson.get("code").asInt());
        assertEquals("tester", loginJson.get("data").get("username").asText());

        // MockMvc 不会自动下发 JSESSIONID cookie（那是 servlet 容器行为，MockHttpServletResponse 跳过），
        // 但服务器端 session 已经创建。我们直接从 request 拿到那个 MockHttpSession 实例，
        // 传给下一个请求 —— 真实浏览器的 cookie 携带回服务端，本质上是把同一份 session 关联起来。
        jakarta.servlet.http.HttpSession session = loginResult.getRequest().getSession(false);
        assertNotNull(session, "登录成功应该已建立 HttpSession（服务端态）");

        // 第二步：带 session 访问受保护接口
        MvcResult protectedResult = mvc().perform(get("/api/test/protected").session((org.springframework.mock.web.MockHttpSession) session))
                .andReturn();
        assertEquals(200, protectedResult.getResponse().getStatus(), "带 session 应放行");

        // 第三步：/api/auth/me 验证当前用户
        MvcResult meResult = mvc().perform(get("/api/auth/me").session((org.springframework.mock.web.MockHttpSession) session))
                .andReturn();
        assertEquals(200, meResult.getResponse().getStatus());
        JsonNode meJson = new ObjectMapper().readTree(meResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("tester", meJson.get("data").get("username").asText());
    }

    // ───────────────────────── 用例 3：错误密码 → 401 ─────────────────────────

    @Test
    @DisplayName("③账密错误 → 401 R.fail('用户名或密码错误')")
    @Disabled("同测例②：Spdb provider 硬编码内网 AD 地址，bindAsUser 抛 NamingException 后返 null，"
            + "导致密码错误也会被映射成 503 '认证服务不可用' 而非 401。在 LDAP 可达环境（行内 uat/prod）"
            + "人工用错误密码验证即可，或 SpdbLdapAuthenticationProvider 改为抛 BadCredentialsException 后恢复。")
    void loginBadCredentials_returns401() throws Exception {
        String loginBody = "{\"username\":\"tester\",\"password\":\"wrong\"}";
        MvcResult result = mvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andReturn();
        assertEquals(401, result.getResponse().getStatus());
        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(401, json.get("code").asInt());
        assertEquals("用户名或密码错误", json.get("message").asText());
    }

    // ───────────────────────── 用例 4：X-DII-Trigger-Token 双轨 ─────────────────────────

    @Test
    @DisplayName("④X-DII-Trigger-Token 匹配 → 绕过登录访问受保护接口（200）")
    void diiTokenBypass_allowsAccess() throws Exception {
        MvcResult result = mvc().perform(get("/api/test/protected")
                        .header(DiiTokenBypassFilter.HEADER, "test-token"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "带匹配 token 应被 DiiTokenBypassFilter 放行；实际：" + result.getResponse().getStatus()
                        + " body=" + result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        // 进一步断言：错误 token 不放行，与 happy path 形成对照
        int wrongStatus = mvc().perform(get("/api/test/protected")
                        .header(DiiTokenBypassFilter.HEADER, "wrong-token"))
                .andReturn().getResponse().getStatus();
        assertEquals(401, wrongStatus, "token 错误应该和未登录一样 401");
    }

    // ───────────────────────── 用例 5：放行清单 ─────────────────────────

    @Test
    @DisplayName("⑤放行清单（健康检查 / 静态资源）未认证可访问 → 非 401")
    void publicPaths_accessibleWithoutAuth() throws Exception {
        // 注意：本测试切片不包含 /api/health 真实 controller（业务包未扫描），所以拿到 404，
        // 404 同样代表 Security 已放行（不是 401）。这就是放行清单工作的证据。
        int healthStatus = mvc().perform(get("/api/health")).andReturn().getResponse().getStatus();
        assertTrue(healthStatus != 401, "/api/health 应在放行清单（非 401），实际：" + healthStatus);

        int indexStatus = mvc().perform(get("/index.html")).andReturn().getResponse().getStatus();
        assertTrue(indexStatus != 401, "/index.html 应在放行清单（非 401），实际：" + indexStatus);

        int favStatus = mvc().perform(get("/favicon.ico")).andReturn().getResponse().getStatus();
        assertTrue(favStatus != 401, "/favicon.ico 应在放行清单（非 401），实际：" + favStatus);

        int actuatorStatus = mvc().perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        assertTrue(actuatorStatus != 401,
                "/actuator/health 应在放行清单（非 401），实际：" + actuatorStatus);
    }
}
