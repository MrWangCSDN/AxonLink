package com.axonlink.ai.code.persistence;

import com.axonlink.ai.code.entity.CodeRepoConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * {@code code_repo_config} 读写。结果库 = benchmarkdb（{@code diiResultJdbcTemplate}）。
 * 仿 {@code DiiAnalysisTaskDao} 的 JdbcTemplate + @Repository 模式（本工程无 MyBatis）。
 */
@Repository
public class CodeRepoConfigDao {

    private static final String SELECT_COLS =
            "id, repo_name, repo_url, branch, local_path, credential_ref, " +
            "include_exts, exclude_paths, last_sync_commit, last_sync_time, " +
            "last_sync_status, enabled";

    private final JdbcTemplate jdbc;

    public CodeRepoConfigDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    private static final RowMapper<CodeRepoConfig> MAPPER = (rs, n) -> {
        CodeRepoConfig c = new CodeRepoConfig();
        c.setId(rs.getLong("id"));
        c.setRepoName(rs.getString("repo_name"));
        c.setRepoUrl(rs.getString("repo_url"));
        c.setBranch(rs.getString("branch"));
        c.setLocalPath(rs.getString("local_path"));
        c.setCredentialRef(rs.getString("credential_ref"));
        c.setIncludeExts(rs.getString("include_exts"));
        c.setExcludePaths(rs.getString("exclude_paths"));
        c.setLastSyncCommit(rs.getString("last_sync_commit"));
        c.setLastSyncTime(rs.getTimestamp("last_sync_time"));
        c.setLastSyncStatus(rs.getString("last_sync_status"));
        c.setEnabled(toInt(rs.getObject("enabled")));
        return c;
    };

    /**
     * enabled 列为 TINYINT(1)，MySQL 驱动默认 tinyInt1isBit=true 时返回 Boolean，
     * 关闭该选项又返回数值，故统一兼容 Boolean / Number / null。
     */
    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 参与定时分析的仓库（enabled=1）。 */
    public List<CodeRepoConfig> selectEnabled() {
        return jdbc.query(
                "SELECT " + SELECT_COLS + " FROM code_repo_config WHERE enabled = 1 ORDER BY id",
                MAPPER);
    }

    /** 按仓库名查（uk_code_repo_name）；不存在返回 null。供本地扫描 get-or-create 用。 */
    public CodeRepoConfig findByRepoName(String repoName) {
        List<CodeRepoConfig> l = jdbc.query(
                "SELECT " + SELECT_COLS + " FROM code_repo_config WHERE repo_name = ? LIMIT 1",
                MAPPER, repoName);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * 新建仓库配置，返回自增 id。
     * 本地扫描场景 enabled 固定 0：定时任务永不挑它（避免对本地工作副本做 fetch/reset），
     * 仅手动 /scan 接口以只读模式扫描。
     */
    public long insert(CodeRepoConfig c) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO code_repo_config " +
                    " (repo_name, repo_url, branch, local_path, include_exts, exclude_paths, enabled) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.getRepoName());
            ps.setString(2, c.getRepoUrl());
            ps.setString(3, c.getBranch());
            ps.setString(4, c.getLocalPath());
            ps.setString(5, c.getIncludeExts());
            ps.setString(6, c.getExcludePaths());
            ps.setInt(7, c.getEnabled() == null ? 0 : c.getEnabled());
            return ps;
        }, kh);
        Number k = kh.getKey();
        return k == null ? -1L : k.longValue();
    }

    /** 本地扫描接入参数变化时更新（路径/分支/过滤），不动 last_sync_*。 */
    public int updateLocalConfig(CodeRepoConfig c) {
        return jdbc.update(
                "UPDATE code_repo_config SET repo_url = ?, branch = ?, local_path = ?, " +
                "include_exts = ?, exclude_paths = ? WHERE id = ?",
                c.getRepoUrl(), c.getBranch(), c.getLocalPath(),
                c.getIncludeExts(), c.getExcludePaths(), c.getId());
    }

    /** 同步收尾：回写 HEAD 基线与状态。 */
    public int updateSyncState(CodeRepoConfig repo) {
        return jdbc.update(
                "UPDATE code_repo_config SET last_sync_commit = ?, last_sync_time = ?, " +
                "last_sync_status = ? WHERE id = ?",
                repo.getLastSyncCommit(), repo.getLastSyncTime(),
                repo.getLastSyncStatus(), repo.getId());
    }

    /** 按 id 删除仓库配置（物理删除）。 */
    public int deleteById(long id) {
        return jdbc.update("DELETE FROM code_repo_config WHERE id = ?", id);
    }
}
