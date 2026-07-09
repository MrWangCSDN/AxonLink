package com.axonlink.ai.daoindex.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.service.WhitelistApplicationService;
import com.axonlink.ai.daoindex.sqlinspect.service.WhitelistApplicationService.ApplyRequest;
import com.axonlink.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 白名单审批工作流接口（V16）。
 *
 * <p>路径前缀：{@code /api/ai/dao-index/whitelist-applications}
 *
 * <p>当前用户来源：优先 {@link SecurityContextHolder}（LDAP B 档登录后即可拿到 principal）；
 * 回退到请求体里前端传的 {@code applicant} / {@code currentUser}（开发期 SecurityContext 为空时用）。
 *
 * <p>v1 不做严格的角色校验——审批人是否真有 L1 / L2 权限由 yml 名单守住，
 * 接口层只对状态机和并发兜底。
 */
@RestController
@RequestMapping("/api/ai/dao-index/whitelist-applications")
public class WhitelistApplicationController {

    private static final Logger log = LoggerFactory.getLogger(WhitelistApplicationController.class);

    private final WhitelistApplicationService service;

    public WhitelistApplicationController(WhitelistApplicationService service) {
        this.service = service;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 配置 / 元信息
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 我的待办计数（铃铛红点用）：当前用户作为 L1 / L2 审批人时还有多少条 PENDING 等他动手。
     * <p>不抛 401：未登录时返回 count=0，避免铃铛报错。
     */
    @GetMapping("/todo-count")
    public R<Map<String, Object>> todoCount(@RequestParam(required = false) String currentUser,
                                            HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            String user = resolveCurrentUser(request, currentUser);
            Map<String, Object> c = service.countMyPendingByCategory(user);
            data.put("currentUser", user);
            data.put("count", num(c.get("total")));
            data.put("slowSqlCount", num(c.get("slowSql")));
            data.put("sqlInspectCount", num(c.get("sqlInspect")));
        } catch (Exception e) {
            // 未登录场景：返回 0 不报错
            data.put("currentUser", null);
            data.put("count", 0L);
            data.put("slowSqlCount", 0L);
            data.put("sqlInspectCount", 0L);
        }
        return R.ok(data);
    }

    /** L1 / L2 审批人下拉名单（yml 注入）。 */
    @GetMapping("/approvers")
    public R<Map<String, Object>> approvers() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", service.isEnabled());
        data.put("l1Approvers", service.getL1Approvers());
        data.put("l2Approvers", service.getL2Approvers());
        return R.ok(data);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 申请
    // ─────────────────────────────────────────────────────────────────────────

    /** 申请白名单。前端 POST JSON body = {@link ApplyRequest}。 */
    @PostMapping
    public ResponseEntity<R<Map<String, Object>>> apply(@RequestBody ApplyRequest req,
                                                        HttpServletRequest request) {
        try {
            String currentUser = resolveCurrentUser(request, req.applicant);
            req.applicant = currentUser;
            long id = service.apply(req);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            return ResponseEntity.ok(R.ok(payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(R.<Map<String, Object>>fail(e.getMessage()));
        } catch (Exception e) {
            log.error("[wl-apply] 失败", e);
            return ResponseEntity.internalServerError().body(R.<Map<String, Object>>fail("申请失败：" + e.getMessage()));
        }
    }

    /** 白名单流转路径（慢SQL，跨多次申请，升序）：申请/一级·二级 通过/退回/撤回——谁、何时、理由。 */
    @GetMapping("/flow")
    public R<java.util.List<Map<String, Object>>> flow(@RequestParam String serviceName,
                                                       @RequestParam String abstractHash) {
        if (serviceName.isBlank() || abstractHash.isBlank()) {
            return R.fail("serviceName / abstractHash 不能为空");
        }
        return R.ok(service.listSlowSqlFlow(serviceName.trim(), abstractHash.trim()));
    }

    /** 单条查看。 */
    @GetMapping("/{id}")
    public R<Map<String, Object>> findById(@PathVariable long id) {
        Map<String, Object> row = service.findById(id);
        if (row == null) return R.fail("未找到 id=" + id);
        return R.ok(row);
    }

    /**
     * 我的待办：按当前用户 + 角色（l1 / l2 / applicant）查列表。
     * <p>{@code GET /whitelist-applications?role=l1&status=PENDING_L1}
     */
    @GetMapping
    public R<Map<String, Object>> listMyTodo(
            @RequestParam(required = false, defaultValue = "all") String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String currentUser,
            HttpServletRequest request) {
        String user = resolveCurrentUser(request, currentUser);
        List<Map<String, Object>> items = service.listMyTodo(user, role, status, limit, offset);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentUser", user);
        payload.put("items", items);
        return R.ok(payload);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 跃迁动作
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/l1-approve")
    public ResponseEntity<R<Map<String, Object>>> l1Approve(@PathVariable long id,
                                                            @RequestBody ActionRequest req,
                                                            HttpServletRequest request) {
        return runAction(() -> {
            String user = resolveCurrentUser(request, req.currentUser);
            service.l1Approve(id, user, req.opinion, req.l2Approver);
        });
    }

    @PostMapping("/{id}/l1-reject")
    public ResponseEntity<R<Map<String, Object>>> l1Reject(@PathVariable long id,
                                                           @RequestBody ActionRequest req,
                                                           HttpServletRequest request) {
        return runAction(() -> {
            String user = resolveCurrentUser(request, req.currentUser);
            service.l1Reject(id, user, req.opinion);
        });
    }

    @PostMapping("/{id}/l2-approve")
    public ResponseEntity<R<Map<String, Object>>> l2Approve(@PathVariable long id,
                                                            @RequestBody ActionRequest req,
                                                            HttpServletRequest request) {
        return runAction(() -> {
            String user = resolveCurrentUser(request, req.currentUser);
            service.l2Approve(id, user, req.opinion);
        });
    }

    @PostMapping("/{id}/l2-reject")
    public ResponseEntity<R<Map<String, Object>>> l2Reject(@PathVariable long id,
                                                           @RequestBody ActionRequest req,
                                                           HttpServletRequest request) {
        return runAction(() -> {
            String user = resolveCurrentUser(request, req.currentUser);
            service.l2Reject(id, user, req.opinion);
        });
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<R<Map<String, Object>>> cancel(@PathVariable long id,
                                                         @RequestBody(required = false) ActionRequest req,
                                                         HttpServletRequest request) {
        return runAction(() -> {
            String user = resolveCurrentUser(request, req == null ? null : req.currentUser);
            service.cancel(id, user);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String resolveCurrentUser(HttpServletRequest request, String fallback) {
        // 1) 优先 Spring Security
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        // 2) HTTP 标准 remote user
        String r = request.getRemoteUser();
        if (r != null && !r.isBlank()) return r;
        // 3) 兜底：前端传入（开发期）
        if (fallback != null && !fallback.isBlank()) return fallback;
        throw new IllegalArgumentException("无法识别当前用户：请确认登录态或在请求体里提供 currentUser");
    }

    /** Object→long（COUNT 返回 Long，SUM(CASE) 可能返回 BigDecimal/null）。 */
    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private ResponseEntity<R<Map<String, Object>>> runAction(Runnable action) {
        try {
            action.run();
            return ResponseEntity.ok(R.ok(Map.of("ok", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(R.fail(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(R.<Map<String, Object>>fail(e.getMessage()));
        } catch (Exception e) {
            log.error("[wl-action] 失败", e);
            return ResponseEntity.internalServerError().body(R.<Map<String, Object>>fail("操作失败：" + e.getMessage()));
        }
    }

    /** 审批/取消统一动作 DTO。 */
    public static class ActionRequest {
        public String opinion;       // 审批意见 / 退回意见（取消时可空）
        public String l2Approver;    // 仅 L1 通过时使用——选定二级审批人 username
        public String currentUser;   // 开发期兜底，正常应从 SecurityContext 取
    }
}
