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

/** Reads V1 conversation and message cursor state from one query-only SQLite snapshot. */
public final class V1SqliteMessageStateSource {
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "messages", Set.of(
                    "id", "room_id", "user_id", "sequence", "mutation_sequence",
                    "recalled", "created_at"),
            "room_message_sequences", Set.of("room_id", "last_sequence"),
            "room_message_deletion_events", Set.of(
                    "id", "room_id", "operator_user_id", "sequence", "created_at"),
            "friend_messages", Set.of(
                    "id", "friendship_id", "sender_id", "sequence", "mutation_sequence",
                    "recalled", "created_at"),
            "friendship_message_sequences", Set.of("friendship_id", "last_sequence"));
    private static final DateTimeFormatter SQLITE_UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final Path database;
    private final V1SqliteConversationSource conversationSource;
    private final V1MessageStateImportPlanner planner;

    public V1SqliteMessageStateSource(Path database) {
        this.database = requireReadableDatabase(database);
        this.conversationSource = new V1SqliteConversationSource(this.database);
        this.planner = new V1MessageStateImportPlanner();
    }

    public V1MessageStateImportPlan readPlan() {
        try (Connection connection = DriverManager.getConnection(readOnlyUrl(database))) {
            configureReadOnly(connection);
            requireHealthyDatabase(connection);
            requireCurrentSchema(connection);
            connection.setAutoCommit(false);
            V1ConversationImportPlan conversationPlan = conversationSource.readPlan(connection);
            V1MessageStateImportPlan plan = planner.plan(new V1MessageStateSourceSnapshot(
                    conversationPlan,
                    readWatermarks(connection),
                    readMessages(connection),
                    readDeletionEvents(connection)));
            connection.rollback();
            return plan;
        } catch (SQLException exception) {
            throw new V1MessageStateSourceException("V1 message state source read failed", exception);
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
                throw new V1MessageStateSourceException("V1 SQLite quick_check did not pass");
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
                throw new V1MessageStateSourceException(
                        "V1 message state schema is missing required migrated columns");
            }
        }
    }

    private static List<V1ConversationWatermarkRow> readWatermarks(Connection connection)
            throws SQLException {
        List<V1ConversationWatermarkRow> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT room_id, last_sequence FROM room_message_sequences "
                                + "ORDER BY room_id")) {
            while (rows.next()) {
                result.add(new V1ConversationWatermarkRow(
                        LegacyV1ConversationKind.ROOM,
                        rows.getLong("room_id"),
                        rows.getLong("last_sequence")));
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT friendship_id, last_sequence "
                                + "FROM friendship_message_sequences ORDER BY friendship_id")) {
            while (rows.next()) {
                result.add(new V1ConversationWatermarkRow(
                        LegacyV1ConversationKind.FRIENDSHIP,
                        rows.getLong("friendship_id"),
                        rows.getLong("last_sequence")));
            }
        }
        return List.copyOf(result);
    }

    private static List<V1MessageCursorRow> readMessages(Connection connection)
            throws SQLException {
        List<V1MessageCursorRow> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT id, room_id, user_id, sequence, mutation_sequence, "
                                + "recalled, created_at FROM messages ORDER BY id")) {
            while (rows.next()) {
                result.add(readMessage(rows, LegacyV1ConversationKind.ROOM,
                        "room_id", "user_id"));
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT id, friendship_id, sender_id, sequence, mutation_sequence, "
                                + "recalled, created_at FROM friend_messages ORDER BY id")) {
            while (rows.next()) {
                result.add(readMessage(rows, LegacyV1ConversationKind.FRIENDSHIP,
                        "friendship_id", "sender_id"));
            }
        }
        return List.copyOf(result);
    }

    private static V1MessageCursorRow readMessage(
            ResultSet row,
            LegacyV1ConversationKind kind,
            String conversationColumn,
            String senderColumn) throws SQLException {
        long mutation = row.getLong("mutation_sequence");
        Long mutationSequence = row.wasNull() ? null : mutation;
        return new V1MessageCursorRow(
                kind,
                row.getLong(conversationColumn),
                row.getLong("id"),
                row.getLong(senderColumn),
                row.getLong("sequence"),
                mutationSequence,
                row.getInt("recalled") != 0,
                parseTimestamp(row.getString("created_at")));
    }

    private static List<V1RoomDeletionCursorRow> readDeletionEvents(Connection connection)
            throws SQLException {
        List<V1RoomDeletionCursorRow> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT id, room_id, operator_user_id, sequence, created_at "
                                + "FROM room_message_deletion_events ORDER BY id")) {
            while (rows.next()) {
                result.add(new V1RoomDeletionCursorRow(
                        rows.getLong("id"),
                        rows.getLong("room_id"),
                        rows.getLong("operator_user_id"),
                        rows.getLong("sequence"),
                        parseTimestamp(rows.getString("created_at"))));
            }
        }
        return List.copyOf(result);
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // V1 CURRENT_TIMESTAMP uses the SQLite UTC form below.
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
                throw new V1MessageStateSourceException(
                        "V1 SQLite source must be a readable file");
            }
            return real;
        } catch (java.io.IOException exception) {
            throw new V1MessageStateSourceException(
                    "V1 SQLite source is not readable", exception);
        }
    }

    private static String readOnlyUrl(Path database) {
        return "jdbc:sqlite:" + database.toUri() + "?mode=ro";
    }
}
