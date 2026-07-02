package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlDao;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSlowSqlOptimizeDao;
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

    public SlowSqlOptimizeService(DiiSlowSqlOptimizeDao optimizeDao, DiiSlowSqlDao slowSqlDao) {
        this.optimizeDao = optimizeDao;
        this.slowSqlDao = slowSqlDao;
    }

    /**
     * 标记「已优化」：锚定 R0 = 该 SQL 当前最新出现的轮次；写真身并同步冗余列。
     * @return 锚定轮次 R0
     * @throws IllegalArgumentException 该 SQL 不在慢SQL池中（无从锚定）
     */
    public String mark(String serviceName, String abstractHash, String optimizedBy) {
        String r0 = slowSqlDao.maxRoundByServiceAndHash(serviceName, abstractHash);
        if (r0 == null) throw new IllegalArgumentException("该SQL在慢SQL池中不存在，无法标记已优化");
        LocalDateTime now = LocalDateTime.now();
        optimizeDao.upsertOptimized(serviceName, abstractHash, r0, optimizedBy, now);
        slowSqlDao.syncOptimizeByServiceAndHash(
                serviceName, abstractHash, DiiSlowSqlOptimizeDao.STATUS_OPTIMIZED, r0, null);
        return r0;
    }

    /**
     * 取消「已优化」：先清冗余列、后删真身。
     * <p>此序保证：若删真身失败，真身仍在（OPTIMIZED），下次导入会从真身重新同步冗余列，
     * 收敛到一致的 OPTIMIZED 态（可见、可重试），不会留下无真身对应的孤儿冗余列。
     * <p>取消是破坏性操作（无审批自助），故留一条审计日志（谁取消、原打标人），便于追溯。
     */
    public void unmark(String serviceName, String abstractHash, String unmarkedBy) {
        Map<String, Object> existing = optimizeDao.findByKey(serviceName, abstractHash);
        slowSqlDao.clearOptimizeByServiceAndHash(serviceName, abstractHash);
        optimizeDao.delete(serviceName, abstractHash);
        if (existing != null) {
            log.info("[slow-sql-optimize] 取消已优化：操作人={} 原打标人={} (svc={}, hash={})",
                    unmarkedBy, existing.get("optimized_by"), serviceName, abstractHash);
        }
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
