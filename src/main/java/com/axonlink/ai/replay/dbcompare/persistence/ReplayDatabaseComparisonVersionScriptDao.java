package com.axonlink.ai.replay.dbcompare.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ReplayDatabaseComparisonVersionScriptDao {

    private final JdbcTemplate jdbc;

    public ReplayDatabaseComparisonVersionScriptDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    public long insert(NewScript script) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO dii_replay_db_compare_version_script "
                            + "(version_id,file_name,compression,script_content,script_sha256,"
                            + "script_size,compressed_size,generated_by,generated_name,generated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, script.versionId());
            statement.setString(2, script.fileName());
            statement.setString(3, script.compression());
            statement.setBytes(4, script.scriptContent());
            statement.setString(5, script.scriptSha256());
            statement.setLong(6, script.scriptSize());
            statement.setLong(7, script.compressedSize());
            statement.setString(8, script.generatedBy());
            statement.setString(9, script.generatedName());
            statement.setTimestamp(10, Timestamp.valueOf(script.generatedAt()));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("保存数据库比对字段版本配置脚本失败");
        }
        return key.longValue();
    }

    public StoredScript findByVersionId(long versionId) {
        List<StoredScript> scripts = jdbc.query(
                "SELECT * FROM dii_replay_db_compare_version_script WHERE version_id=?",
                (row, index) -> new StoredScript(
                        row.getLong("id"),
                        row.getLong("version_id"),
                        row.getString("file_name"),
                        row.getString("compression"),
                        row.getBytes("script_content"),
                        row.getString("script_sha256"),
                        row.getLong("script_size"),
                        row.getLong("compressed_size"),
                        row.getString("generated_by"),
                        row.getString("generated_name"),
                        row.getTimestamp("generated_at").toLocalDateTime()),
                versionId);
        return scripts.isEmpty() ? null : scripts.get(0);
    }

    public record NewScript(
            long versionId,
            String fileName,
            String compression,
            byte[] scriptContent,
            String scriptSha256,
            long scriptSize,
            long compressedSize,
            String generatedBy,
            String generatedName,
            LocalDateTime generatedAt) {
    }

    public record StoredScript(
            long id,
            long versionId,
            String fileName,
            String compression,
            byte[] scriptContent,
            String scriptSha256,
            long scriptSize,
            long compressedSize,
            String generatedBy,
            String generatedName,
            LocalDateTime generatedAt) {
    }
}
