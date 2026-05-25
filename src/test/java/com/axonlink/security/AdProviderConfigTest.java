package com.axonlink.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AD provider 装配单测（增强 v3）。
 *
 * <p>不连真实 AD（{@code ActiveDirectoryLdapAuthenticationProvider} 构造不建立连接，
 * 连接发生在 authenticate() 时），只验证两件事：
 * <ol>
 *   <li>{@link SecurityProperties.AdConfig} 默认值与原自研 Spdb 硬编码一致（迁移零行为变化的保证）</li>
 *   <li>{@link SecurityConfig#ldapAuthenticationProvider()} 返回的是 Spring 标准
 *       {@link ActiveDirectoryLdapAuthenticationProvider}，而非自研类</li>
 * </ol>
 *
 * <p>真实 AD 登录流（密码对→200 / 密码错→401）依赖真实 AD 错误码，由
 * {@code SecurityIntegrationTest} 用例②③（@Disabled）记录，在行内 uat/prod 人工验证。
 */
class AdProviderConfigTest {

    @Test
    @DisplayName("AdConfig 默认值 = 原 Spdb 硬编码（domain/url/searchFilter），保证迁移零行为变化")
    void adConfigDefaults() {
        SecurityProperties.AdConfig ad = new SecurityProperties.AdConfig();
        assertEquals("hdq.spdb.com", ad.getDomain(), "AD 域默认值");
        assertEquals("ldap://10.200.63.55:3268", ad.getUrl(), "AD URL 默认值（GC 3268）");
        assertEquals("(&(objectClass=user)(userPrincipalName={0}))", ad.getSearchFilter(),
                "AD searchFilter 默认值（UPN）");
    }

    @Test
    @DisplayName("ldapAuthenticationProvider() 返回 Spring 标准 ActiveDirectoryLdapAuthenticationProvider")
    void providerIsStandardAdProvider() {
        // diiProps 本测不用，传 null；SecurityProperties 默认即带 AdConfig 默认值
        SecurityConfig cfg = new SecurityConfig(new SecurityProperties(), null);
        AuthenticationProvider provider = cfg.ldapAuthenticationProvider();
        assertNotNull(provider, "provider 不应为 null");
        assertInstanceOf(ActiveDirectoryLdapAuthenticationProvider.class, provider,
                "应是 Spring 标准 AD provider，而非自研 SpdbLdapAuthenticationProvider");
    }
}
