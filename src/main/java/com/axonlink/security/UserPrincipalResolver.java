package com.axonlink.security;

import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 统一用户解析器：把 Spring Security 里的 principal（username / empNo）
 * 解析为 {@link SysUser}，依据 session 中的 {@link AuthController.SessionKeys#AUTH_METHOD} 标记
 * 自动选择查询字段。
 *
 * <ul>
 *   <li>{@code LDAP} → 按 {@code sys_user.username} 查</li>
 *   <li>{@code UIAS} → 按 {@code sys_user.emp_no} 查</li>
 *   <li>其他/未标记 → 优先按 username 查，未命中再按 emp_no 查（兜底）</li>
 * </ul>
 */
@Component
public class UserPrincipalResolver {

    @Autowired
    private SysUserDao sysUserDao;

    /** 解析结果：包含登录方式、principal、命中的 sys_user。 */
    public static class Resolved {
        public final String authMethod;
        public final String principal;
        public final SysUser user;

        public Resolved(String authMethod, String principal, SysUser user) {
            this.authMethod = authMethod;
            this.principal = principal;
            this.user = user;
        }

        public boolean isAuthenticated() { return user != null; }
    }

    /**
     * 解析当前请求的用户。
     *
     * @param request 当前 HTTP 请求（用于读 session 里的 authMethod 标记）
     * @return 解析结果；如果未登录或查不到对应 sys_user，user 为 null
     */
    public Resolved resolve(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return new Resolved("ANONYMOUS", null, null);
        }
        String principal = auth.getName();
        if (principal == null || principal.isBlank()) {
            return new Resolved("UNKNOWN", null, null);
        }

        String method = AuthController.resolveAuthMethod(request);
        SysUser user = lookup(method, principal);
        return new Resolved(method, principal, user);
    }

    private SysUser lookup(String authMethod, String principal) {
        if ("UIAS".equals(authMethod)) {
            SysUser u = sysUserDao.findByEmpNo(principal);
            if (u != null) return u;
        }
        if ("LDAP".equals(authMethod)) {
            SysUser u = sysUserDao.findByUsername(principal);
            if (u != null) return u;
        }
        // 兜底：先 username 再 emp_no
        SysUser u = sysUserDao.findByUsername(principal);
        if (u != null) return u;
        return sysUserDao.findByEmpNo(principal);
    }
}
