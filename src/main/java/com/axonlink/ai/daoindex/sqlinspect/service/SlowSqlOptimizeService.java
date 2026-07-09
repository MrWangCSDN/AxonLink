package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlOptimizeDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiWhitelistApplicationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 慢SQL「已优化」自助标记 + 跨轮次「优化未生效」检测。
 * <p>真身存 {@link DiiSlowSqlOptimizeDao}（跨轮次身份键 service+hash），冗余态盖到
 * {@link DiiSlowSqlDao} 的行供列表秒渲染。无审批。轮次比较用字符串序（与 repeat_rounds 同口径）。
 * <p><b>一致性</b>：结果库无事务管理器（与白名单 service 同口径，不套 {@code @Transactional}）。
 * 真身表为唯一事实源，冗余列靠「导入时重新同步」（步骤⑤）做最终一致。为此写序刻意如下：
 * mark 先写真身后同步冗余；unmark 先清冗余后删真身——保证任一步失败时真身都能在下次导入被重新同步，
 * 不留孤儿冗余列。
 */
@Service
public class SlowSqlOptimizeService {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlOptimizeService.class);

    private final DiiSlowSqlOptimizeDao optimizeDao;
    private final DiiSlowSqlDao slowSqlDao;
    private final DiiWhitelistApplicationDao whitelistDao;

    public SlowSqlOptimizeService(DiiSlowSqlOptimizeDao optimizeDao, DiiSlowSqlDao slowSqlDao,
                                  DiiWhitelistApplicationDao whitelistDao) {
        this.optimizeDao = optimizeDao;
        this.slowSqlDao = slowSqlDao;
        this.whitelistDao = whitelistDao;
    }

    /**
     * 标记「已优化」：锚定 R0 = 该 SQL 当前最新出现的轮次；写真身并同步冗余列。
     * 记录优化人工号/姓名 + 优化内容；可对同一 SQL 反复标记（含 REGRESSED 后重新标记，内容会更新）。
     * @param optimizedBy     优化人工号（当前登录用户，取不到回退登录名）
     * @param optimizedByName 优化人姓名（按工号解析的快照）
     * @param note            优化内容（≤200字，必填由 controller 校验）
     * @return 锚定轮次 R0
     * @throws IllegalArgumentException 该 SQL 不在慢SQL池中（无从锚定）
     */
    public String mark(String serviceName, String abstractHash,
                       String optimizedBy, String optimizedByName, String note) {
        String r0 = slowSqlDao.maxRoundByServiceAndHash(serviceName, abstractHash);
        if (r0 == null) throw new IllegalArgumentException("该SQL在慢SQL池中不存在，无法标记已优化");
        Map<String, Object> existing = optimizeDao.findByKey(serviceName, abstractHash);
        // 新一次尝试 = 首次打标 / 未生效(REGRESSED)后重新打标；仍是 OPTIMIZED 的编辑不算新尝试。
        boolean newAttempt = existing == null
                || DiiSlowSqlOptimizeDao.STATUS_REGRESSED.equals(existing.get("status"));
        // 互斥：白名单与优化二选一——「新尝试」在白名单流程中被拦（未生效后若已转投白名单，优化路线关闭）；
        // 仍是 OPTIMIZED 的编辑放行（兼容互斥规则上线前的存量双态行）。
        if (newAttempt && slowSqlDao.hasWhitelistByServiceAndHash(serviceName, abstractHash)) {
            throw new IllegalArgumentException("该SQL已在白名单流程中（申请/审批/已通过），白名单与优化互斥，不能标记已优化");
        }
        LocalDateTime now = LocalDateTime.now();
        optimizeDao.upsertOptimized(serviceName, abstractHash, r0, optimizedBy, optimizedByName, note, now);
        slowSqlDao.syncOptimizeByServiceAndHash(
                serviceName, abstractHash, DiiSlowSqlOptimizeDao.STATUS_OPTIMIZED, r0, null);
        // 优化路线（追加不删）：新尝试 → 追加一条；OPTIMIZED 编辑 → 更新最新一条（保留原 R0 与失败痕迹）。
        if (newAttempt) {
            optimizeDao.appendHistory(serviceName, abstractHash, r0, optimizedBy, optimizedByName, note, now);
        } else {
            optimizeDao.updateLatestHistory(serviceName, abstractHash, optimizedBy, optimizedByName, note, now);
        }
        return r0;
    }

    /** 优化路线：该 (微服务, 抽象SQL) 的全部优化尝试（升序=第1次→最新），悬浮弹层用。 */
    public List<Map<String, Object>> listHistory(String serviceName, String abstractHash) {
        return optimizeDao.listHistory(serviceName, abstractHash);
    }

    /**
     * 统一处理路径：优化侧（标记/撤销，未生效作为标记事件的标签）+ 白名单侧（申请/审批/退回/撤回）
     * 按时间升序合并成一条时间线——"优化→未生效→撤销→申请白名单→…"一眼看全。
     * 事件字段：kind(OPTIMIZE/WHITELIST)、action、actor、actorName、note、at、
     * 及优化标记事件附带 round / reappearedRound。
     */
    public List<Map<String, Object>> journey(String serviceName, String abstractHash) {
        List<Map<String, Object>> events = new java.util.ArrayList<>();
        for (Map<String, Object> h : optimizeDao.listHistory(serviceName, abstractHash)) {
            Map<String, Object> mark = new java.util.LinkedHashMap<>();
            mark.put("kind", "OPTIMIZE");
            mark.put("action", "MARK");
            mark.put("actor", h.get("optimized_by"));
            mark.put("actorName", h.get("optimized_by_name"));
            mark.put("note", h.get("optimize_note"));
            mark.put("round", h.get("optimized_round"));
            mark.put("reappearedRound", h.get("reappeared_round"));
            mark.put("at", h.get("optimized_at"));
            events.add(mark);
            if (h.get("revoked_at") != null) {
                Map<String, Object> revoke = new java.util.LinkedHashMap<>();
                revoke.put("kind", "OPTIMIZE");
                revoke.put("action", "REVOKE");
                revoke.put("actor", h.get("revoked_by"));
                revoke.put("actorName", h.get("revoked_by_name"));
                revoke.put("at", h.get("revoked_at"));
                events.add(revoke);
            }
        }
        for (Map<String, Object> f : whitelistDao.listFlowBySlowKey(abstractHash, serviceName)) {
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("kind", "WHITELIST");
            e.put("action", f.get("action"));
            e.put("actor", f.get("actor"));
            e.put("actorName", f.get("actor_name"));
            e.put("note", f.get("opinion"));
            e.put("at", f.get("created_at"));
            events.add(e);
        }
        // 时间均为 "yyyy-MM-dd HH:mm:ss[.f]" 字符串，字典序=时间序；稳定排序保持同刻事件的来源顺序
        events.sort(java.util.Comparator.comparing(e2 -> String.valueOf(e2.get("at"))));
        return events;
    }

    /**
     * 撤销优化：互斥的出口——生效中(OPTIMIZED)的 SQL 想走白名单必须先撤销。
     * 撤销人(工号/姓名)与时间盖到最新路线条目（审计留痕，路线不删），随后清冗余列、删真身，
     * 行回「未处理」→ 白名单可申请。写序：先盖章/清冗余、后删真身——任一步失败时真身仍在，
     * 下次导入会重新同步冗余列，收敛到一致态（与非事务一致性设计同口径）。
     * @throws IllegalArgumentException 该 SQL 未标记优化
     */
    public void revoke(String serviceName, String abstractHash, String revokedBy, String revokedByName) {
        Map<String, Object> existing = optimizeDao.findByKey(serviceName, abstractHash);
        if (existing == null) throw new IllegalArgumentException("该SQL未标记优化，无需撤销");
        LocalDateTime now = LocalDateTime.now();
        optimizeDao.stampLatestHistoryRevoked(serviceName, abstractHash, revokedBy, revokedByName, now);
        slowSqlDao.clearOptimizeByServiceAndHash(serviceName, abstractHash);
        optimizeDao.delete(serviceName, abstractHash);
        log.info("[slow-sql-optimize] 撤销优化：撤销人={}({}) 原打标人={} (svc={}, hash={})",
                revokedByName, revokedBy, existing.get("optimized_by"), serviceName, abstractHash);
    }

    /**
     * 导入后钩子（步骤⑤）：本轮出现的键 ∩ 真身表 → 对 {@code round > optimized_round} 的
     * OPTIMIZED 记录翻 REGRESSED（记最新 reappeared_round），并把优化态重新盖到本轮新插入的行。
     * @param svcHashKeys 本轮出现的所有键（service + "\n" + abstract_hash），即导入聚合 Map 的 keySet
     * @param round       本轮轮次
     * @return 本轮新判「优化未生效」的条数（OPTIMIZED→REGRESSED 的迁移数）
     */
    public int inheritAndDetectReappearOnImport(Collection<String> svcHashKeys, String round) {
        if (svcHashKeys == null || svcHashKeys.isEmpty()) return 0;
        Set<String> batch = (svcHashKeys instanceof Set) ? (Set<String>) svcHashKeys : new HashSet<>(svcHashKeys);
        List<Map<String, Object>> records = optimizeDao.listAll();
        if (records.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        int reappearedHit = 0;
        for (Map<String, Object> rec : records) {
            String svc = (String) rec.get("service_name");
            String hash = (String) rec.get("abstract_hash");
            if (!batch.contains(svc + "\n" + hash)) continue;   // 本轮未出现，不动
            String status = (String) rec.get("status");
            String r0 = (String) rec.get("optimized_round");
            String reappeared = (String) rec.get("reappeared_round");
            // 又出现在更晚轮次 → 翻/更新 REGRESSED（DAO 内含 round > r0 守卫）
            if (r0 != null && round.compareTo(r0) > 0) {
                optimizeDao.flagReappeared(svc, hash, round, now);
                // 优化路线：把最新一次尝试回填 reappeared_round——"这次优化失败"永久留痕（重标不覆盖）
                optimizeDao.stampLatestHistoryReappeared(svc, hash, round);
                if (DiiSlowSqlOptimizeDao.STATUS_OPTIMIZED.equals(status)) reappearedHit++;
                status = DiiSlowSqlOptimizeDao.STATUS_REGRESSED;
                reappeared = (reappeared == null || round.compareTo(reappeared) > 0) ? round : reappeared;
            }
            // 重新继承冗余列到本轮新插入行（及跨轮次所有行）
            slowSqlDao.syncOptimizeByServiceAndHash(svc, hash, status, r0, reappeared);
        }
        if (reappearedHit > 0) {
            log.info("[slow-sql-import] 轮次 {} 检测到 {} 条已优化SQL又出现（优化未生效）", round, reappearedHit);
        }
        return reappearedHit;
    }
}
