package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.ConnectionDetails;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.ConnectionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

@Component
public class ReplayScriptConfigDataSource {
    private static final int DEFAULT_PORT = 5432;
    private static final Pattern SCHEMA_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final Driver driver;

    public ReplayScriptConfigDataSource() {
        this(new org.postgresql.Driver());
    }

    ReplayScriptConfigDataSource(Driver driver) {
        this.driver = driver;
    }

    ConnectionDetails normalize(ConnectionRequest source) {
        String host = requiredText(source.host(), "主机").toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = port(source);
        authority(host, port);
        String database = requiredText(source.database(), "数据库");
        String schema = requiredText(source.schema(), "Schema");
        if (!SCHEMA_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("数据源Schema必须是合法数据库标识符");
        }
        return new ConnectionDetails("CUSTOM", host, port, database, schema,
                requiredText(source.username(), "用户名"), null);
    }

    Connection open(ConnectionDetails source, String password) {
        String database = URLEncoder.encode(source.database(), StandardCharsets.UTF_8).replace("+", "%20");
        String url = "jdbc:postgresql://" + authority(source.host(), source.port()) + "/" + database;
        Properties credentials = new Properties();
        credentials.setProperty("user", source.username());
        credentials.setProperty("password", password);
        credentials.setProperty("loginTimeout", "10");
        credentials.setProperty("connectTimeout", "10");
        credentials.setProperty("socketTimeout", "30");

        Connection connection = null;
        try {
            connection = driver.connect(url, credentials);
            if (connection == null) throw unavailable();
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException | RuntimeException ignored) {
                    // A driver exception can contain connection details.
                }
            }
            throw unavailable();
        }
    }

    static ReplayDatabaseComparisonGenerationException unavailable() {
        return new ReplayDatabaseComparisonGenerationException(HttpStatus.SERVICE_UNAVAILABLE,
                "SCRIPT_CONFIG_DATABASE_UNAVAILABLE", "所选方案数据源暂不可用", Map.of());
    }

    private int port(ConnectionRequest source) {
        if (source.port() == null) return DEFAULT_PORT;
        try {
            int port = source.port().intValueExact();
            if (port >= 1 && port <= 65535) return port;
        } catch (ArithmeticException ignored) {
            // Fractional and out-of-range values share the same validation error.
        }
        throw new IllegalArgumentException("数据源端口必须为1到65535的整数");
    }

    private String authority(String host, int port) {
        try {
            URI address = new URI("postgresql", null, host, port, null, null, null);
            if (address.getHost() != null && host.indexOf('%') < 0) return address.getRawAuthority();
        } catch (URISyntaxException ignored) {
            // Keep invalid endpoint text out of validation responses.
        }
        throw new IllegalArgumentException("数据源主机格式不正确");
    }

    private String requiredText(String input, String label) {
        if (input == null || input.isBlank() || input.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("数据源" + label + "不能为空或包含控制字符");
        }
        return input.trim();
    }
}
