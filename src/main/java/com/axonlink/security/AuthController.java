package com.axonlink.security;

import com.axonlink.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录 / 登出 / 当前用户 三接口。
 *
 * <p>仅当 {@code axon-link.security.enabled=true} 时装配。
 *
 * <p>接口契约（与设计文档对齐）：
 * <ul>
 *   <li>{@code POST /api/auth/login}：body {@code {username, password}} JSON，
 *       成功返回 {@code R.ok({username})} 并写入 session cookie；
 *       密码错返回 401 + {@code R.fail("用户名或密码错误")}；
 *       LDAP 不可达返回 503 + {@code R.fail("认证服务不可用")}</li>
 *   <li>{@code POST /api/auth/logout}：清空 SecurityContext + invalidate session，返回 {@code R.ok(null)}</li>
 *   <li>{@code GET  /api/auth/me}：已登录返回 {@code R.ok({username})}；未登录返回 401 + {@code R.fail("未登录")}</li>
 * </ul>
 *
 * <p>注意：本 controller 路径在 {@link SecurityConfig#PUBLIC_PATHS} 放行清单内，
 * Security filter 不会拦截。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "axon-link.security", name = "enabled", havingValue = "true")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 用于在 controller 里手动调用 LDAP 认证流程。 */
    private final AuthenticationManager authenticationManager;

    public AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /**
     * 登录请求体 DTO：与 Vue 表单的字段名一致。
     */
    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /**
     * POST /api/auth/login —— 账密登录。
     */
    @PostMapping("/login")
    public ResponseEntity<R<Map<String, Object>>> login(@RequestBody LoginRequest body,
                                                        HttpServletRequest request,
                                                        jakarta.servlet.http.HttpServletResponse response) {
        // 入参基础校验：空字符串也视为非法
        if (body == null || isBlank(body.getUsername()) || isBlank(body.getPassword())) {
            R<Map<String, Object>> r = R.fail("用户名或密码不能为空");
            r.setCode(400);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(r);
        }
        try {
            // 构造 UsernamePasswordAuthenticationToken（未认证态：仅 principal + credentials）
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(body.getUsername(), body.getPassword());
            // 委托给 AuthenticationManager；它会找到 LdapAuthenticationProvider 完成 search-then-bind
            Authentication auth = authenticationManager.authenticate(token);
            // 把已认证的 Authentication 写入 SecurityContext，并持久化到 HttpSession
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            // Spring Security 6 起，自动持久化已弃用。这里显式：
            //   ① request.getSession(true) 强制创建 session（让 servlet 容器下发 JSESSIONID cookie）
            //   ② 直接 setAttribute 把 SecurityContext 存进 session（key 与
            //      HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY 对齐）
            // 这样后续请求带 cookie 时，SecurityContextPersistenceFilter / SecurityContextHolderFilter
            // 会从 session 读出 context 写入 SecurityContextHolder，下游 .authenticated() 检查通过。
            request.getSession(true).setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    context);
            log.info("[auth] 登录成功 user={}", auth.getName());
            // 成功响应：包装 username（不返回任何敏感信息）
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("username", auth.getName());
            return ResponseEntity.ok(R.ok(data));
        } catch (BadCredentialsException e) {
            // 用户名 / 密码不匹配
            log.warn("[auth] 登录失败：用户名或密码错误 user={}", body.getUsername());
            R<Map<String, Object>> r = R.fail("用户名或密码错误");
            r.setCode(401);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(r);
        } catch (AuthenticationException e) {
            // 其它认证异常（如 LDAP 服务连不上抛 CommunicationException 等）
            log.error("[auth] 登录失败：认证服务异常 user={} msg={}", body.getUsername(), e.getMessage());
            R<Map<String, Object>> r = R.fail("认证服务不可用");
            r.setCode(503);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(r);
        }
    }

    /**
     * POST /api/auth/logout —— 登出。
     */
    @PostMapping("/logout")
    public ResponseEntity<R<Void>> logout(HttpServletRequest request) {
        // 清 SecurityContext（线程局部）
        SecurityContextHolder.clearContext();
        // 失效 session（让 cookie 对应的服务端 session 无效）
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(R.ok(null));
    }

    /**
     * GET /api/auth/me —— 返回当前用户。
     */
    @GetMapping("/me")
    public ResponseEntity<R<Map<String, Object>>> me() {
        // 从 SecurityContext 取 Authentication
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 已认证且非 anonymous（Spring Security 给未登录请求会塞一个 AnonymousAuthenticationToken）
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("username", auth.getName());
            return ResponseEntity.ok(R.ok(data));
        }
        // 未登录：返回 401 + R.fail("未登录")，与 SecurityConfig 的 entrypoint 一致
        R<Map<String, Object>> r = R.fail("未登录");
        r.setCode(401);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(r);
    }

    /** 简单空字符串判断（避免引入 commons-lang）。 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
