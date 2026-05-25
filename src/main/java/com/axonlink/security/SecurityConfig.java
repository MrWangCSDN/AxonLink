package com.axonlink.security;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
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

    public SecurityConfig(SecurityProperties sp, DaoIndexAnalysisProperties diiProps) {
        // 构造注入：Spring 自动按类型装配两个依赖
        this.sp = sp;
        this.diiProps = diiProps;
    }

    /**
     * LDAP 认证提供器：Spring 标准 {@link ActiveDirectoryLdapAuthenticationProvider}（增强 v3）。
     *
     * <p>原自研 SpdbLdapAuthenticationProvider（手写 JNDI bind）已删，换用 Spring 专为 AD 打造的
     * 标准 provider，一次解决：① 硬编码 → config 驱动（{@code axon-link.security.ad.*}）；
     * ② 密码错误正确抛 {@link org.springframework.security.authentication.BadCredentialsException}
     * → AuthController 落 401（不再是 503）；③ 用上框架成熟的 AD 错误码解析。
     *
     * <p>关键配置：
     * <ul>
     *   <li>{@code (domain, url)}：UPN bind，用户输 {@code user} 或 {@code user@domain} 都行（内部补 domain）</li>
     *   <li>{@code setConvertSubErrorCodesToExceptions(true)}：把 AD 子错误码（52e 密码错 / 525 用户不存在 /
     *       530/531 登录时限 / 532 密码过期 / 533 账号禁用 / 701 账号过期 / 773 须改密）映射成
     *       对应 {@code BadCredentialsException}/{@code AccountStatusException}</li>
     *   <li>{@code setSearchFilter}：默认 {@code (&(objectClass=user)(userPrincipalName={0}))}，与原自研一致</li>
     * </ul>
     *
     * <p>B 档不读 LDAP 组：不设 {@code GrantedAuthoritiesMapper}，登录成功用户已认证但空权限（全员同权）。
     * 未来 C 档加 RBAC 时在此挂 authoritiesMapper 即可。
     */
    @Bean
    public AuthenticationProvider ldapAuthenticationProvider() {
        SecurityProperties.AdConfig ad = sp.getAd();
        ActiveDirectoryLdapAuthenticationProvider provider =
                new ActiveDirectoryLdapAuthenticationProvider(ad.getDomain(), ad.getUrl());
        // AD 子错误码 → 标准异常（密码错 52e → BadCredentialsException，AuthController 据此落 401）
        provider.setConvertSubErrorCodesToExceptions(true);
        // 用登录请求里的凭据做后续搜索（标准 AD bind-then-search）
        provider.setUseAuthenticationRequestCredentials(true);
        provider.setSearchFilter(ad.getSearchFilter());
        log.info("[security] ActiveDirectoryLdapAuthenticationProvider 初始化 domain={} url={}",
                ad.getDomain(), ad.getUrl());
        return provider;
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
                                                   AuthenticationProvider ldapProvider,
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
