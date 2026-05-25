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
import org.springframework.mock.web.MockHttpSession;
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

/**
 * 增强 v2 · UIAS 统一认证集成测试。
 *
 * <p>独立测试类（不破坏 {@link SecurityIntegrationTest} 已有 5 个测例）：
 * <ul>
 *   <li>{@code axon-link.security.enabled=true}（与 LDAP 测试同口径，复用 Security 装配）</li>
 *   <li>{@code axon-link.security.uias.enabled=true}（启用 UIAS 端点）</li>
 * </ul>
 *
 * <p>覆盖三条用例：
 * <ol>
 *   <li>GET {@code /api/auth/config} → 200 + JSON 含 {@code ldapEnabled/uiasEnabled/defaultMethod}</li>
 *   <li>GET {@code /api/auth/uias/callback} 无 session 工号 → 302 location 含 {@code error=uias_no_empno}</li>
 *   <li>GET {@code /api/auth/uias/callback} 预置 session 工号 → 302 location {@code /}，
 *       后续带 session 访问受保护接口 → 200，{@code /api/auth/me} 返回 principal=zhang.san</li>
 * </ol>
 *
 * <p>启动内嵌 LDAP 仅为满足 LdapContextSource bean 创建（UIAS 路径不会真访问 LDAP）。
 */
@SpringBootTest(
        classes = UiasIntegrationTest.UiasTestApp.class,
        properties = {
                "axon-link.security.enabled=true",
                "axon-link.security.uias.enabled=true",
                "dao-index-analysis.batch-trigger.token=test-token",
        })
@DisplayName("UIAS 集成测试 —— 双登录方式增强 v2")
class UiasIntegrationTest {

    /**
     * 测试专用最小 Spring Boot 引导类（参考 {@link SecurityIntegrationTest.SecurityTestApp} 的写法）。
     */
    @SpringBootConfiguration
    @ComponentScan(
            basePackages = "com.axonlink.security",
            useDefaultFilters = true,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = UiasIntegrationTest.ProtectedTestController.class))
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @EnableConfigurationProperties(DaoIndexAnalysisProperties.class)
    static class UiasTestApp {
    }

    /** 测试用受保护接口（验证带 session 后能拿到 200，并能读出 principal）。 */
    @RestController
    public static class ProtectedTestController {
        @GetMapping("/api/test/uias-protected")
        public R<String> protectedEndpoint() {
            return R.ok("protected-ok");
        }
    }

    private static InMemoryDirectoryServer ldapServer;
    private static int ldapPort;

    @BeforeAll
    static void startLdap() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=test,dc=com");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("LDAP", 0));
        config.setSchema(null);
        ldapServer = new InMemoryDirectoryServer(config);
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
        ldapServer.startListening();
        ldapPort = ldapServer.getListenPort();
    }

    @AfterAll
    static void stopLdap() {
        if (ldapServer != null) {
            ldapServer.shutDown(true);
        }
    }

    @DynamicPropertySource
    static void ldapProps(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> "ldap://localhost:" + ldapPort);
        registry.add("spring.ldap.base", () -> "dc=test,dc=com");
        registry.add("spring.ldap.username", () -> "");
        registry.add("spring.ldap.password", () -> "");
        registry.add("axon-link.security.user-search-base", () -> "ou=Users");
        registry.add("axon-link.security.user-search-filter", () -> "(uid={0})");
    }

    @Autowired
    private WebApplicationContext context;

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

    // ───────────────────────── 用例 1：/api/auth/config ─────────────────────────

    @Test
    @DisplayName("①GET /api/auth/config → 200 + JSON 含 ldapEnabled/uiasEnabled/defaultMethod")
    void getConfigEndpointReturnsAuthMethods() throws Exception {
        MvcResult result = mvc().perform(get("/api/auth/config")).andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "/api/auth/config 应 200，实际响应 body="
                        + result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode json = new ObjectMapper().readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, json.get("code").asInt());
        JsonNode data = json.get("data");
        assertNotNull(data, "data 节不应为空");
        assertTrue(data.has("ldapEnabled"), "data 应含 ldapEnabled 字段");
        assertTrue(data.has("uiasEnabled"), "data 应含 uiasEnabled 字段");
        assertTrue(data.has("defaultMethod"), "data 应含 defaultMethod 字段");
        assertEquals(true, data.get("ldapEnabled").asBoolean(), "ldapEnabled 应为 true");
        assertEquals(true, data.get("uiasEnabled").asBoolean(), "uiasEnabled 应为 true");
        assertEquals("UIAS", data.get("defaultMethod").asText(),
                "两者都开时 defaultMethod 应为 UIAS");
    }

    // ───────────────────────── 用例 2：callback 无 empno → uias_no_empno ─────────────────────────

    @Test
    @DisplayName("②GET /api/auth/uias/callback 无 session 工号 → 302 location 含 error=uias_no_empno")
    void uiasCallbackWithoutEmpnoRedirectsWithError() throws Exception {
        MvcResult result = mvc().perform(get("/api/auth/uias/callback")).andReturn();
        assertEquals(302, result.getResponse().getStatus(), "callback 应 302 重定向");
        String location = result.getResponse().getHeader("Location");
        assertNotNull(location, "应有 Location header");
        assertTrue(location.contains("error=uias_no_empno"),
                "Location 应含 error=uias_no_empno，实际：" + location);
    }

    // ───────────────────────── 用例 3：callback 有 empno → 302 / 并装配 principal ─────────────────────────

    @Test
    @DisplayName("③GET /api/auth/uias/callback 预置 session 工号 zhang.san → 302 / 且后续带 session 拿到 principal")
    void uiasCallbackWithEmpnoLogsInAndRedirects() throws Exception {
        // 准备：手动在 session 中塞工号（模拟 SDK 行为）
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("uiasEmpNo", "zhang.san");

        // 触发 callback
        MvcResult callbackResult = mvc().perform(get("/api/auth/uias/callback").session(session)).andReturn();
        assertEquals(302, callbackResult.getResponse().getStatus(), "callback 应 302");
        String location = callbackResult.getResponse().getHeader("Location");
        assertNotNull(location);
        assertEquals("/", location, "成功路径 Location 应为 /");

        // 复用同一个 session 访问受保护接口 → 应 200（已登录）
        MvcResult protectedResult = mvc()
                .perform(get("/api/test/uias-protected").session(session))
                .andReturn();
        assertEquals(200, protectedResult.getResponse().getStatus(),
                "callback 后复用 session 访问受保护接口应 200，实际："
                        + protectedResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        // 同 session 调 /api/auth/me 应能拿到 principal=zhang.san
        MvcResult meResult = mvc().perform(get("/api/auth/me").session(session)).andReturn();
        assertEquals(200, meResult.getResponse().getStatus());
        JsonNode meJson = new ObjectMapper().readTree(
                meResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("zhang.san", meJson.get("data").get("username").asText(),
                "UIAS 登录后 /api/auth/me 应返回 username=zhang.san");
    }
}
