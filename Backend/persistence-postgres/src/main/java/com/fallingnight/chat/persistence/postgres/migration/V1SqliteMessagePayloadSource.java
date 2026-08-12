package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads retained V1 message bodies and attachment metadata without file bytes/paths. */
public final class V1SqliteMessagePayloadSource {
    private static final Set<String> MESSAGE_COLUMNS = Set.of(
            "id", "content_type", "content", "file_name", "file_size", "file_id",
            "file_cleared", "clear_reason", "thumbnail", "recalled");
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "messages", union(MESSAGE_COLUMNS, Set.of("room_id")),
            "friend_messages", union(MESSAGE_COLUMNS, Set.of("friendship_id")));

    private final Path database;
    private final V1MessagePayloadImportPlanner planner;

    public V1SqliteMessagePayloadSource(Path database) {
        this.database = requireReadableDatabase(database);
        this.planner = new V1MessagePayloadImportPlanner();
    }

    public V1MessagePayloadImportPlan readPlan() {
        try (Connection connection = DriverManager.getConnection(readOnlyUrl(database))) {
            configureReadOnly(connection);
            requireHealthyDatabase(connection);
            requireCurrentSchema(connection);
            connection.setAutoCommit(false);
            List<V1MessagePayloadRow> rows = new ArrayList<>();
            readRows(connection, "messages", "room_id",
                    LegacyV1ConversationKind.ROOM, rows);
            readRows(connection, "friend_messages", "friendship_id",
                    LegacyV1ConversationKind.FRIENDSHIP, rows);
            V1MessagePayloadImportPlan plan = planner.plan(rows);
            connection.rollback();
            return plan;
        } catch (SQLException exception) {
            throw new V1MessagePayloadSourceException(
                    "V1 message payload source read failed", exception);
        }
    }

    private static void readRows(
            Connection connection,
            String table,
            String conversationColumn,
            LegacyV1ConversationKind kind,
            List<V1MessagePayloadRow> output) throws SQLException {
        String sql = "SELECT id, " + conversationColumn + ", content_type, content, "
                + "file_name, file_size, file_id, file_cleared, clear_reason, thumbnail, "
                + "recalled FROM " + table + " ORDER BY id";
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                output.add(new V1MessagePayloadRow(
                        kind,
                        rows.getLong(conversationColumn),
                        rows.getLong("id"),
                        rows.getString("content_type"),
                        rows.getString("content"),
                        rows.getString("file_name"),
                        rows.getLong("file_size"),
                        rows.getLong("file_id"),
                        rows.getInt("file_cleared") != 0,
                        rows.getString("clear_reason"),
                        rows.getString("thumbnail"),
                        rows.getInt("recalled") != 0));
            }
        }
    }

    private static void configureReadOnly(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private static void requireHealthyDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next()) {
                throw new V1MessagePayloadSourceException("V1 SQLite quick_check did not pass");
            }
        }
    }

    private static void requireCurrentSchema(Connection connection) throws SQLException {
        for (Map.Entry<String, Set<String>> required : REQUIRED_COLUMNS.entrySet()) {
            Set<String> actual = new HashSet<>();
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "PRAGMA table_info(" + required.getKey() + ")")) {
                while (result.next()) {
                    actual.add(result.getString("name"));
                }
            }
            if (!actual.containsAll(required.getValue())) {
                throw new V1MessagePayloadSourceException(
                        "V1 message payload schema is missing required migrated columns");
            }
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static Path requireReadableDatabase(Path value) {
        Objects.requireNonNull(value, "database");
        try {
            Path real = value.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
                throw new V1MessagePayloadSourceException(
                        "V1 SQLite source must be a readable file");
            }
            return real;
        } catch (java.io.IOException exception) {
            throw new V1MessagePayloadSourceException(
                    "V1 SQLite source is not readable", exception);
        }
    }

    private static String readOnlyUrl(Path database) {
        return "jdbc:sqlite:" + database.toUri() + "?mode=ro";
    }
}
