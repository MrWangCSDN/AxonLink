package com.axonlink.security;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * X-DII-Trigger-Token 双轨过滤器。
 *
 * <p>设计目的：脚本/curl 携带 {@code X-DII-Trigger-Token} 且匹配
 * {@code dao-index-analysis.batch-trigger.token} 配置时，<b>跳过 LDAP 登录</b>，
 * 让自动化触发链路与现有"未知触发者"语义完全一致（{@code request.getRemoteUser()} 为 null）。
 *
 * <p>实现策略：filter 注册在 Spring Security 链中的
 * {@code UsernamePasswordAuthenticationFilter} 之前；命中 token 时，
 * 给 SecurityContext 写入一个已认证状态的 token-principal，让下游
 * {@code .anyRequest().authenticated()} 检查能放行。<b>不</b> 提前 chain.doFilter
 * 跳过整个 Security 链——因为 chain.doFilter 之后下游 filter 仍会执行 authenticated 检查。
 *
 * <p>安全约束：token 配置本身为空（含全空白）时，本 filter <b>不</b>放行任何请求。
 * 这条约束很关键——否则任意带 header 的请求都会"匹配空 token"被误放行。
 * 与现有 {@code DaoIndexController.triggerBatch} 中 token 为空时"跳过校验（仅开发环境）"
 * 的行为不同：登录保护已开启的场景下，安全比便利优先。
 */
public class DiiTokenBypassFilter extends OncePerRequestFilter {

    /** DII 模块配置，用来拿到当前生效的 token 值。 */
    private final DaoIndexAnalysisProperties props;

    /** 请求头名称：与现有 DaoIndexController.triggerBatch 保持一致。 */
    public static final String HEADER = "X-DII-Trigger-Token";

    /** 旁路 principal 的虚拟用户名（不要与任何真实 LDAP 用户名重合）。 */
    public static final String DII_PRINCIPAL = "dii-token";

    /** 旁路 principal 的角色：未来 C 档可基于此角色做细粒度授权。 */
    public static final String DII_ROLE = "ROLE_DII_TOKEN";

    public DiiTokenBypassFilter(DaoIndexAnalysisProperties props) {
        // 通过构造注入而非字段注入，便于单元测试用 new 直接构造
        this.props = props;
    }

    /**
     * 主流程：检查请求头 → 命中则写认证态 → 总是放行到下一个 filter。
     *
     * <p>注意：本 filter 不主动短路；放行交给 SecurityFilterChain 自身的 authenticated 检查。
     * 命中 token 时已经把 SecurityContext 标记为已认证，下游会放行；
     * 未命中时 SecurityContext 仍为空，下游会按未登录处理。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 取出当前生效的预期 token 值（来自配置）
        String expected = props.getBatchTrigger().getToken();
        // 取出请求头里的 token
        String actual = request.getHeader(HEADER);
        // 三段卫语句：① expected 非空 ② actual 非空 ③ 完全相等 —— 任一不满足都不旁路
        if (expected != null && !expected.trim().isEmpty()
                && actual != null && expected.equals(actual)) {
            // 命中：写一个已认证的 token-principal 到 SecurityContext
            // 用 UsernamePasswordAuthenticationToken 的"已认证"构造器（带 authorities 参数自动 setAuthenticated(true)）
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    DII_PRINCIPAL,
                    "N/A",
                    List.of(new SimpleGrantedAuthority(DII_ROLE)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        // 总是放行，让下游 SecurityFilterChain 决定最终访问权限
        chain.doFilter(request, response);
    }
}
