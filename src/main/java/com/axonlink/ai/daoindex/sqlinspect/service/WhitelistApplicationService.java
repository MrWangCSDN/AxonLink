package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiWhitelistApplicationDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.notification.service.MailService;
import com.axonlink.notification.service.WhitelistMailTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.axonlink.ai.daoindex.sqlinspect.persistence.DiiWhitelistApplicationDao.*;

/**
 * SQL 白名单审批工作流业务编排（V16）。
 *
 * <p>承担状态机 + item/pool 同步的全部协调，DAO 只做单 SQL 更新；本类保证：
 * <ol>
 *   <li>申请、L1/L2、取消的输入校验与权限收口</li>
 *   <li>跃迁成功后同步 item/pool 的 {@code whitelist_app_id} / {@code whitelist_status}</li>
 *   <li>APPROVED 终态时把 {@code is_whitelist=1} 落到匹配行</li>
 *   <li>nsql 包含模式（target_type=NAMED_SQL）按 named_sql 扩散</li>
 * </ol>
 *
 * <p><b>权限</b>：v1 简化，信任前端传 username。具体校验：
 * <ul>
 *   <li>L1 跃迁：当前用户必须等于 application.l1_approver</li>
 *   <li>L2 跃迁：当前用户必须等于 application.l2_approver</li>
 *   <li>取消：当前用户必须等于 application.applicant，且状态 ∈ {PENDING_L1, REJECTED_L1}</li>
 * </ul>
 * 权限错由 DAO 的 WHERE 子句兜底——updateCount=0 即拒绝（避免并发竞态）。
 */
@Service
public class WhitelistApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WhitelistApplicationService.class);

    private final DiiWhitelistApplicationDao dao;
    private final DiiAnalysisItemDao itemDao;
    private final DiiSqlPoolDao poolDao;
    private final DiiSlowSqlDao slowSqlDao;
    private final DaoIndexAnalysisProperties props;

    /** 邮件服务（白名单审批通知）—— 注入可能为空（无 starter-mail 依赖时）。 */
    @Autowired(required = false)
    private MailService mailService;

    /** 用户 DAO（按 username 查 email）。 */
    @Autowired
    private SysUserDao sysUserDao;

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public WhitelistApplicationService(DiiWhitelistApplicationDao dao,
                                       DiiAnalysisItemDao itemDao,
                                       DiiSqlPoolDao poolDao,
                                       DiiSlowSqlDao slowSqlDao,
                                       DaoIndexAnalysisProperties props) {
        this.dao = dao;
        this.itemDao = itemDao;
        this.poolDao = poolDao;
        this.slowSqlDao = slowSqlDao;
        this.props = props;
    }

    /**
     * 申请白名单。常规初态 PENDING_L1；若<b>申请人本人即一级审批人</b>则自动通过一审、
     * 直接 PENDING_L2（由申请人在弹窗里指定二级审批人）。
     *
     * @param applicant       申请人 username
     * @param includeNamedSql nsql 是否勾选"包含 nsql 原始语句"——true=NAMED_SQL 作用域；
     *                        false=HASH 作用域
     * @param row             触发申请那一行的 item/pool 字段子集
     */
    public long apply(ApplyRequest req) {
        ensureEnabled();
        validate(req);

        String applicant = applicantRequired(req.applicant);
        // v8：申请人本人即一级审批人 → 自动通过一审、直接进入二审，此时必填二级审批人；
        //     否则走常规一审流程，必填一级审批人。审批人都须在 yml 名单内（防前端伪造任意 username）。
        boolean applicantIsL1 = isL1Approver(applicant);
        if (applicantIsL1) {
            if (!isL2Approver(req.l2Approver)) {
                throw new IllegalArgumentException("二级审批人不在配置名单内: " + req.l2Approver);
            }
        } else if (!isL1Approver(req.l1Approver)) {
            throw new IllegalArgumentException("一级审批人不在配置名单内: " + req.l1Approver);
        }

        String targetType;
        if (req.slowSql) {
            targetType = TARGET_SLOW_SQL;
            if (req.sqlHash == null || req.sqlHash.isBlank()) {
                throw new IllegalArgumentException("SLOW_SQL 模式必须提供 sql_hash(abstract_hash)");
            }
            // v2：慢SQL白名单粒度 = (微服务+抽象SQL)，微服务名借 projectName 字段携带（必填）
            if (req.projectName == null || req.projectName.isBlank()) {
                throw new IllegalArgumentException("SLOW_SQL 模式必须提供微服务名(projectName)");
            }
        } else {
            targetType = req.includeNamedSql ? TARGET_NAMED_SQL : TARGET_HASH;
            if (TARGET_NAMED_SQL.equals(targetType) && (req.namedSql == null || req.namedSql.isBlank())) {
                throw new IllegalArgumentException("NAMED_SQL 模式必须提供 named_sql");
            }
            if (TARGET_HASH.equals(targetType) && (req.sqlHash == null || req.sqlHash.isBlank())) {
                throw new IllegalArgumentException("HASH 模式必须提供 sql_hash");
            }
        }

        // 防重复：同 hash / named_sql 已存在活跃申请（非 CANCELLED）则拒绝
        // （慢SQL v2：按 微服务+hash 查重——不同微服务的同一抽象SQL各自独立申请）
        Map<String, Object> existing;
        if (TARGET_SLOW_SQL.equals(targetType)) {
            existing = dao.findLatestActiveBySlowHash(req.sqlHash, req.projectName);
        } else if (TARGET_HASH.equals(targetType)) {
            existing = dao.findLatestActiveBySqlHash(req.sqlHash);
        } else {
            existing = dao.findLatestActiveByNamedSql(req.namedSql);
        }
        if (existing != null && !STATUS_CANCELLED.equals(String.valueOf(existing.get("status")))) {
            throw new IllegalStateException(
                    "已存在活跃白名单申请 id=" + existing.get("id") + " status=" + existing.get("status"));
        }

        // 互斥：慢SQL 的白名单与优化二选一——仅拦「优化生效中(OPTIMIZED)」；
        // 未生效(REGRESSED)=上次尝试已失败归档，路线重新开放，允许转投白名单。
        if (TARGET_SLOW_SQL.equals(targetType)
                && slowSqlDao.hasActiveOptimizeByServiceAndHash(req.projectName, req.sqlHash)) {
            throw new IllegalStateException("该SQL已标记优化且生效中，白名单与优化互斥，不能申请白名单");
        }

        // v8：申请人=一级审批人——先建 PENDING_L1（l1_approver=本人），立即自动通过一审 → PENDING_L2。
        // 复用 l1Approve 跃迁，落库后记录与"被一审通过"的申请完全一致（l1_decision=APPROVE 等），审计可追溯。
        if (applicantIsL1) {
            String autoOpinion = "申请人本人为一级审批人，自动通过一审";
            long id = dao.create(
                    targetType, req.sqlHash, req.namedSql,
                    req.kindSource, req.sqlText, req.projectName, req.env,
                    applicant, req.applyReason,
                    applicant,                      // l1_approver = 申请人本人
                    req.sourceTable, req.sourceId);
            int n = dao.l1Approve(id, applicant, autoOpinion, req.l2Approver);
            if (n == 0) {
                throw new IllegalStateException("申请人为一级审批人，自动通过一审失败 id=" + id);
            }
            // 同步目标表 wl 字段到 PENDING_L2
            syncByTargetType(id, STATUS_PENDING_L2, false, targetType, req.sqlHash, req.namedSql, req.projectName);
            // 邮件直接通知二级审批人
            sendMailL1PassedNotifyL2(id, autoOpinion, req.l2Approver);
            return id;
        }

        long id = dao.create(
                targetType, req.sqlHash, req.namedSql,
                req.kindSource, req.sqlText, req.projectName, req.env,
                applicant, req.applyReason,
                req.l1Approver,
                req.sourceTable, req.sourceId);

        // 同步目标表的 wl 字段（PENDING_L1 状态，is_whitelist 不动）
        syncByTargetType(id, STATUS_PENDING_L1, false, targetType, req.sqlHash, req.namedSql, req.projectName);

        // 邮件通知 L1 审批人
        sendMailApplyPendingL1(id, targetType, req);

        return id;
    }

    /** 审批意见必填（通过/退回都要写意见，留审计痕迹）。 */
    private static void requireOpinion(String opinion) {
        if (opinion == null || opinion.isBlank()) {
            throw new IllegalArgumentException("审批意见不能为空");
        }
    }

    /** L1 通过：PENDING_L1 → PENDING_L2，指定 L2 审批人。 */
    public void l1Approve(long id, String currentUser, String opinion, String l2Approver) {
        ensureEnabled();
        requireOpinion(opinion);
        if (l2Approver == null || l2Approver.isBlank()) {
            throw new IllegalArgumentException("一级通过时必须指定二级审批人");
        }
        if (!isL2Approver(l2Approver)) {
            throw new IllegalArgumentException("二级审批人不在配置名单内: " + l2Approver);
        }
        int n = dao.l1Approve(id, currentUser, opinion, l2Approver);
        if (n == 0) {
            throw new IllegalStateException("无权或状态不符（当前必须为 PENDING_L1 且 L1 审批人匹配）");
        }
        Map<String, Object> app = dao.findById(id);
        syncByTargetType(id, STATUS_PENDING_L2, false,
                (String) app.get("target_type"),
                (String) app.get("sql_hash"),
                (String) app.get("named_sql"),
                (String) app.get("project_name"));

        // 邮件通知 L2 审批人
        sendMailL1PassedNotifyL2(id, opinion, l2Approver);
    }

    /** L1 退回：PENDING_L1 → REJECTED_L1。 */
    public void l1Reject(long id, String currentUser, String opinion) {
        ensureEnabled();
        requireOpinion(opinion);
        int n = dao.l1Reject(id, currentUser, opinion);
        if (n == 0) {
            throw new IllegalStateException("无权或状态不符（当前必须为 PENDING_L1 且 L1 审批人匹配）");
        }
        Map<String, Object> app = dao.findById(id);
        syncByTargetType(id, STATUS_REJECTED_L1, false,
                (String) app.get("target_type"),
                (String) app.get("sql_hash"),
                (String) app.get("named_sql"),
                (String) app.get("project_name"));

        // 邮件通知申请人
        sendMailRejectedNotifyApplicant(id, opinion);
    }

    /** L2 通过：PENDING_L2 → APPROVED（终态，写 is_whitelist=1）。 */
    public void l2Approve(long id, String currentUser, String opinion) {
        ensureEnabled();
        requireOpinion(opinion);
        int n = dao.l2Approve(id, currentUser, opinion);
        if (n == 0) {
            throw new IllegalStateException("无权或状态不符（当前必须为 PENDING_L2 且 L2 审批人匹配）");
        }
        Map<String, Object> app = dao.findById(id);
        // 终态：approved=true → is_whitelist 写 1
        syncByTargetType(id, STATUS_APPROVED, true,
                (String) app.get("target_type"),
                (String) app.get("sql_hash"),
                (String) app.get("named_sql"),
                (String) app.get("project_name"));

        // 邮件通知申请人
        sendMailApprovedNotifyApplicant(id, opinion, currentUser);
    }

    /** L2 退回：PENDING_L2 → PENDING_L1（带 L2 意见回 L1 再审）。 */
    public void l2Reject(long id, String currentUser, String opinion) {
        ensureEnabled();
        requireOpinion(opinion);
        int n = dao.l2Reject(id, currentUser, opinion);
        if (n == 0) {
            throw new IllegalStateException("无权或状态不符（当前必须为 PENDING_L2 且 L2 审批人匹配）");
        }
        Map<String, Object> app = dao.findById(id);
        syncByTargetType(id, STATUS_PENDING_L1, false,
                (String) app.get("target_type"),
                (String) app.get("sql_hash"),
                (String) app.get("named_sql"),
                (String) app.get("project_name"));
    }

    /** 取消：仅 PENDING_L1 / REJECTED_L1 且申请人本人。 */
    public void cancel(long id, String currentUser) {
        ensureEnabled();
        int n = dao.cancel(id, currentUser);
        if (n == 0) {
            throw new IllegalStateException(
                    "无权取消或状态不符（仅 PENDING_L1 / REJECTED_L1 且申请人本人可取消）");
        }
        // 清掉 item / pool 的 wl 冗余字段（不动 is_whitelist——审计上看，取消≠回收已下发的白名单）
        itemDao.clearWhitelistByAppId(id);
        poolDao.clearWhitelistByAppId(id);
        slowSqlDao.clearWhitelistByAppId(id);
    }

    /**
     * 中央同步：跃迁成功后把 item / pool / slow_sql 的 wl 冗余字段刷成最新状态。
     */
    private void syncByTargetType(long appId, String status, boolean approved,
                                  String targetType, String sqlHash, String namedSql,
                                  String projectName) {
        if (TARGET_SLOW_SQL.equals(targetType)) {
            // 慢SQL v2：按 (微服务=projectName, abstract_hash) 同步 dii_slow_sql 跨轮次同键行
            slowSqlDao.syncWhitelistByServiceAndHash(projectName, sqlHash, appId, status, approved);
        } else if (TARGET_NAMED_SQL.equals(targetType)) {
            // nsql 包含模式：所有同 named_sql 池行扩散
            poolDao.syncWhitelistByNamedSql(namedSql, appId, status, approved);
        } else {
            // HASH 模式：按 sql_hash 同步两张表
            itemDao.syncWhitelistByHash(sqlHash, appId, status, approved);
            poolDao.syncWhitelistByHash(sqlHash, appId, status, approved);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 入库继承（外部入口）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Item 入库时（{@code BatchInspectionRunner} 路径）调用：反查 sql_hash 匹配的活跃申请，
     * 若有则同步当前 item 的 wl 字段；终态 APPROVED 写 is_whitelist=1。
     */
    public void inheritOnItemInsert(long itemId, String sqlHash) {
        if (sqlHash == null) return;
        Map<String, Object> app = dao.findLatestActiveBySqlHash(sqlHash);
        if (app == null) return;
        Long appId = ((Number) app.get("id")).longValue();
        String status = String.valueOf(app.get("status"));
        itemDao.inheritWhitelistOnInsert(itemId, sqlHash, appId, status);
    }

    /**
     * 池行 upsert 时调用：先按 sql_hash 查 HASH 类型，未命中再按 named_sql 查 NAMED_SQL 类型。
     * <p>不需要 pool 行的具体 id——直接通过 sql_hash / named_sql 批量 UPDATE 匹配行。
     * Upsert 是「保留时间更晚的代表」语义，配合此处的批量同步可处理 INSERT 与 UPDATE 两种情况。
     */
    public void inheritOnPoolUpsert(String sqlHash, String namedSql) {
        Map<String, Object> app = dao.findLatestActiveBySqlHash(sqlHash);
        if (app == null) {
            app = dao.findLatestActiveByNamedSql(namedSql);
        }
        if (app == null) return;
        Long appId = ((Number) app.get("id")).longValue();
        String status = String.valueOf(app.get("status"));
        boolean approved = STATUS_APPROVED.equals(status);
        if (TARGET_NAMED_SQL.equals(String.valueOf(app.get("target_type")))) {
            poolDao.syncWhitelistByNamedSql(namedSql, appId, status, approved);
        } else {
            poolDao.syncWhitelistByHash(sqlHash, appId, status, approved);
        }
    }

    /**
     * 慢SQL **批量**导入后的白名单继承（避免 N+1）：
     * 一次取全部活跃 SLOW_SQL 申请到内存（数量有限）→ 仅对「本批键 ∩ 申请」的少数命中项做同步。
     * 把「逐键一次 SELECT」压成「1 次 SELECT + 命中数次 UPDATE」。
     * <p>v2：键 =（微服务 + "\n" + abstract_hash），每键取最高 id 的活跃申请。
     */
    public void inheritOnSlowSqlImportBatch(Collection<String> svcHashKeys) {
        if (svcHashKeys == null || svcHashKeys.isEmpty()) return;
        List<Map<String, Object>> apps = dao.listActiveByTargetType(TARGET_SLOW_SQL);
        if (apps == null || apps.isEmpty()) return;
        // v2：键 = 微服务 + "\n" + abstract_hash（与导入聚合键一致）
        Set<String> batch = (svcHashKeys instanceof Set)
                ? (Set<String>) svcHashKeys : new HashSet<>(svcHashKeys);
        // 历史无微服务的申请（project_name 空）按 hash 兜底匹配
        Set<String> hashesInBatch = new HashSet<>();
        for (String key : batch) {
            int nl = key.indexOf('\n');
            if (nl >= 0) hashesInBatch.add(key.substring(nl + 1));
        }
        // apps 已按 id 升序 → 命中用 map put 覆盖，最终留下最高 id（=最新活跃）那条
        Map<String, Map<String, Object>> matched = new HashMap<>();
        for (Map<String, Object> a : apps) {
            String h = (String) a.get("sql_hash");
            if (h == null) continue;
            String svc = (String) a.get("project_name");
            if (svc != null && !svc.isBlank()) {
                String key = svc.trim() + "\n" + h;
                if (batch.contains(key)) matched.put(key, a);
            } else if (hashesInBatch.contains(h)) {
                matched.put("\n" + h, a);   // 兼容旧申请：hash 级（跨微服务）
            }
        }
        for (Map<String, Object> a : matched.values()) {
            String h = (String) a.get("sql_hash");
            String svc = (String) a.get("project_name");
            long appId = ((Number) a.get("id")).longValue();
            String status = String.valueOf(a.get("status"));
            slowSqlDao.syncWhitelistByServiceAndHash(svc, h, appId, status, STATUS_APPROVED.equals(status));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 简单查询
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> findById(long id) { return dao.findById(id); }

    public List<Map<String, Object>> listMyTodo(String username, String role, String status,
                                                int limit, int offset) {
        return dao.listMyTodo(username, role, status, limit, offset);
    }

    /** 当前用户的「该我审批」条数（L1 PENDING_L1 + L2 PENDING_L2 之和）。 */
    public long countMyPending(String username) {
        return dao.countMyPending(username);
    }

    /** 「该我审批」按类拆分：{total, slowSql, sqlInspect}（铃铛分流用）。 */
    public Map<String, Object> countMyPendingByCategory(String username) {
        return dao.countMyPendingByCategory(username);
    }

    /**
     * 巡检短路用：判断 sql_hash 是否已 APPROVED 终态。
     * <p>批量巡检每条 SQL 入口都要查一次——加缓存意义不大（sql_hash 多样），让 DB 索引（{@code idx_sql_hash}）顶住。
     * @return true=已 APPROVED，调用方应跳过 EXPLAIN + LLM
     */
    public boolean isHashApproved(String sqlHash) {
        if (sqlHash == null || sqlHash.isEmpty()) return false;
        Map<String, Object> app = dao.findLatestActiveBySqlHash(sqlHash);
        return app != null && STATUS_APPROVED.equals(String.valueOf(app.get("status")));
    }

    /**
     * 巡检短路用：判断 named_sql 是否走「包含模式」并已 APPROVED。
     * 池行可能同 named_sql 不同 sql_hash，APPROVED 的 NAMED_SQL 类应用对所有同 named_sql 行生效。
     */
    public boolean isNamedSqlApproved(String namedSql) {
        if (namedSql == null || namedSql.isEmpty()) return false;
        Map<String, Object> app = dao.findLatestActiveByNamedSql(namedSql);
        return app != null && STATUS_APPROVED.equals(String.valueOf(app.get("status")));
    }

    public List<DaoIndexAnalysisProperties.Approver> getL1Approvers() {
        return props.getWhitelist().getL1Approvers();
    }
    public List<DaoIndexAnalysisProperties.Approver> getL2Approvers() {
        return props.getWhitelist().getL2Approvers();
    }

    public boolean isEnabled() {
        return props.getWhitelist().isEnabled();
    }

    private void ensureEnabled() {
        if (!props.getWhitelist().isEnabled()) {
            throw new IllegalStateException("白名单审批工作流已禁用（yml: whitelist.enabled=false）");
        }
    }

    private boolean isL1Approver(String username) {
        return props.getWhitelist().getL1Approvers().stream()
                .anyMatch(a -> a.getUsername() != null && a.getUsername().equals(username));
    }
    private boolean isL2Approver(String username) {
        return props.getWhitelist().getL2Approvers().stream()
                .anyMatch(a -> a.getUsername() != null && a.getUsername().equals(username));
    }

    private static void validate(ApplyRequest req) {
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        if (req.applicant == null || req.applicant.isBlank()) {
            throw new IllegalArgumentException("申请人 (applicant) 不能为空");
        }
        if (req.applyReason == null || req.applyReason.isBlank()) {
            throw new IllegalArgumentException("申请理由不能为空");
        }
        // v8：审批人必填校验移到 apply()——申请人本人是一级审批人时走"直接二审"分支，
        // 此时必填的是 l2Approver 而非 l1Approver，故这里不再统一强校验 l1Approver。
        if (req.sourceTable == null
                || !(req.sourceTable.equals("item") || req.sourceTable.equals("sql_pool")
                     || req.sourceTable.equals("slow_sql"))) {
            throw new IllegalArgumentException("source_table 必须是 'item' / 'sql_pool' / 'slow_sql'");
        }
    }
    private static String applicantRequired(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("申请人不能为空");
        return s.trim();
    }

    /** 申请请求 DTO。前端 POST 时序列化为 JSON 投递。 */
    public static class ApplyRequest {
        public String sqlHash;
        public String namedSql;
        public String sqlText;
        public String projectName;
        public String env;
        public String kindSource;        // odb / nsql
        public boolean includeNamedSql;  // nsql 类型才有意义
        public boolean slowSql;          // true=慢SQL申请(target_type=SLOW_SQL，按 sql_hash=abstract_hash 匹配)
        public String applicant;
        public String applyReason;
        public String l1Approver;
        public String l2Approver;        // v8：申请人本人是一级审批人时，直接指定二级审批人（跳过一审）
        public String sourceTable;       // item / sql_pool / slow_sql
        public long sourceId;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 邮件通知（异步，失败不影响主流程）
    // ─────────────────────────────────────────────────────────────────────────

    /** 业务事件 1：申请提交 → 通知 L1 审批人 */
    private void sendMailApplyPendingL1(long appId, String targetType, ApplyRequest req) {
        if (mailService == null) return;
        String l1Email = resolveUserEmail(req.l1Approver);
        if (l1Email == null) return;
        Map<String, Object> vars = new HashMap<>();
        vars.put("appId", appId);
        vars.put("applicantName", req.applicant);
        vars.put("l1ApproverName", req.l1Approver);
        vars.put("env", req.env);
        vars.put("projectName", req.projectName);
        vars.put("targetType", targetType);
        vars.put("sqlSnippet", truncate(req.sqlText, 200));
        vars.put("applyReason", req.applyReason);
        vars.put("applyTime", nowStr());
        mailService.sendWhitelistMail(
                java.util.Collections.singletonList(l1Email),
                WhitelistMailTemplate.APPLY_PENDING_L1_SUBJECT,
                WhitelistMailTemplate.APPLY_PENDING_L1_BODY,
                vars);
    }

    /** 业务事件 2：L1 通过 → 通知 L2 审批人 */
    private void sendMailL1PassedNotifyL2(long appId, String l1Opinion, String l2Approver) {
        if (mailService == null) return;
        String l2Email = resolveUserEmail(l2Approver);
        if (l2Email == null) return;
        Map<String, Object> app = dao.findById(appId);
        Map<String, Object> vars = new HashMap<>();
        vars.put("appId", appId);
        vars.put("applicantName", str(app, "applicant"));
        vars.put("l1ApproverName", str(app, "l1_approver"));
        vars.put("l1Opinion", l1Opinion);
        vars.put("l2ApproverName", l2Approver);
        vars.put("env", str(app, "env"));
        vars.put("projectName", str(app, "project_name"));
        vars.put("targetType", str(app, "target_type"));
        vars.put("sqlSnippet", truncate(str(app, "sql_text"), 200));
        vars.put("applyReason", str(app, "apply_reason"));
        mailService.sendWhitelistMail(
                java.util.Collections.singletonList(l2Email),
                WhitelistMailTemplate.L1_PASSED_NOTIFY_L2_SUBJECT,
                WhitelistMailTemplate.L1_PASSED_NOTIFY_L2_BODY,
                vars);
    }

    /** 业务事件 3：终审通过 → 通知申请人 */
    private void sendMailApprovedNotifyApplicant(long appId, String l2Opinion, String l2Approver) {
        if (mailService == null) return;
        Map<String, Object> app = dao.findById(appId);
        String applicant = str(app, "applicant");
        String email = resolveUserEmail(applicant);
        if (email == null) return;
        Map<String, Object> vars = new HashMap<>();
        vars.put("appId", appId);
        vars.put("applicantName", applicant);
        vars.put("env", str(app, "env"));
        vars.put("projectName", str(app, "project_name"));
        vars.put("targetType", str(app, "target_type"));
        vars.put("sqlSnippet", truncate(str(app, "sql_text"), 200));
        vars.put("l2ApproverName", l2Approver);
        vars.put("l2Opinion", l2Opinion);
        vars.put("approveTime", nowStr());
        mailService.sendWhitelistMail(
                java.util.Collections.singletonList(email),
                WhitelistMailTemplate.APPROVED_NOTIFY_APPLICANT_SUBJECT,
                WhitelistMailTemplate.APPROVED_NOTIFY_APPLICANT_BODY,
                vars);
    }

    /** 业务事件 4：L1 退回 → 通知申请人 */
    private void sendMailRejectedNotifyApplicant(long appId, String l1Opinion) {
        if (mailService == null) return;
        Map<String, Object> app = dao.findById(appId);
        String applicant = str(app, "applicant");
        String email = resolveUserEmail(applicant);
        if (email == null) return;
        Map<String, Object> vars = new HashMap<>();
        vars.put("appId", appId);
        vars.put("applicantName", applicant);
        vars.put("env", str(app, "env"));
        vars.put("projectName", str(app, "project_name"));
        vars.put("targetType", str(app, "target_type"));
        vars.put("sqlSnippet", truncate(str(app, "sql_text"), 200));
        vars.put("applyReason", str(app, "apply_reason"));
        vars.put("l1ApproverName", str(app, "l1_approver"));
        vars.put("l1Opinion", l1Opinion);
        mailService.sendWhitelistMail(
                java.util.Collections.singletonList(email),
                WhitelistMailTemplate.L1_REJECTED_NOTIFY_APPLICANT_SUBJECT,
                WhitelistMailTemplate.L1_REJECTED_NOTIFY_APPLICANT_BODY,
                vars);
    }

    /** 从 ccbs_ai_sys_user 查 email；空/null 时返回 null 让调用方跳过发送。 */
    private String resolveUserEmail(String username) {
        if (username == null || username.isBlank()) return null;
        try {
            var u = sysUserDao.findByUsername(username.trim());
            if (u == null) return null;
            String e = u.getEmail();
            return (e == null || e.isBlank()) ? null : e.trim();
        } catch (Exception ex) {
            log.warn("[whitelist-mail] 查 email 失败 user={} : {}", username, ex.getMessage());
            return null;
        }
    }

    private static String str(Map<String, Object> m, String key) {
        if (m == null) return "";
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String nowStr() {
        return TIME_FMT.format(new java.util.Date());
    }
}
