package com.axonlink.ai.code.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 仓库每日代码行数快照 DAO。
 */
@Repository
public class CodeRepoDailyStatDao {

    private final JdbcTemplate jdbc;

    public CodeRepoDailyStatDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /**
     * 写入或更新（UPSERT）某仓库某日的快照。
     * 兼容 MySQL / openGauss。
     */
    public void upsert(long repoId, String statDate,
                       long totalOwned, long staffOwned, long vendorOwned,
                       int authorCount, int fileCount, String snapshotCommit) {
        String sql = """
            INSERT INTO code_repo_daily_stat
              (repo_id, stat_date, total_owned_lines, staff_owned_lines,
               vendor_owned_lines, author_count, file_count, snapshot_commit, snapshot_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON DUPLICATE KEY UPDATE
              total_owned_lines = VALUES(total_owned_lines),
              staff_owned_lines = VALUES(staff_owned_lines),
              vendor_owned_lines = VALUES(vendor_owned_lines),
              author_count = VALUES(author_count),
              file_count = VALUES(file_count),
              snapshot_commit = VALUES(snapshot_commit),
              snapshot_time = NOW()
            """;
        jdbc.update(sql, repoId, statDate, totalOwned, staffOwned, vendorOwned,
                authorCount, fileCount, snapshotCommit);
    }

    /**
     * 查询某仓库最近 N 天的每日快照（折线图数据源）。
     */
    public List<Map<String, Object>> queryTrend(long repoId, int days) {
        return jdbc.queryForList("""
            SELECT stat_date, total_owned_lines, staff_owned_lines,
                   vendor_owned_lines, author_count, file_count
              FROM code_repo_daily_stat
             WHERE repo_id = ?
               AND stat_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
             ORDER BY stat_date ASC
            """, repoId, days);
    }
}