package com.axonlink.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 安全与认证模块配置（LDAP B 档）。
 *
 * <p>绑定 application.yml 中 {@code axon-link.security.*} 节点。
 *
 * <p>本类不放 {@code @ConditionalOnProperty}：即使 {@code enabled=false}，
 * 仍然可以被注入到管理端 / 调试端读取配置；真正决定 Security 是否装配的
 * 是 {@link SecurityConfig} 上的 {@code @ConditionalOnProperty}。
 *
 * <p>命名风格与项目其它 properties 类（{@code Neo4jConfig}、{@code DaoIndexAnalysisProperties}、
 * {@code FlowtranConfig}）保持一致：{@code @Configuration} + {@code @ConfigurationProperties}，
 * 不额外加 {@code @Component} / {@code @EnableConfigurationProperties}。
 */
@Configuration
@ConfigurationProperties(prefix = "axon-link.security")
public class SecurityProperties {

    /** 灰度开关，默认 false。false 时 Security 整套不装配，所有 /api/** 仍开放（向后兼容）。 */
    private boolean enabled = false;

    /** LDAP 用户搜索 base（相对 spring.ldap.base），例如 {@code OU=Users}。 */
    private String userSearchBase = "OU=Users";

    /**
     * LDAP 用户搜索 filter。{@code {0}} 会被替换为登录用户名。
     * <ul>
     *   <li>AD：{@code (sAMAccountName={0})}</li>
     *   <li>OpenLDAP：{@code (uid={0})}</li>
     * </ul>
     */
    private String userSearchFilter = "(sAMAccountName={0})";

    /** 组搜索 base（预留，B 档不读组，未来 C 档接口级 RBAC 使用）。 */
    private String groupSearchBase = "OU=Groups";

    /** 组搜索 filter（预留，B 档不读组）。 */
    private String groupSearchFilter = "(member={0})";

    /** 会话超时（分钟），默认 120。当前依赖 Spring Boot 默认 session，预留字段供未来扩展。 */
    private int sessionTimeoutMinutes = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getUserSearchBase() { return userSearchBase; }
    public void setUserSearchBase(String userSearchBase) { this.userSearchBase = userSearchBase; }

    public String getUserSearchFilter() { return userSearchFilter; }
    public void setUserSearchFilter(String userSearchFilter) { this.userSearchFilter = userSearchFilter; }

    public String getGroupSearchBase() { return groupSearchBase; }
    public void setGroupSearchBase(String groupSearchBase) { this.groupSearchBase = groupSearchBase; }

    public String getGroupSearchFilter() { return groupSearchFilter; }
    public void setGroupSearchFilter(String groupSearchFilter) { this.groupSearchFilter = groupSearchFilter; }

    public int getSessionTimeoutMinutes() { return sessionTimeoutMinutes; }
    public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }
}
