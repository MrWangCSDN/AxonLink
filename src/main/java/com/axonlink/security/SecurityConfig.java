package com.axonlink.security;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Security 配置（LDAP B 档 · 灰度装配）。
 *
 * <p><b>装配条件</b>：{@code axon-link.security.enabled=true}（默认 false 不装配，
 * 等价于"从未引入 Security"，所有 /api/** 仍开放）。
 *
 * <p><b>保护策略</b>：
 * <ul>
 *   <li>放行：登录入口 {@code /api/auth/**}、健康检查 {@code /actuator/health} / {@code /api/health}、
 *       SPA 首屏 {@code /} / {@code /index.html} / {@code /assets/**} / {@code /monaco/**} /
 *       {@code /favicon*} / {@code /spd-bank-logo.png}</li>
 *   <li>其它所有路径：需登录（B 档不分角色）</li>
 *   <li>CSRF 禁用（内部应用 + 同源 + SameSite=Lax + JSON POST 已闭合主流攻击面）</li>
 *   <li>401 → JSON {@code {code:401, message:"未登录"}}</li>
 *   <li>注册 {@link DiiTokenBypassFilter} 在 UsernamePasswordAuthenticationFilter 之前，
 *       支持 X-DII-Trigger-Token 双轨绕过登录</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "axon-link.security", name = "enabled", havingValue = "true")
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Spring 环境，用于读取 {@code spring.ldap.*} 配置。 */
    private final Environment env;
    /** axon-link.security.* 自定义配置。 */
    private final SecurityProperties sp;
    /** DII 模块配置（DiiTokenBypassFilter 透传）。 */
    private final DaoIndexAnalysisProperties diiProps;

    /** 放行清单：与设计文档保持一致；任何变动需同步设计。 */
    private static final String[] PUBLIC_PATHS = new String[]{
            "/api/auth/**",
            // 增强 v2 双登录方式：UIAS 入口 + callback；虽已被上面 /api/auth/** 覆盖，
            // 但显式列出以表达意图，便于后续阅读 / 改 ant pattern 时不漏放行。
            "/api/auth/uias/**",
            // 增强 v2 双登录方式：前端拉 Tab 显示策略；同上属冗余但显式。
            "/api/auth/config",
            "/actuator/health",
            "/api/health",
            "/",
            "/index.html",
            "/assets/**",
            "/monaco/**",
            "/favicon*",
            "/spd-bank-logo.png",
    };

    public SecurityConfig(Environment env, SecurityProperties sp, DaoIndexAnalysisProperties diiProps) {
        // 构造注入：Spring 自动按类型装配三个依赖
        this.env = env;
        this.sp = sp;
        this.diiProps = diiProps;
    }

    /**
     * LDAP 上下文：bind 服务账号建立到 LDAP 服务器的连接池。
     *
     * <p>从 {@code spring.ldap.*} 取配置；服务账号 DN/密码留空时，仍可对匿名可读的 LDAP 工作
     * （仅作运行时容错，不建议生产部署）。
     */
    @Bean
    public LdapContextSource ldapContextSource() {
        // 直接读 Environment，避免重复定义一份 LdapProperties
        LdapContextSource ctx = new LdapContextSource();
        ctx.setUrl(env.getProperty("spring.ldap.urls", "ldap://localhost:389"));
        ctx.setBase(env.getProperty("spring.ldap.base", ""));
        ctx.setUserDn(env.getProperty("spring.ldap.username", ""));
        ctx.setPassword(env.getProperty("spring.ldap.password", ""));
        // afterPropertiesSet 触发内部 LdapTemplate 初始化（连接池等）
        ctx.afterPropertiesSet();
        log.info("[security] LdapContextSource 初始化完成 url={} base={}",
                ctx.getUrls() == null ? "?" : String.join(",", ctx.getUrls()),
                env.getProperty("spring.ldap.base"));
        return ctx;
    }

    /**
     * LDAP 认证提供器（search-then-bind 模式）。
     *
     * <p>流程：
     * <ol>
     *   <li>用服务账号 bind LDAP（{@link LdapContextSource}）</li>
     *   <li>按 {@code userSearchFilter} 在 {@code userSearchBase} 下查找登录用户的 DN</li>
     *   <li>用查到的 DN + 用户提交的密码再次 bind（验证密码）</li>
     * </ol>
     *
     * <p>B 档不读 LDAP 组（不调 setAuthoritiesPopulator），登录成功用户拥有默认的
     * 已认证身份但不携带任何业务角色。未来 C 档加 RBAC 时在此挂
     * {@code DefaultLdapAuthoritiesPopulator(ctx, sp.getGroupSearchBase())} 即可。
     */
    @Bean
    public SpdbLdapAuthenticationProvider ldapAuthenticationProvider(LdapContextSource ctx) {
        // 内网定制：Spring Security 内置 LdapAuthenticationProvider + FilterBasedLdapUserSearch + BindAuthenticator
        // 整套替换为浦发自研 SpdbLdapAuthenticationProvider（封装行内 LDAP/AD 特有协议细节，由行内安全团队维护）。
        // ctx 仍作为参数注入保留 DI 顺序（Spring 先实例化 LdapContextSource bean），方便未来该 provider 内部按需读取。
        return new SpdbLdapAuthenticationProvider();
    }

    /**
     * 暴露 {@link AuthenticationManager} 给 {@link AuthController} 注入。
     *
     * <p>Spring Boot 3 默认不再自动暴露 AuthenticationManager bean，需要这样手动暴露。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * DII token 双轨过滤器 Bean。
     *
     * <p>注入 {@link DaoIndexAnalysisProperties}；filter 在 doFilterInternal 时
     * 现读 {@code props.getBatchTrigger().getToken()}，支持热更新（依赖 properties 自身的 binder）。
     */
    @Bean
    public DiiTokenBypassFilter diiTokenBypassFilter() {
        return new DiiTokenBypassFilter(diiProps);
    }

    /**
     * SecurityFilterChain 主配置。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SpdbLdapAuthenticationProvider ldapProvider,
                                                   DiiTokenBypassFilter diiFilter) throws Exception {
        http
                // ① CSRF 禁用：内部应用 + 同源 + SameSite=Lax + JSON POST，无传统 CSRF 风险
                .csrf(csrf -> csrf.disable())
                // ② 认证提供器：仅 LDAP（B 档无其它来源）
                .authenticationProvider(ldapProvider)
                // ③ 注册 DII token 双轨 filter 在用户名密码认证 filter 之前
                .addFilterBefore(diiFilter, UsernamePasswordAuthenticationFilter.class)
                // ④ 授权策略：放行清单 + 其它需登录
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // ⑤ session：默认 IF_REQUIRED，登录时创建 session；未登录访问受保护接口不创建空 session
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // ⑥ 未认证处理：返回 JSON {code:401, message:"未登录"}，Content-Type=application/json;charset=UTF-8
                .exceptionHandling(eh -> eh.authenticationEntryPoint(this::writeUnauthorizedJson))
                // ⑦ 关闭 httpBasic / formLogin：登录走自定义 /api/auth/login（AuthController），不要 Spring 默认表单
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable());

        return http.build();
    }

    /**
     * 401 JSON 响应：与现有 R<T> 统一响应体保持一致。
     *
     * <p>不直接依赖 com.axonlink.common.R 的序列化（R 是 POJO 没 builder），
     * 用 LinkedHashMap 显式控制字段顺序 {@code code/message/data}，方便前端调试。
     */
    private void writeUnauthorizedJson(jakarta.servlet.http.HttpServletRequest req,
                                       HttpServletResponse resp,
                                       org.springframework.security.core.AuthenticationException ex)
            throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json;charset=UTF-8");
        // 与 R.fail("未登录") 语义一致，但显式 code=401
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 401);
        body.put("message", "未登录");
        body.put("data", null);
        new ObjectMapper().writeValue(resp.getOutputStream(), body);
    }
}
