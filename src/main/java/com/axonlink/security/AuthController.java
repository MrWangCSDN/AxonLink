package com.axonlink.security;

import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.service.UserService;
import com.axonlink.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.io.IOException;
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

    /** 安全配置（UIAS 子节读 enabled / sdkUrl / ssoTarget / sessionEmpnoKey）。 */
    private final SecurityProperties sp;

    /** 统一用户解析（按 LDAP/UIAS 标记查 sys_user）。 */
    private final UserPrincipalResolver principalResolver;

    /** 用户管理服务（LDAP 首次登录自动同步）。 */
    private final UserService userService;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityProperties sp,
                          UserPrincipalResolver principalResolver,
                          UserService userService) {
        this.authenticationManager = authenticationManager;
        this.sp = sp;
        this.principalResolver = principalResolver;
        this.userService = userService;
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
            // 标记登录方式：LDAP（账密）→ 业务侧按 username 查 sys_user
            request.getSession(true).setAttribute(SessionKeys.AUTH_METHOD, "LDAP");
            // LDAP 用户首次登录自动同步到 ccbs_ai_sys_user（仅 username + realName）
            // UIAS 路径不走这里，所以 UIAS 用户只能通过用户管理页面手动维护
            try {
                String realName = extractRealNameFromAuth(auth);
                String principalClass = auth.getPrincipal() == null ? "null"
                        : auth.getPrincipal().getClass().getName();
                log.info("[auth] LDAP principal class={}, getName()={}, extractedRealName={}",
                        principalClass, auth.getName(), realName);
                if (realName == null) realName = auth.getName();  // fallback：用 username 占位
                userService.syncLdapUserIfFirstLogin(auth.getName(), realName);
            } catch (Exception e) {
                // 同步失败不影响登录主流程
                log.warn("[auth] LDAP 用户同步失败 user={} : {}", auth.getName(), e.getMessage());
            }
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
     * 当前 session 中保存的关键常量。供其它模块引用，避免散落字符串。
     */
    public static final class SessionKeys {
        /** session 中保存的登录方式：{@code "LDAP"} / {@code "UIAS"} */
        public static final String AUTH_METHOD = "authMethod";
        private SessionKeys() {}
    }

    /** 用于标识登录方式：LDAP | UIAS | UNKNOWN */
    public static String resolveAuthMethod(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) return "UNKNOWN";
        Object v = s.getAttribute(SessionKeys.AUTH_METHOD);
        return v == null ? "UNKNOWN" : String.valueOf(v);
    }

    /**
     * GET /api/auth/me —— 返回当前用户。
     */
    @GetMapping("/me")
    public ResponseEntity<R<Map<String, Object>>> me(HttpServletRequest request) {
        // 从 SecurityContext 取 Authentication
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 已认证且非 anonymous（Spring Security 给未登录请求会塞一个 AnonymousAuthenticationToken）
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            // 用 principalResolver 把 username/empNo 解析到 sys_user，拿到真实姓名
            UserPrincipalResolver.Resolved cu = principalResolver.resolve(request);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("username", auth.getName());
            // 登录方式：LDAP / UIAS
            data.put("authMethod", cu.authMethod);
            // 中文真实姓名（最核心字段，前端右上角用）
            data.put("realName", cu.user != null ? cu.user.getRealName() : null);
            // 附加：工号、邮箱、部门（按需取）
            if (cu.user != null) {
                data.put("empNo", cu.user.getEmpNo());
                data.put("email", cu.user.getEmail());
                data.put("department", cu.user.getDepartment());
            }
            return ResponseEntity.ok(R.ok(data));
        }
        // 未登录：返回 401 + R.fail("未登录")，与 SecurityConfig 的 entrypoint 一致
        R<Map<String, Object>> r = R.fail("未登录");
        r.setCode(401);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(r);
    }

    /**
     * UIAS 统一认证登录入口（占位）。
     *
     * <p>真实 UIAS SDK 接入后，SDK 会接管本方法，跳到 UIAS 网关。
     * 本期占位实现：
     * <ul>
     *   <li>{@code uias.enabled=true} + {@code sdk-url} 已配 → 302 跳 {@code sdk-url?ssotarget=...}</li>
     *   <li>{@code uias.enabled=false} 或 sdk-url 空 → 302 跳 {@code /login?error=uias_not_configured}</li>
     * </ul>
     *
     * <p>TODO（SDK 接入位置）：将下方拼 URL 替换为 SDK 提供的 redirectToUias() 方法。
     */
    @GetMapping("/uias/login")
    public void uiasLogin(HttpServletResponse response) throws IOException {
        SecurityProperties.UiasConfig uias = sp.getUias();
        if (!uias.isEnabled() || isBlank(uias.getSdkUrl())) {
            response.sendRedirect("/login?error=uias_not_configured");
            return;
        }
        // TODO 真 SDK 接入位置：SDK 通常提供 redirectToUias(response, ssoTarget) 工具方法
        StringBuilder url = new StringBuilder(uias.getSdkUrl());
        if (!isBlank(uias.getSsoTarget())) {
            url.append(url.indexOf("?") >= 0 ? "&" : "?")
               .append("ssotarget=").append(uias.getSsoTarget());
        }
        log.info("[uias] 跳转 UIAS 网关 url={}", url);
        response.sendRedirect(url.toString());
    }

    /**
     * UIAS 认证回调：从 session 读 SDK 塞入的工号 → 装 Spring Security SecurityContext → 跳首页。
     *
     * <p>前提：UIAS SDK 在跳到本端点前已经把工号放进 {@code session.{sessionEmpnoKey}}。
     *
     * <p>失败路径（均 302 到 {@code /login?error=...}）：
     * <ul>
     *   <li>session 里没工号 → {@code uias_no_empno}（SDK 集成异常）</li>
     *   <li>装 SecurityContext 失败 → {@code uias_callback_failed}</li>
     * </ul>
     *
     * <p>成功：装 PreAuthenticatedAuthenticationToken（principal=empNo，role=ROLE_UIAS_USER），
     * 写入 HttpSession SPRING_SECURITY_CONTEXT_KEY，与 LDAP 登录同口径，下游 controller 可
     * 一致用 {@code request.getRemoteUser()} 拿 empNo。
     */
    @GetMapping("/uias/callback")
    public void uiasCallback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityProperties.UiasConfig uias = sp.getUias();
        if (!uias.isEnabled()) {
            response.sendRedirect("/login?error=uias_not_configured");
            return;
        }
        try {
            HttpSession session = request.getSession(false);
            Object empNoVal = session != null ? session.getAttribute(uias.getSessionEmpnoKey()) : null;
            String empNo = empNoVal != null ? String.valueOf(empNoVal).trim() : "";
            if (empNo.isEmpty()) {
                log.warn("[uias] callback 无 empno session-key={}", uias.getSessionEmpnoKey());
                response.sendRedirect("/login?error=uias_no_empno");
                return;
            }

            // 装 Spring Security SecurityContext（与 LDAP login 同口径）
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            empNo, "N/A",
                            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_UIAS_USER")));
            org.springframework.security.core.context.SecurityContext ctx =
                    org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);

            HttpSession s = request.getSession(true);
            s.setAttribute(
                    org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    ctx);
            // 标记登录方式：UIAS（单点）→ 业务侧按 emp_no 查 sys_user
            s.setAttribute(SessionKeys.AUTH_METHOD, "UIAS");
            // SDK 中间态 key 用完清掉，避免后续误用
            s.removeAttribute(uias.getSessionEmpnoKey());

            log.info("[uias] callback 装配成功 empNo={}", empNo);
            response.sendRedirect("/");
        } catch (Throwable t) {
            log.error("[uias] callback 异常: {}", t.getMessage(), t);
            response.sendRedirect("/login?error=uias_callback_failed");
        }
    }

    /**
     * GET /api/auth/config —— 前端用，决定登录页显哪些 Tab + 默认选哪个。
     *
     * <p>响应：{@code {ldapEnabled: true|false, uiasEnabled: true|false, defaultMethod: "LDAP"|"UIAS"}}。
     * <p>{@code defaultMethod}：两个都开时默认 UIAS；只一个开时默认那个；两个都关时返 "NONE"。
     */
    @GetMapping("/config")
    public R<java.util.Map<String, Object>> config() {
        boolean ldap = true;   // 本方法本身在 @ConditionalOnProperty enabled=true 才装配，到这里 LDAP 一定 enabled
        boolean uias = sp.getUias().isEnabled();
        String defaultMethod = uias ? "UIAS" : (ldap ? "LDAP" : "NONE");
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("ldapEnabled", ldap);
        data.put("uiasEnabled", uias);
        data.put("defaultMethod", defaultMethod);
        return R.ok(data);
    }

    /** 简单空字符串判断（避免引入 commons-lang）。 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 从 LDAP 认证后的 Authentication 里提取中文真实姓名。
     * 优先：principal.getDn() 中的 cn；fallback：principal 对象的 toString() 里匹配。
     */
    private static String extractRealNameFromAuth(Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        if (principal == null) return null;
        // 1) 尝试 Spring LDAP 的 LdapUserDetailsImpl.getDn() 反射取值（避免强依赖 Spring LDAP 类型）
        String dn = invokeStringGetter(principal, "getDn");
        if (dn == null) dn = invokeStringGetter(principal, "getDistinguishedName");
        if (dn != null) {
            String cn = LdapDnParser.extractCn(dn);
            if (cn != null && !cn.isBlank()) return cn;
        }
        // 2) fallback：从 principal.toString() 里找 cn=... 段
        String cn = LdapDnParser.extractCn(String.valueOf(principal));
        return cn;
    }

    /** 反射调用无参 getXxx()，返回值是 String 才返回，否则 null。 */
    private static String invokeStringGetter(Object target, String method) {
        try {
            Object v = target.getClass().getMethod(method).invoke(target);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }
}
