package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiAnalysisItemDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiWhitelistApplicationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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
     * 申请白名单（PENDING_L1 初态）。
     *
     * @param applicant       申请人 username
     * @param includeNamedSql nsql 是否勾选"包含 nsql 原始语句"——true=NAMED_SQL 作用域；
     *                        false=HASH 作用域
     * @param row             触发申请那一行的 item/pool 字段子集
     */
    public long apply(ApplyRequest req) {
        ensureEnabled();
        validate(req);

        // 校验所选审批人确实在 yml 配置名单内（防止前端伪造任意 username）
        if (!isL1Approver(req.l1Approver)) {
            throw new IllegalArgumentException("一级审批人不在配置名单内: " + req.l1Approver);
        }

        String targetType;
        if (req.slowSql) {
            targetType = TARGET_SLOW_SQL;
            if (req.sqlHash == null || req.sqlHash.isBlank()) {
                throw new IllegalArgumentException("SLOW_SQL 模式必须提供 sql_hash(abstract_hash)");
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
        Map<String, Object> existing;
        if (TARGET_SLOW_SQL.equals(targetType)) {
            existing = dao.findLatestActiveBySlowHash(req.sqlHash);
        } else if (TARGET_HASH.equals(targetType)) {
            existing = dao.findLatestActiveBySqlHash(req.sqlHash);
        } else {
            existing = dao.findLatestActiveByNamedSql(req.namedSql);
        }
        if (existing != null && !STATUS_CANCELLED.equals(String.valueOf(existing.get("status")))) {
            throw new IllegalStateException(
                    "已存在活跃白名单申请 id=" + existing.get("id") + " status=" + existing.get("status"));
        }

        long id = dao.create(
                targetType, req.sqlHash, req.namedSql,
                req.kindSource, req.sqlText, req.projectName, req.env,
                applicantRequired(req.applicant), req.applyReason,
                req.l1Approver,
                req.sourceTable, req.sourceId);

        // 同步目标表的 wl 字段（PENDING_L1 状态，is_whitelist 不动）
        syncByTargetType(id, STATUS_PENDING_L1, false, targetType, req.sqlHash, req.namedSql);
        return id;
    }

    /** L1 通过：PENDING_L1 → PENDING_L2，指定 L2 审批人。 */
    public void l1Approve(long id, String currentUser, String opinion, String l2Approver) {
        ensureEnabled();
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
                (String) app.get("named_sql"));
    }

    /** L1 退回：PENDING_L1 → REJECTED_L1。 */
    public void l1Reject(long id, String currentUser, String opinion) {
        ensureEnabled();
        int n = dao.l1Reject(id, currentUser, opinion);
        if (n == 0) {
            throw new IllegalStateException("无权或状态不符（当前必须为 PENDING_L1 且 L1 审批人匹配）");
        }
        Map<String, Object> app = dao.findById(id);
        syncByTargetType(id, STATUS_REJECTED_L1, false,
                (String) app.get("target_type"),
                (String) app.get("sql_hash"),
                (String) app.get("named_sql"));
    }

    /** L2 通过：PENDING_L2 → APPROVED（终态，写 is_whitelist=1）。 */
    public void l2Approve(long id, String currentUser, String opinion) {
        ensureEnabled();
        int n = dao.l2Approve(id, currentUser, opinion);
        if (n == 0) {
            throw new IllegalStateException("无权或状态不符（当前必须为 PENDING_L2 且 L2 审批人匹配）");
        }
        Map<String, Object> app = dao.findById(id);
        // 终态：approved=true → is_whitelist 写 1
        syncByTargetType(id, STATUS_APPROVED, true,
                (String) app.get("target_type"),
                (String) app.get("sql_hash"),
                (String) app.get("named_sql"));
    }

    /** L2 退回：PENDING_L2 → PENDING_L1（带 L2 意见回 L1 再审）。 */
    public void l2Reject(long id, String currentUser, String opinion) {
        ensureEnabled();
        int n = dao.l2Reject(id, currentUser, opinion);
        if (n == 0) {
            throw new IllegalStateException("无权或状态不符（当前必须为 PENDING_L2 且 L2 审批人匹配）");
        }
        Map<String, Object> app = dao.findById(id);
        syncByTargetType(id, STATUS_PENDING_L1, false,
                (String) app.get("target_type"),
                (String) app.get("sql_hash"),
                (String) app.get("named_sql"));
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
                                  String targetType, String sqlHash, String namedSql) {
        if (TARGET_SLOW_SQL.equals(targetType)) {
            // 慢SQL：按 abstract_hash 同步 dii_slow_sql 全部同抽象SQL行
            slowSqlDao.syncWhitelistByHash(sqlHash, appId, status, approved);
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
     * 慢SQL导入后调用：按 abstract_hash 反查 SLOW_SQL 类申请（任意活跃状态），
     * 命中则把该抽象SQL全部行同步成申请当前状态；APPROVED→is_whitelist=1。
     */
    public void inheritOnSlowSqlImport(String abstractHash) {
        if (abstractHash == null || abstractHash.isEmpty()) return;
        Map<String, Object> app = dao.findLatestActiveBySlowHash(abstractHash);
        if (app == null) return;
        Long appId = ((Number) app.get("id")).longValue();
        String status = String.valueOf(app.get("status"));
        boolean approved = STATUS_APPROVED.equals(status);
        slowSqlDao.syncWhitelistByHash(abstractHash, appId, status, approved);
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
        if (req.l1Approver == null || req.l1Approver.isBlank()) {
            throw new IllegalArgumentException("一级审批人 (l1Approver) 不能为空");
        }
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
        public String sourceTable;       // item / sql_pool / slow_sql
        public long sourceId;
    }
}
