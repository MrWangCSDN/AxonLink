package com.axonlink.ai.code.persistence;

import com.axonlink.ai.code.entity.CodeFileAuthorStat;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code code_file_author_stat} 写入。结果库 = benchmarkdb（{@code diiResultJdbcTemplate}）。
 *
 * <p>写策略：
 * <ul>
 *   <li>FULL（首跑 / 历史改写回退）：{@link #deleteByRepoId} 清空该仓库 + {@link #batchInsert} 全量重灌</li>
 *   <li>INCREMENTAL：{@link #deleteByRepoIdAndPaths} 只删变更∪删除的文件行 + {@link #batchInsert} 重插变更文件；
 *       未触碰文件的旧行原样保留（其 blame 归属与全量重算逐字节等价）</li>
 * </ul>
 */
@Repository
public class CodeFileAuthorStatDao {

    /** 批量 insert / IN 删除的分块大小，避免单条 SQL 过长与参数上限。 */
    private static final int CHUNK = 500;

    private static final String INSERT_SQL =
            "INSERT INTO code_file_author_stat (" +
            " repo_id, file_path, author_name, author_email, user_id, " +
            " owned_lines, file_total_lines, added_lines, deleted_lines, commit_count, " +
            " first_commit_time, last_commit_time, snapshot_commit, snapshot_time) " +
            "VALUES (?, ?, ?, ?, ?,  ?, ?, ?, ?, ?,  ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public CodeFileAuthorStatDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /** FULL 模式：清空该仓库全部事实行。 */
    public int deleteByRepoId(Long repoId) {
        return jdbc.update("DELETE FROM code_file_author_stat WHERE repo_id = ?", repoId);
    }

    /** INCREMENTAL 模式：删除指定文件路径的行（变更∪删除集）。分块 IN，返回累计删除行数。 */
    public int deleteByRepoIdAndPaths(Long repoId, Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return 0;
        }
        List<String> all = new ArrayList<>(paths);
        int deleted = 0;
        for (int i = 0; i < all.size(); i += CHUNK) {
            List<String> sub = all.subList(i, Math.min(all.size(), i + CHUNK));
            StringBuilder sb = new StringBuilder(
                    "DELETE FROM code_file_author_stat WHERE repo_id = ? AND file_path IN (");
            for (int j = 0; j < sub.size(); j++) {
                sb.append(j == 0 ? "?" : ",?");
            }
            sb.append(')');
            Object[] args = new Object[sub.size() + 1];
            args[0] = repoId;
            for (int j = 0; j < sub.size(); j++) {
                args[j + 1] = sub.get(j);
            }
            deleted += jdbc.update(sb.toString(), args);
        }
        return deleted;
    }

    /** 分块批量插入。 */
    public void batchInsert(List<CodeFileAuthorStat> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size(); i += CHUNK) {
            final List<CodeFileAuthorStat> sub = list.subList(i, Math.min(list.size(), i + CHUNK));
            jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    CodeFileAuthorStat s = sub.get(idx);
                    int k = 1;
                    ps.setLong(k++, s.getRepoId());
                    ps.setString(k++, s.getFilePath());
                    ps.setString(k++, s.getAuthorName());
                    ps.setString(k++, s.getAuthorEmail());
                    if (s.getUserId() == null) {
                        ps.setNull(k++, Types.BIGINT);
                    } else {
                        ps.setLong(k++, s.getUserId());
                    }
                    ps.setInt(k++, nz(s.getOwnedLines()));
                    ps.setInt(k++, nz(s.getFileTotalLines()));
                    ps.setInt(k++, nz(s.getAddedLines()));
                    ps.setInt(k++, nz(s.getDeletedLines()));
                    ps.setInt(k++, nz(s.getCommitCount()));
                    ps.setTimestamp(k++, ts(s.getFirstCommitTime()));
                    ps.setTimestamp(k++, ts(s.getLastCommitTime()));
                    ps.setString(k++, s.getSnapshotCommit());
                    ps.setTimestamp(k++, ts(s.getSnapshotTime()));
                }

                @Override
                public int getBatchSize() {
                    return sub.size();
                }
            });
        }
    }

    /** 该仓库当前事实行数（日志/校验用）。 */
    public int countByRepoId(Long repoId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_file_author_stat WHERE repo_id = ?",
                Integer.class, repoId);
        return n == null ? 0 : n;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static Timestamp ts(java.util.Date d) {
        return d == null ? null : new Timestamp(d.getTime());
    }
}
