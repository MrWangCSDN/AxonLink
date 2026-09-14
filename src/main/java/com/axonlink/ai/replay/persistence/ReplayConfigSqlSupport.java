package com.axonlink.ai.replay.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 回放配置 DAO 公共 SQL 辅助。
 *
 * <p>包含查询统一转义百分号、下划线和转义符，使用 {@code !} 作为转义字符以兼容 MySQL 与 H2。
 */
public final class ReplayConfigSqlSupport {

    static final String LIKE_ESCAPE = " ESCAPE '!'";
    private static final char ESCAPE_CHAR = '!';

    private ReplayConfigSqlSupport() {
    }

    /** 生成大小写敏感的包含查询模式串。 */
    public static String likeContains(String raw) {
        StringBuilder builder = new StringBuilder("%");
        for (char c : raw.trim().toCharArray()) {
            if (c == ESCAPE_CHAR || c == '%' || c == '_') {
                builder.append(ESCAPE_CHAR);
            }
            builder.append(c);
        }
        return builder.append('%').toString();
    }

    /** 生成 IN 占位符并将参数追加到列表。 */
    public static String appendIn(List<Object> args, Collection<String> values) {
        StringBuilder builder = new StringBuilder("(");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                builder.append(',');
            }
            builder.append('?');
            args.add(value);
            first = false;
        }
        return builder.append(')').toString();
    }

    /** 读取可空 DATETIME 列为 LocalDateTime。 */
    public static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
