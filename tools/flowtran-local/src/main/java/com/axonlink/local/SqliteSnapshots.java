package com.axonlink.local;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

final class SqliteSnapshots {
    private static final TypeReference<Map<String, Object>> PROPERTIES = new TypeReference<>() {};

    record Summary(String createdAt, ScanSettings settings, int javaFiles, Map<String, Integer> nodes,
                   int edges, int issueCount, long elapsedMs) {}
    record Snapshot(Summary summary, Graph graph) {}

    private final Path file;
    private final ObjectMapper json;

    SqliteSnapshots(Path file, ObjectMapper json) throws IOException, SQLException {
        this.file = file.toAbsolutePath().normalize();
        this.json = json;
        if (this.file.getParent() == null || Files.isDirectory(this.file)) {
            throw new IllegalArgumentException("lab.database 须指向 SQLite 文件 " + this.file);
        }
        Files.createDirectories(this.file.getParent());
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("""
                CREATE TABLE IF NOT EXISTS graph_scan (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    summary TEXT NOT NULL
                )
                """);
            sql.executeUpdate("""
                CREATE TABLE IF NOT EXISTS graph_node (
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    node_key TEXT NOT NULL,
                    properties TEXT NOT NULL
                )
                """);
            sql.executeUpdate("""
                CREATE TABLE IF NOT EXISTS graph_edge (
                    source_id TEXT NOT NULL REFERENCES graph_node (id),
                    target_id TEXT NOT NULL REFERENCES graph_node (id),
                    type TEXT NOT NULL,
                    line INTEGER NOT NULL,
                    method_name TEXT NOT NULL,
                    PRIMARY KEY (source_id, type, target_id, line, method_name)
                )
                """);
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS graph_edge_target ON graph_edge(target_id, type)");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS graph_node_kind ON graph_node(kind, node_key)");
            sql.executeUpdate("""
                CREATE TABLE IF NOT EXISTS graph_issue (
                    node_id TEXT,
                    file_path TEXT,
                    line INTEGER NOT NULL,
                    rule TEXT NOT NULL,
                    message TEXT
                )
                """);
        }
    }

    Path file() {
        return file;
    }

    Snapshot load() throws SQLException, IOException {
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            Summary summary;
            try (var rows = sql.executeQuery("SELECT summary FROM graph_scan WHERE id = 1")) {
                summary = rows.next() ? json.readValue(rows.getString(1), Summary.class) : null;
            }
            Graph graph = new Graph();
            try (var rows = sql.executeQuery("SELECT kind, node_key, properties FROM graph_node ORDER BY rowid")) {
                while (rows.next()) {
                    graph.put(rows.getString(1), rows.getString(2), json.readValue(rows.getString(3), PROPERTIES));
                }
            }
            try (var rows = sql.executeQuery("""
                SELECT source_id, target_id, type, line, method_name FROM graph_edge ORDER BY rowid
                """)) {
                while (rows.next()) {
                    graph.link(new Graph.Edge(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getInt(4), rows.getString(5)));
                }
            }
            try (var rows = sql.executeQuery("SELECT node_id, file_path, line, rule, message FROM graph_issue ORDER BY rowid")) {
                while (rows.next()) {
                    graph.report(new Graph.Issue(rows.getString(1), rows.getString(2), rows.getInt(3),
                        rows.getString(4), rows.getString(5)));
                }
            }
            connection.commit();
            return new Snapshot(summary, graph);
        }
    }

    void replace(Snapshot snapshot) throws SQLException, IOException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.executeUpdate("DELETE FROM graph_issue");
                sql.executeUpdate("DELETE FROM graph_edge");
                sql.executeUpdate("DELETE FROM graph_node");
                sql.executeUpdate("DELETE FROM graph_scan");
                insertGraph(connection, snapshot.graph());
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO graph_scan VALUES (1, ?)")) {
                    insert.setString(1, json.writeValueAsString(snapshot.summary()));
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | IOException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }
    }

    private void insertGraph(Connection connection, Graph graph) throws SQLException, IOException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO graph_node (id, kind, node_key, properties) VALUES (?, ?, ?, ?)
            """)) {
            for (Graph.Node node : graph.nodes()) {
                insert.setString(1, node.id());
                insert.setString(2, node.kind());
                insert.setString(3, node.key());
                insert.setString(4, json.writeValueAsString(node.properties()));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO graph_edge (source_id, target_id, type, line, method_name) VALUES (?, ?, ?, ?, ?)
            """)) {
            for (Graph.Edge edge : graph.edges()) {
                insert.setString(1, edge.from());
                insert.setString(2, edge.to());
                insert.setString(3, edge.type());
                insert.setInt(4, edge.line());
                insert.setString(5, edge.methodName());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO graph_issue (node_id, file_path, line, rule, message) VALUES (?, ?, ?, ?, ?)
            """)) {
            for (Graph.Issue issue : graph.issues()) {
                insert.setString(1, issue.nodeId());
                insert.setString(2, issue.file());
                insert.setInt(3, issue.line());
                insert.setString(4, issue.rule());
                insert.setString(5, issue.message());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toString().replace('\\', '/'));
        try (Statement sql = connection.createStatement()) {
            sql.executeUpdate("PRAGMA foreign_keys = ON");
            return connection;
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }
}
