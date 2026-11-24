package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.Summary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/** Each edit replaces configuration and its verified script in one atomic statement. */
@Repository
public class ReplayDatabaseComparisonScriptConfigurationDao {
    private final JdbcTemplate jdbc;

    public ReplayDatabaseComparisonScriptConfigurationDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    public List<Summary> list() {
        return jdbc.query("SELECT id,name,revision,table_count,field_count,updated_by,updated_at "
                        + "FROM dii_replay_db_compare_script_config ORDER BY updated_at DESC,id DESC",
                (r, n) -> new Summary(r.getLong("id"), r.getString("name"), r.getLong("revision"),
                        r.getInt("table_count"), r.getInt("field_count"), r.getString("updated_by"),
                        r.getTimestamp("updated_at").toLocalDateTime()));
    }

    public StoredConfiguration find(long id) {
        return jdbc.query("SELECT * FROM dii_replay_db_compare_script_config WHERE id=?", (r, n) ->
                new StoredConfiguration(r.getLong("id"), r.getString("name"), r.getString("name_key"),
                        r.getLong("revision"), r.getString("config_json"), r.getString("file_name"),
                        r.getBytes("script_content_gzip"), r.getString("script_sha256"),
                        r.getLong("script_size"), r.getInt("table_count"), r.getInt("field_count"),
                        r.getString("updated_by"), r.getTimestamp("created_at").toLocalDateTime(),
                        r.getTimestamp("updated_at").toLocalDateTime()), id).stream().findFirst().orElse(null);
    }

    public long insert(WriteConfiguration value) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO dii_replay_db_compare_script_config "
                    + "(name,name_key,revision,config_json,file_name,script_content_gzip,script_sha256,script_size,"
                    + "table_count,field_count,updated_by,created_at,updated_at) VALUES (?,?,1,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, value.name());
            statement.setString(2, value.nameKey());
            statement.setString(3, value.configJson());
            statement.setString(4, value.fileName());
            statement.setBytes(5, value.scriptContentGzip());
            statement.setString(6, value.scriptSha256());
            statement.setLong(7, value.scriptSize());
            statement.setInt(8, value.tableCount());
            statement.setInt(9, value.fieldCount());
            statement.setString(10, value.updatedBy());
            statement.setTimestamp(11, Timestamp.valueOf(value.updatedAt()));
            statement.setTimestamp(12, Timestamp.valueOf(value.updatedAt()));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("保存命名脚本配置失败");
        return key.longValue();
    }

    public int update(long id, long expectedRevision, WriteConfiguration value) {
        return jdbc.update("UPDATE dii_replay_db_compare_script_config SET name=?,name_key=?,revision=revision+1,"
                        + "config_json=?,file_name=?,script_content_gzip=?,script_sha256=?,script_size=?,"
                        + "table_count=?,field_count=?,updated_by=?,updated_at=? WHERE id=? AND revision=?",
                value.name(), value.nameKey(), value.configJson(), value.fileName(), value.scriptContentGzip(),
                value.scriptSha256(), value.scriptSize(), value.tableCount(), value.fieldCount(),
                value.updatedBy(), Timestamp.valueOf(value.updatedAt()), id, expectedRevision);
    }

    public record WriteConfiguration(String name, String nameKey, String configJson, String fileName,
                                     byte[] scriptContentGzip, String scriptSha256, long scriptSize,
                                     int tableCount, int fieldCount, String updatedBy, LocalDateTime updatedAt) {}

    public record StoredConfiguration(long id, String name, String nameKey, long revision, String configJson,
                                      String fileName, byte[] scriptContentGzip, String scriptSha256,
                                      long scriptSize, int tableCount, int fieldCount, String updatedBy,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
