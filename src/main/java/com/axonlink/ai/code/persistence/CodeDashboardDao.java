package com.axonlink.ai.code.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 大屏 summary 层读写。结果库 = benchmarkdb（{@code diiResultJdbcTemplate}）。
 *
 * <p>聚合一律 set-based（{@code INSERT ... SELECT ... GROUP BY}）在库内完成，
 * 不把百万级事实行拉进 JVM——这是大事实表下唯一可扩展的做法。
 *
 * <p>身份分类口径（SQL 内表达，别名表覆盖优先）：
 * <pre>COALESCE(alias.person_type,
 *   CASE WHEN LOWER(SUBSTRING_INDEX(email,'@',1)) REGEXP '^[ct]-' THEN 'VENDOR' ELSE 'STAFF' END)</pre>
 */
@Repository
public class CodeDashboardDao {

    /** 分类表达式：GROUP BY 位置用（rebuildTxPersonStat / rebuildDomainPersonStat 把它放进 GROUP BY）。 */
    private static final String PERSON_TYPE_EXPR =
            "COALESCE(a.person_type, CASE WHEN LOWER(SUBSTRING_INDEX(s.author_email,'@',1)) " +
            "REGEXP '^[ct]-' THEN 'VENDOR' ELSE 'STAFF' END)";

    /**
     * SELECT 位置用（GROUP BY 为 author_email 而非 person_type 时）。
     * a.person_type 须包裹 MAX 以通过 ONLY_FULL_GROUP_BY；别名表对 email 有 UNIQUE KEY，
     * MAX 取唯一值，结果与不加 MAX 完全等价。
     */
    private static final String PERSON_TYPE_SELECT =
            "COALESCE(MAX(a.person_type), CASE WHEN LOWER(SUBSTRING_INDEX(s.author_email,'@',1)) " +
            "REGEXP '^[ct]-' THEN 'VENDOR' ELSE 'STAFF' END)";

    private final JdbcTemplate jdbc;

    public CodeDashboardDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    // ---- 聚合（每日采集后重建该 repo 的 summary） ----

    /**
     * 重建工程维度 summary：仓库×作者（含归一人员名与 person_type）。
     * 不依赖 Phase②，采集后即可生效。返回插入行数。
     */
    public int rebuildRepoAuthorStat(long repoId) {
        jdbc.update("DELETE FROM code_repo_author_stat WHERE repo_id = ?", repoId);
        return jdbc.update(
                "INSERT INTO code_repo_author_stat " +
                " (repo_id, author_email, person_name, person_type, owned_lines, file_count, " +
                "  added_lines, deleted_lines, snapshot_commit, snapshot_time) " +
                "SELECT s.repo_id, s.author_email, " +
                "       COALESCE(MAX(a.person_name), MAX(s.author_name)), " +
                "       " + PERSON_TYPE_SELECT + ", " +
                "       SUM(s.owned_lines), COUNT(DISTINCT s.file_path), " +
                "       SUM(s.added_lines), SUM(s.deleted_lines), " +
                "       MAX(s.snapshot_commit), NOW() " +
                "  FROM code_file_author_stat s " +
                "  LEFT JOIN code_author_alias a ON a.email = s.author_email AND a.enabled = 1 " +
                " WHERE s.repo_id = ? " +
                " GROUP BY s.repo_id, s.author_email",
                repoId);
    }

    /**
     * 重建交易维度 summary：仓库×交易×person_type，来自 code_tx_file_map ⋈ 事实表。
     * code_tx_file_map 为空（Phase② 未落地）时自然 no-op（0 行）。返回插入行数。
     * 口径：一个文件被多笔交易共享时对每笔交易各自全计（已锁定，接受高估）。
     */
    public int rebuildTxPersonStat(long repoId) {
        jdbc.update("DELETE FROM code_tx_person_stat WHERE repo_id = ?", repoId);
        return jdbc.update(
                "INSERT INTO code_tx_person_stat " +
                " (repo_id, tx_id, person_type, owned_lines, file_count, snapshot_commit, snapshot_time) " +
                "SELECT m.repo_id, m.tx_id, " +
                "       " + PERSON_TYPE_EXPR + ", " +
                "       SUM(s.owned_lines), COUNT(DISTINCT s.file_path), " +
                "       MAX(s.snapshot_commit), NOW() " +
                "  FROM code_tx_file_map m " +
                "  JOIN code_file_author_stat s " +
                "    ON s.repo_id = m.repo_id AND s.file_path = m.file_path " +
                "  LEFT JOIN code_author_alias a ON a.email = s.author_email AND a.enabled = 1 " +
                " WHERE m.repo_id = ? " +
                " GROUP BY m.repo_id, m.tx_id, " + PERSON_TYPE_EXPR,
                repoId);
    }

    // ---- 读（大屏，只查小 summary 表） ----

    /** 仓库列表（大屏选择器）。 */
    public List<Map<String, Object>> listRepos() {
        return jdbc.queryForList(
                "SELECT id, repo_name, last_sync_commit, last_sync_time, last_sync_status " +
                "  FROM code_repo_config ORDER BY id");
    }

    /** 工程维度按 person_type 汇总（行员/厂商总览 + KPI 源）。 */
    public List<Map<String, Object>> sumByPersonType(long repoId) {
        return jdbc.queryForList(
                "SELECT person_type, " +
                "       COUNT(*)            AS author_count, " +
                "       SUM(owned_lines)    AS owned_lines, " +
                "       SUM(file_count)     AS file_count, " +
                "       SUM(added_lines)    AS added_lines, " +
                "       SUM(deleted_lines)  AS deleted_lines, " +
                "       MAX(snapshot_time)  AS snapshot_time " +
                "  FROM code_repo_author_stat WHERE repo_id = ? GROUP BY person_type",
                repoId);
    }

    /** 作者存活行排行（大屏 Top 榜 / 下钻）。 */
    public List<Map<String, Object>> authorRanking(long repoId, int limit) {
        return jdbc.queryForList(
                "SELECT author_email, person_name, person_type, owned_lines, file_count, " +
                "       added_lines, deleted_lines, snapshot_commit, snapshot_time " +
                "  FROM code_repo_author_stat WHERE repo_id = ? " +
                " ORDER BY owned_lines DESC LIMIT ?",
                repoId, clamp(limit));
    }

    /** 指定 person_type 的作者存活行排行（行员/厂商分榜）。 */
    public List<Map<String, Object>> authorRankingByType(long repoId, String personType, int limit) {
        return jdbc.queryForList(
                "SELECT author_email, person_name, person_type, owned_lines, file_count, " +
                "       added_lines, deleted_lines, snapshot_commit, snapshot_time " +
                "  FROM code_repo_author_stat WHERE repo_id = ? AND person_type = ? " +
                " ORDER BY owned_lines DESC LIMIT ?",
                repoId, personType, clamp(limit));
    }

    /** 交易维度排行（Phase② 后有数据；每行一个 tx×person_type）。 */
    public List<Map<String, Object>> txByPersonType(long repoId, int limit) {
        return jdbc.queryForList(
                "SELECT tx_id, person_type, owned_lines, file_count, snapshot_time " +
                "  FROM code_tx_person_stat WHERE repo_id = ? " +
                " ORDER BY owned_lines DESC LIMIT ?",
                repoId, clamp(limit));
    }

    // ---- 领域维度（路径纯函数推导，无 Neo4j；file_path 即事实表自身键，天然对齐） ----

    /** 该仓库去重文件路径（规模=文件数，喂 DomainKeyResolver 用）。 */
    public List<String> distinctFilePaths(long repoId) {
        return jdbc.queryForList(
                "SELECT DISTINCT file_path FROM code_file_author_stat WHERE repo_id = ?",
                String.class, repoId);
    }

    /** 该仓库当前事实快照 commit（写入 code_file_domain 备查）。 */
    public String latestSnapshotCommit(long repoId) {
        return jdbc.queryForObject(
                "SELECT MAX(snapshot_commit) FROM code_file_author_stat WHERE repo_id = ?",
                String.class, repoId);
    }

    /** 全量替换该仓库 file→domain 映射。rows 每项 = [file_path, domain_key]。 */
    public void replaceFileDomain(long repoId, List<Object[]> rows, String snapshotCommit) {
        jdbc.update("DELETE FROM code_file_domain WHERE repo_id = ?", repoId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Object[]> batch = new java.util.ArrayList<>(rows.size());
        for (Object[] r : rows) {
            batch.add(new Object[]{repoId, r[0], r[1], snapshotCommit});
        }
        for (int i = 0; i < batch.size(); i += 500) {
            jdbc.batchUpdate(
                    "INSERT INTO code_file_domain " +
                    " (repo_id, file_path, domain_key, snapshot_commit, snapshot_time) " +
                    "VALUES (?, ?, ?, ?, NOW())",
                    batch.subList(i, Math.min(batch.size(), i + 500)));
        }
    }

    /** 重建领域×person_type 聚合（事实 ⋈ code_file_domain ⋈ 别名分类）。返回插入行数。 */
    public int rebuildDomainPersonStat(long repoId) {
        jdbc.update("DELETE FROM code_domain_person_stat WHERE repo_id = ?", repoId);
        return jdbc.update(
                "INSERT INTO code_domain_person_stat " +
                " (repo_id, domain_key, person_type, owned_lines, file_count, " +
                "  added_lines, deleted_lines, snapshot_commit, snapshot_time) " +
                "SELECT s.repo_id, d.domain_key, " + PERSON_TYPE_EXPR + ", " +
                "       SUM(s.owned_lines), COUNT(DISTINCT s.file_path), " +
                "       SUM(s.added_lines), SUM(s.deleted_lines), " +
                "       MAX(s.snapshot_commit), NOW() " +
                "  FROM code_file_author_stat s " +
                "  JOIN code_file_domain d ON d.repo_id = s.repo_id AND d.file_path = s.file_path " +
                "  LEFT JOIN code_author_alias a ON a.email = s.author_email AND a.enabled = 1 " +
                " WHERE s.repo_id = ? " +
                " GROUP BY s.repo_id, d.domain_key, " + PERSON_TYPE_EXPR,
                repoId);
    }

    /** 重建领域内作者明细（下钻）。返回插入行数。 */
    public int rebuildDomainAuthorStat(long repoId) {
        jdbc.update("DELETE FROM code_domain_author_stat WHERE repo_id = ?", repoId);
        return jdbc.update(
                "INSERT INTO code_domain_author_stat " +
                " (repo_id, domain_key, author_email, person_name, person_type, " +
                "  owned_lines, file_count, snapshot_commit, snapshot_time) " +
                "SELECT s.repo_id, d.domain_key, s.author_email, " +
                "       COALESCE(MAX(a.person_name), MAX(s.author_name)), " +
                "       " + PERSON_TYPE_SELECT + ", " +
                "       SUM(s.owned_lines), COUNT(DISTINCT s.file_path), " +
                "       MAX(s.snapshot_commit), NOW() " +
                "  FROM code_file_author_stat s " +
                "  JOIN code_file_domain d ON d.repo_id = s.repo_id AND d.file_path = s.file_path " +
                "  LEFT JOIN code_author_alias a ON a.email = s.author_email AND a.enabled = 1 " +
                " WHERE s.repo_id = ? " +
                " GROUP BY s.repo_id, d.domain_key, s.author_email",
                repoId);
    }

    /** 领域维度汇总（每行 = 领域×person_type）。 */
    public List<Map<String, Object>> sumByDomain(long repoId) {
        return jdbc.queryForList(
                "SELECT domain_key, person_type, owned_lines, file_count, " +
                "       added_lines, deleted_lines, snapshot_time " +
                "  FROM code_domain_person_stat WHERE repo_id = ? " +
                " ORDER BY domain_key, person_type",
                repoId);
    }

    /** 某领域内作者排行（下钻）。 */
    public List<Map<String, Object>> domainAuthors(long repoId, String domainKey, int limit) {
        return jdbc.queryForList(
                "SELECT domain_key, author_email, person_name, person_type, owned_lines, " +
                "       file_count, snapshot_time " +
                "  FROM code_domain_author_stat WHERE repo_id = ? AND domain_key = ? " +
                " ORDER BY owned_lines DESC LIMIT ?",
                repoId, domainKey, clamp(limit));
    }

    // ---- 交易→文件映射写入（flowtrans XML 扫描结果；每次全量替换）----

    /**
     * 全量替换该仓库 tx→file 映射（由 CodeTxXmlScanner 生成）。
     * rows 每项 = [repoRelativeFilePath, txId]。
     */
    public void replaceTxFileMap(long repoId, List<String[]> rows, String snapshotCommit) {
        jdbc.update("DELETE FROM code_tx_file_map WHERE repo_id = ?", repoId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Object[]> batch = new java.util.ArrayList<>(rows.size());
        for (String[] r : rows) {
            batch.add(new Object[]{repoId, r[1], r[0], snapshotCommit}); // tx_id, file_path
        }
        for (int i = 0; i < batch.size(); i += 500) {
            jdbc.batchUpdate(
                    "INSERT INTO code_tx_file_map " +
                    " (repo_id, tx_id, file_path, snapshot_commit, snapshot_time) " +
                    "VALUES (?, ?, ?, ?, NOW())",
                    batch.subList(i, Math.min(batch.size(), i + 500)));
        }
    }

    // ---- 人员×交易 聚合（依赖 code_tx_file_map 已落库）----

    /**
     * 重建人员×交易归属明细：仓库×作者×交易（来源 flowtrans XML blame）。
     * 当前口径：仅统计行员（STAFF）对 flowtrans XML 的代码归属，厂商暂不聚合。
     * code_tx_file_map 为空时自然 no-op（0 行）。返回插入行数。
     */
    public int rebuildPersonTxStat(long repoId) {
        jdbc.update("DELETE FROM code_person_tx_stat WHERE repo_id = ?", repoId);
        return jdbc.update(
                "INSERT INTO code_person_tx_stat " +
                " (repo_id, author_email, person_name, person_type, tx_id, " +
                "  owned_lines, file_count, snapshot_commit, snapshot_time) " +
                "SELECT s.repo_id, s.author_email, " +
                "       COALESCE(MAX(a.person_name), MAX(s.author_name)), " +
                "       " + PERSON_TYPE_SELECT + ", " +
                "       m.tx_id, " +
                "       SUM(s.owned_lines), COUNT(DISTINCT s.file_path), " +
                "       MAX(s.snapshot_commit), NOW() " +
                "  FROM code_file_author_stat s " +
                "  JOIN code_tx_file_map m " +
                "    ON m.repo_id = s.repo_id AND m.file_path = s.file_path " +
                "  LEFT JOIN code_author_alias a ON a.email = s.author_email AND a.enabled = 1 " +
                " WHERE s.repo_id = ? " +
                " GROUP BY s.repo_id, s.author_email, m.tx_id " +
                "HAVING " + PERSON_TYPE_SELECT + " = 'STAFF'",
                repoId);
    }

    // ---- 人员维度读（大屏：作者总行 + 交易归属列表）----

    /**
     * 人员维度统计：每人总行数 + 归属交易码列表（逗号分隔）。
     * 来源：code_repo_author_stat LEFT JOIN code_person_tx_stat。
     * tx_ids 为空时表示该人未参与任何 flowtrans XML。
     */
    public List<Map<String, Object>> personStats(long repoId, int limit) {
        return jdbc.queryForList(
                "SELECT r.author_email, r.person_name, r.person_type, " +
                "       r.owned_lines, r.file_count, " +
                "       GROUP_CONCAT(DISTINCT p.tx_id ORDER BY p.tx_id SEPARATOR ',') AS tx_ids, " +
                "       COUNT(DISTINCT p.tx_id) AS tx_count, " +
                "       r.snapshot_time " +
                "  FROM code_repo_author_stat r " +
                "  LEFT JOIN code_person_tx_stat p " +
                "    ON p.repo_id = r.repo_id AND p.author_email = r.author_email " +
                " WHERE r.repo_id = ? " +
                " GROUP BY r.author_email, r.person_name, r.person_type, " +
                "          r.owned_lines, r.file_count, r.snapshot_time " +
                " ORDER BY r.owned_lines DESC " +
                " LIMIT ?",
                repoId, clamp(limit));
    }

    /** 指定 person_type 的人员统计（行员/厂商分榜）。 */
    public List<Map<String, Object>> personStatsByType(long repoId, String personType, int limit) {
        return jdbc.queryForList(
                "SELECT r.author_email, r.person_name, r.person_type, " +
                "       r.owned_lines, r.file_count, " +
                "       GROUP_CONCAT(DISTINCT p.tx_id ORDER BY p.tx_id SEPARATOR ',') AS tx_ids, " +
                "       COUNT(DISTINCT p.tx_id) AS tx_count, " +
                "       r.snapshot_time " +
                "  FROM code_repo_author_stat r " +
                "  LEFT JOIN code_person_tx_stat p " +
                "    ON p.repo_id = r.repo_id AND p.author_email = r.author_email " +
                " WHERE r.repo_id = ? AND r.person_type = ? " +
                " GROUP BY r.author_email, r.person_name, r.person_type, " +
                "          r.owned_lines, r.file_count, r.snapshot_time " +
                " ORDER BY r.owned_lines DESC " +
                " LIMIT ?",
                repoId, personType, clamp(limit));
    }

    private int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 1000);
    }
}
