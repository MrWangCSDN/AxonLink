package com.axonlink.ai.code.service;

import com.axonlink.ai.code.persistence.CodeDashboardDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大屏读侧组装。只读小 summary 表（已由每日聚合物化），不碰事实表。
 * 一次 overview 返回首屏多组件所需数据，减少前端往返。
 */
@Service
public class CodeDashboardService {

    @Autowired
    private CodeDashboardDao dao;

    public List<Map<String, Object>> repos() {
        return dao.listRepos();
    }

    public List<Map<String, Object>> authors(long repoId, int limit) {
        return dao.authorRanking(repoId, limit);
    }

    public List<Map<String, Object>> tx(long repoId, int limit) {
        return dao.txByPersonType(repoId, limit);
    }

    /**
     * 人员维度统计：每人总代码量 + 归属交易码列表（来自 flowtrans XML blame）。
     * tx_ids = 逗号分隔交易码，为空时表示该人未参与任何 flowtrans XML 文件。
     */
    public List<Map<String, Object>> personStats(long repoId, int limit) {
        return dao.personStats(repoId, limit);
    }

    /** 指定 person_type 的人员统计（行员/厂商分榜）。 */
    public List<Map<String, Object>> personStatsByType(long repoId, String personType, int limit) {
        return dao.personStatsByType(repoId, personType, limit);
    }

    public List<Map<String, Object>> domainAuthors(long repoId, String domainKey, int limit) {
        return dao.domainAuthors(repoId, domainKey, limit);
    }

    /**
     * 项目级 按领域划分：每个领域一行，含 行员/厂商 拆分与占全仓比。
     * 由 code_domain_person_stat（领域×person_type）透视而来。
     */
    public List<Map<String, Object>> domains(long repoId) {
        List<Map<String, Object>> rows = dao.sumByDomain(repoId);
        Map<String, Map<String, Object>> byDomain = new LinkedHashMap<>();
        long repoTotal = 0;
        for (Map<String, Object> r : rows) {
            String dk = String.valueOf(r.get("domain_key"));
            long owned = lng(r.get("owned_lines"));
            long files = lng(r.get("file_count"));
            repoTotal += owned;
            Map<String, Object> d = byDomain.computeIfAbsent(dk, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domainKey", k);
                m.put("ownedLines", 0L);
                m.put("fileCount", 0L);
                m.put("staffOwned", 0L);
                m.put("vendorOwned", 0L);
                return m;
            });
            d.put("ownedLines", lng(d.get("ownedLines")) + owned);
            d.put("fileCount", lng(d.get("fileCount")) + files);
            String pt = String.valueOf(r.get("person_type"));
            if ("VENDOR".equals(pt)) {
                d.put("vendorOwned", lng(d.get("vendorOwned")) + owned);
            } else {
                d.put("staffOwned", lng(d.get("staffOwned")) + owned);
            }
            Object st = r.get("snapshot_time");
            if (st != null) {
                d.put("snapshotTime", st);
            }
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>(byDomain.values());
        for (Map<String, Object> d : out) {
            long owned = lng(d.get("ownedLines"));
            double share = repoTotal == 0 ? 0.0
                    : Math.round(owned * 1000.0 / repoTotal) / 10.0;
            d.put("sharePct", share);
        }
        out.sort((x, y) -> Long.compare(lng(y.get("ownedLines")), lng(x.get("ownedLines"))));
        return out;
    }

    /** 大屏首屏聚合：行员/厂商总览(含占比) + 作者 Top + 交易 Top。 */
    public Map<String, Object> overview(long repoId) {
        List<Map<String, Object>> byType = dao.sumByPersonType(repoId);

        long totalOwned = 0;
        Object snapshotTime = null;
        for (Map<String, Object> row : byType) {
            totalOwned += lng(row.get("owned_lines"));
            Object st = row.get("snapshot_time");
            if (st != null) {
                snapshotTime = st;
            }
        }
        for (Map<String, Object> row : byType) {
            long owned = lng(row.get("owned_lines"));
            double share = totalOwned == 0 ? 0.0
                    : Math.round(owned * 1000.0 / totalOwned) / 10.0;
            row.put("share_pct", share);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repoId", repoId);
        out.put("totalOwnedLines", totalOwned);
        out.put("byType", byType);
        out.put("topPersons", dao.personStats(repoId, 20));   // 人员维度：总行数 + 交易归属
        out.put("byDomain", domains(repoId));
        out.put("topTx", dao.txByPersonType(repoId, 20));     // 交易维度：各交易行员/厂商占比
        out.put("snapshotTime", snapshotTime);
        return out;
    }

    private static long lng(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
