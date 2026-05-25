package com.axonlink.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 灰度关闭时的"放行所有请求"Security 配置。
 *
 * <p><b>背景</b>：spring-boot-starter-security 加进 pom 后，Spring Boot 默认
 * 会自动装配一条"所有请求都要 HTTP Basic + 自动生成密码"的 SecurityFilterChain。
 * 这破坏了设计文档第 7 条决议："关闭时 Security 整套不装配，等价于'从未引入'"。
 *
 * <p>本配置在 {@code axon-link.security.enabled=false}（或缺省）时生效，
 * 注册一条 permitAll 的 SecurityFilterChain，覆盖默认的 secure-by-default 行为，
 * 让所有 /api/** 接口仍像引入依赖前一样开放（兼容现有 dev/CI 流程）。
 *
 * <p>与 {@link SecurityConfig} 互斥：两者通过 {@code @ConditionalOnProperty} 的
 * {@code havingValue} 值二选一，永远只有一个被装配。
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "axon-link.security", name = "enabled", havingValue = "false", matchIfMissing = true)
public class PermissiveSecurityConfig {

    /**
     * 放行所有请求 + 禁用 CSRF + 禁用所有登录入口。
     *
     * <p>注意：本 chain 同样禁用了 httpBasic 和 formLogin，避免 Spring Boot 默认
     * 行为（"username=user, password=<随机日志>"）暴露给运维误用。
     */
    @Bean
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        http
                // 允许所有请求，无需任何认证
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // 禁用 CSRF —— 与 enabled=true 路径一致，且避免对原有 POST 接口的影响
                .csrf(csrf -> csrf.disable())
                // 禁用默认的 HTTP Basic / 表单登录 / logout 入口
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable());
        return http.build();
    }
}
