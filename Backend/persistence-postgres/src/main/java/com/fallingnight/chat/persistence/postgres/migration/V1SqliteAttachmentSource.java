package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads the complete V1 attachment graph through query-only SQLite. */
public final class V1SqliteAttachmentSource {
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "files", Set.of("id", "room_id", "user_id", "file_name", "file_path",
                    "file_size", "cleared", "clear_reason", "cleared_at", "created_at",
                    "cos_url"),
            "messages", Set.of("id", "room_id", "user_id", "content_type", "file_name",
                    "file_size", "file_id", "file_cleared", "clear_reason", "created_at"),
            "friend_files", Set.of("id", "friendship_id", "user_id", "file_name",
                    "file_path", "file_size", "cleared", "clear_reason", "cleared_at",
                    "created_at", "cos_url"),
            "friend_messages", Set.of("id", "friendship_id", "sender_id", "content_type",
                    "file_name", "file_size", "file_id", "file_cleared", "clear_reason",
                    "created_at"));
    private static final DateTimeFormatter SQLITE_UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final Path database;
    private final V1AttachmentSourcePlanner planner;

    public V1SqliteAttachmentSource(Path database) {
        this(database, new V1AttachmentSourcePlanner());
    }

    V1SqliteAttachmentSource(Path database, V1AttachmentSourcePlanner planner) {
        this.database = requireReadableDatabase(database);
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public V1AttachmentSourcePlan readPlan() {
        try (Connection connection = DriverManager.getConnection(readOnlyUrl(database))) {
            configureReadOnly(connection);
            requireHealthyDatabase(connection);
            requireCurrentSchema(connection);
            return readPlan(connection);
        } catch (SQLException exception) {
            throw new V1AttachmentSourceException("V1 attachment source read failed", exception);
        }
    }

    V1AttachmentSourcePlan readPlan(Connection connection) throws SQLException {
        List<V1AttachmentSourceFile> files = new ArrayList<>();
        files.addAll(readFiles(connection, LegacyV1ConversationKind.ROOM,
                "files", "room_id"));
        files.addAll(readFiles(connection, LegacyV1ConversationKind.FRIENDSHIP,
                "friend_files", "friendship_id"));
        List<V1AttachmentMessageLink> links = new ArrayList<>();
        links.addAll(readLinks(connection, LegacyV1ConversationKind.ROOM,
                "messages", "room_id", "user_id"));
        links.addAll(readLinks(connection, LegacyV1ConversationKind.FRIENDSHIP,
                "friend_messages", "friendship_id", "sender_id"));
        return planner.plan(files, links);
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
                throw new V1AttachmentSourceException("V1 SQLite quick_check did not pass");
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
                throw new V1AttachmentSourceException(
                        "V1 attachment schema is missing required migrated columns");
            }
        }
    }

    private static List<V1AttachmentSourceFile> readFiles(Connection connection,
            LegacyV1ConversationKind kind, String table, String conversationColumn)
            throws SQLException {
        List<V1AttachmentSourceFile> files = new ArrayList<>();
        String sql = "SELECT id, " + conversationColumn + " AS conversation_id, user_id, "
                + "file_name, file_path, file_size, cleared, clear_reason, cleared_at, "
                + "created_at, cos_url FROM " + table + " ORDER BY id";
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                files.add(new V1AttachmentSourceFile(kind,
                        result.getLong("conversation_id"), result.getLong("id"),
                        result.getLong("user_id"), result.getString("file_name"),
                        result.getLong("file_size"), result.getBoolean("cleared"),
                        result.getString("clear_reason"),
                        parseTimestamp(result.getString("cleared_at")),
                        parseTimestamp(result.getString("created_at")),
                        result.getString("file_path"), result.getString("cos_url")));
            }
        }
        return files;
    }

    private static List<V1AttachmentMessageLink> readLinks(Connection connection,
            LegacyV1ConversationKind kind, String table, String conversationColumn,
            String senderColumn) throws SQLException {
        List<V1AttachmentMessageLink> links = new ArrayList<>();
        String sql = "SELECT id, " + conversationColumn + " AS conversation_id, "
                + senderColumn + " AS sender_id, file_id, content_type, file_name, file_size, "
                + "file_cleared, clear_reason, created_at FROM " + table
                + " WHERE content_type IN ('file', 'image', 'video') ORDER BY id";
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                links.add(new V1AttachmentMessageLink(kind,
                        result.getLong("conversation_id"), result.getLong("id"),
                        result.getLong("sender_id"), result.getLong("file_id"),
                        result.getString("content_type"), result.getString("file_name"),
                        result.getLong("file_size"), result.getBoolean("file_cleared"),
                        result.getString("clear_reason"),
                        parseTimestamp(result.getString("created_at"))));
            }
        }
        return links;
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Try the offset and SQLite UTC representations below.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Offset-less V1 values are interpreted as documented SQLite UTC.
        }
        try {
            return LocalDateTime.parse(value, SQLITE_UTC).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Path requireReadableDatabase(Path value) {
        Objects.requireNonNull(value, "database");
        try {
            Path real = value.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
                throw new V1AttachmentSourceException("V1 SQLite source must be a readable file");
            }
            return real;
        } catch (java.io.IOException exception) {
            throw new V1AttachmentSourceException("V1 SQLite source is not readable", exception);
        }
    }

    private static String readOnlyUrl(Path database) {
        return "jdbc:sqlite:" + database.toUri() + "?mode=ro";
    }
}
