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

    // 7 天趋势走独立的每日快照 DAO（queryTrend 在此）
    @Autowired
    private com.axonlink.ai.code.persistence.CodeRepoDailyStatDao dailyStatDao;

    public List<Map<String, Object>> repos() {
        return dao.listRepos();
    }

    public List<Map<String, Object>> authors(long repoId, int limit) {
        return dao.authorRanking(repoId, limit);
    }

    public List<Map<String, Object>> tx(long repoId, int limit) {
        return dao.txByPersonType(repoId, "STAFF", limit);
    }

    /** 指定 person_type 的交易排行。 */
    public List<Map<String, Object>> txByPersonType(long repoId, String personType, int limit) {
        return dao.txByPersonType(repoId, personType, limit);
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

    public List<Map<String, Object>> trend(long repoId, int days) {
        return dailyStatDao.queryTrend(repoId, days);
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

    /**
     * 大屏首屏聚合：行员/厂商总览(含占比) + 作者 Top + 交易 Top。
     *
     * <p>{@code repoId == 0}（或负数）触发 <b>ALL 汇总模式</b>：
     * 跨所有 enabled 仓库聚合——每个仓库的统计行 SUM by (person_type / domain / tx_id)，
     * 人员维度按 (author_email) 跨仓 dedup + 累加 owned_lines / tx_ids 合并。
     * <p>正常 repoId 走单仓查询（沿用 V1 行为）。
     */
    public Map<String, Object> overview(long repoId) {
        if (repoId <= 0) {
            return overviewAll();
        }
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
        out.put("topAuthors", dao.authorRankingByType(repoId, "STAFF", 10));
        out.put("topPersons", dao.personStatsByType(repoId, "STAFF", 20));
        out.put("byDomain", domains(repoId));
        out.put("topTx", dao.txByPersonType(repoId, "STAFF", 20));
        out.put("snapshotTime", snapshotTime);
        return out;
    }

    /**
     * ALL 汇总：跨所有 enabled 仓库聚合首屏数据。
     * <p>实现策略：对每个仓库分别调单仓 overview，再合并——避免改 DAO；
     * 仓库数典型 < 30，合并耗时 < 200ms。
     * <p>合并规则：
     * <ul>
     *   <li>totalOwnedLines = sum</li>
     *   <li>byType: 按 person_type 累加 owned_lines / author_count；share_pct 重算</li>
     *   <li>byDomain: 按 domainKey 累加 staffOwned/vendorOwned/ownedLines；sharePct 重算</li>
     *   <li>topPersons: 按 author_email 跨仓 dedup + 累加；tx_ids 取并集</li>
     *   <li>topTx: 按 tx_id 累加（每仓 STAFF/VENDOR 各自合并）</li>
     *   <li>snapshotTime: 取最大值</li>
     * </ul>
     */
    private Map<String, Object> overviewAll() {
        List<Map<String, Object>> repos = dao.listRepos();
        // 按 enabled 过滤（dao 已经做了）；逐仓拉数据
        Map<String, Map<String, Object>> typeAcc = new LinkedHashMap<>();
        Map<String, Map<String, Object>> domainAcc = new LinkedHashMap<>();
        Map<String, Map<String, Object>> personAcc = new LinkedHashMap<>();
        Map<String, java.util.TreeSet<String>> personTxAcc = new java.util.HashMap<>();
        Map<String, Map<String, Object>> txAcc = new LinkedHashMap<>();
        long totalOwned = 0;
        Object snapshotTime = null;

        for (Map<String, Object> repo : repos) {
            long rid = lng(repo.get("id"));
            if (rid <= 0) continue;
            Map<String, Object> sub = overview(rid);  // 调单仓路径

            totalOwned += lng(sub.get("totalOwnedLines"));
            Object st = sub.get("snapshotTime");
            if (st != null && (snapshotTime == null || String.valueOf(st).compareTo(String.valueOf(snapshotTime)) > 0)) {
                snapshotTime = st;
            }

            // byType 累加（STAFF / VENDOR）
            for (Map<String, Object> row : safeList(sub.get("byType"))) {
                String pt = String.valueOf(row.get("person_type"));
                Map<String, Object> agg = typeAcc.computeIfAbsent(pt, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("person_type", k);
                    m.put("owned_lines", 0L);
                    m.put("author_count", 0L);
                    return m;
                });
                agg.put("owned_lines", lng(agg.get("owned_lines")) + lng(row.get("owned_lines")));
                agg.put("author_count", lng(agg.get("author_count")) + lng(row.get("author_count")));
            }

            // byDomain 累加
            for (Map<String, Object> row : safeList(sub.get("byDomain"))) {
                String dk = String.valueOf(row.get("domainKey"));
                Map<String, Object> agg = domainAcc.computeIfAbsent(dk, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("domainKey", k);
                    m.put("staffOwned", 0L);
                    m.put("vendorOwned", 0L);
                    m.put("ownedLines", 0L);
                    return m;
                });
                agg.put("staffOwned",  lng(agg.get("staffOwned"))  + lng(row.get("staffOwned")));
                agg.put("vendorOwned", lng(agg.get("vendorOwned")) + lng(row.get("vendorOwned")));
                agg.put("ownedLines",  lng(agg.get("ownedLines"))  + lng(row.get("ownedLines")));
            }

            // topPersons 按 author_email dedup
            for (Map<String, Object> row : safeList(sub.get("topPersons"))) {
                String email = String.valueOf(row.get("author_email"));
                Map<String, Object> agg = personAcc.get(email);
                if (agg == null) {
                    agg = new LinkedHashMap<>(row);
                    agg.put("owned_lines", lng(row.get("owned_lines")));
                    agg.put("file_count", lng(row.get("file_count")));
                    personAcc.put(email, agg);
                    personTxAcc.put(email, new java.util.TreeSet<>());
                } else {
                    agg.put("owned_lines", lng(agg.get("owned_lines")) + lng(row.get("owned_lines")));
                    agg.put("file_count",  lng(agg.get("file_count"))  + lng(row.get("file_count")));
                }
                String txs = row.get("tx_ids") == null ? null : String.valueOf(row.get("tx_ids"));
                if (txs != null && !txs.isBlank()) {
                    for (String t : txs.split(",")) {
                        String tt = t.trim();
                        if (!tt.isEmpty()) personTxAcc.get(email).add(tt);
                    }
                }
            }

            // topTx 按 tx_id 累加
            for (Map<String, Object> row : safeList(sub.get("topTx"))) {
                String key = String.valueOf(row.get("tx_id")) + "|" + String.valueOf(row.get("person_type"));
                Map<String, Object> agg = txAcc.computeIfAbsent(key, k -> new LinkedHashMap<>(row));
                if (agg != row) {
                    agg.put("owned_lines", lng(agg.get("owned_lines")) + lng(row.get("owned_lines")));
                    agg.put("file_count",  lng(agg.get("file_count"))  + lng(row.get("file_count")));
                }
            }
        }

        // byType 重算 share_pct
        List<Map<String, Object>> byType = new java.util.ArrayList<>(typeAcc.values());
        for (Map<String, Object> row : byType) {
            long owned = lng(row.get("owned_lines"));
            double share = totalOwned == 0 ? 0.0 : Math.round(owned * 1000.0 / totalOwned) / 10.0;
            row.put("share_pct", share);
        }

        // byDomain 重算 sharePct 并按 ownedLines 降序
        List<Map<String, Object>> byDomain = new java.util.ArrayList<>(domainAcc.values());
        for (Map<String, Object> row : byDomain) {
            long owned = lng(row.get("ownedLines"));
            double share = totalOwned == 0 ? 0.0 : Math.round(owned * 1000.0 / totalOwned) / 10.0;
            row.put("sharePct", share);
        }
        byDomain.sort((a, b) -> Long.compare(lng(b.get("ownedLines")), lng(a.get("ownedLines"))));

        // topPersons：tx_ids 字符串拼回 + 按 owned_lines 降序取 20
        List<Map<String, Object>> topPersons = new java.util.ArrayList<>(personAcc.values());
        for (Map<String, Object> row : topPersons) {
            java.util.TreeSet<String> txs = personTxAcc.get(String.valueOf(row.get("author_email")));
            row.put("tx_ids", txs == null || txs.isEmpty() ? null : String.join(",", txs));
            row.put("tx_count", (long) (txs == null ? 0 : txs.size()));
        }
        topPersons.sort((a, b) -> Long.compare(lng(b.get("owned_lines")), lng(a.get("owned_lines"))));
        if (topPersons.size() > 20) {
            topPersons = topPersons.subList(0, 20);
        }

        // topTx 按 owned_lines 降序取 20
        List<Map<String, Object>> topTx = new java.util.ArrayList<>(txAcc.values());
        topTx.sort((a, b) -> Long.compare(lng(b.get("owned_lines")), lng(a.get("owned_lines"))));
        if (topTx.size() > 20) {
            topTx = topTx.subList(0, 20);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repoId", 0L);
        out.put("totalOwnedLines", totalOwned);
        out.put("byType", byType);
        out.put("topAuthors", topAuthorsFromAll());
        out.put("topPersons", topPersons);
        out.put("byDomain", byDomain);
        out.put("topTx", topTx);
        out.put("snapshotTime", snapshotTime);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> safeList(Object v) {
        if (v instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return java.util.Collections.emptyList();
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

    private List<Map<String, Object>> topAuthorsFromAll() {
        List<Map<String, Object>> repos = dao.listRepos();
        Map<String, Map<String, Object>> authorAcc = new LinkedHashMap<>();
        for (Map<String, Object> repo : repos) {
            long rid = lng(repo.get("id"));
            if (rid <= 0) continue;
            List<Map<String, Object>> authors = dao.authorRankingByType(rid, "STAFF", 10);
            for (Map<String, Object> row : authors) {
                String email = String.valueOf(row.get("author_email"));
                Map<String, Object> acc = authorAcc.get(email);
                if (acc == null) {
                    acc = new LinkedHashMap<>(row);
                    acc.put("owned_lines", lng(row.get("owned_lines")));
                    authorAcc.put(email, acc);
                } else {
                    acc.put("owned_lines", lng(acc.get("owned_lines")) + lng(row.get("owned_lines")));
                }
            }
        }
        List<Map<String, Object>> sorted = new java.util.ArrayList<>(authorAcc.values());
        sorted.sort((a, b) -> Long.compare(lng(b.get("owned_lines")), lng(a.get("owned_lines"))));
        return sorted.subList(0, Math.min(sorted.size(), 10));
    }
}
