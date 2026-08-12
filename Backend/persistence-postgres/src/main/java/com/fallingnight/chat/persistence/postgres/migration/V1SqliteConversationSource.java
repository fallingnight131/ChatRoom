package com.fallingnight.chat.persistence.postgres.migration;

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

/** Reads minimal V1 room/friendship metadata through query-only SQLite. */
public final class V1SqliteConversationSource {
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "users", Set.of("id"),
            "rooms", Set.of("id", "name", "creator_id", "created_at"),
            "room_members", Set.of(
                    "room_id", "user_id", "joined_at", "last_read_msg_id"),
            "room_admins", Set.of("room_id", "user_id"),
            "friendships", Set.of(
                    "id", "user_id1", "user_id2", "created_at",
                    "user1_last_read_msg_id", "user2_last_read_msg_id"));
    private static final DateTimeFormatter SQLITE_UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final Path database;
    private final V1ConversationImportPlanner planner;

    public V1SqliteConversationSource(Path database) {
        this(database, new V1ConversationImportPlanner());
    }

    V1SqliteConversationSource(Path database, V1ConversationImportPlanner planner) {
        this.database = requireReadableDatabase(database);
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public V1ConversationImportPlan readPlan() {
        try (Connection connection = DriverManager.getConnection(readOnlyUrl(database))) {
            configureReadOnly(connection);
            requireHealthyDatabase(connection);
            requireCurrentSchema(connection);
            return readPlan(connection);
        } catch (SQLException exception) {
            throw new V1ConversationSourceException("V1 conversation source read failed", exception);
        }
    }

    V1ConversationImportPlan readPlan(Connection connection) throws SQLException {
        return planner.plan(new V1ConversationSourceSnapshot(
                readUserIds(connection),
                readRooms(connection),
                readMemberships(connection),
                readAdministrators(connection),
                readFriendships(connection)));
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
                throw new V1ConversationSourceException("V1 SQLite quick_check did not pass");
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
                throw new V1ConversationSourceException(
                        "V1 conversation schema is missing required migrated columns");
            }
        }
    }

    private static Set<Long> readUserIds(Connection connection) throws SQLException {
        Set<Long> users = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT id FROM users ORDER BY id")) {
            while (result.next()) {
                users.add(result.getLong("id"));
            }
        }
        return Set.copyOf(users);
    }

    private static List<V1RoomRow> readRooms(Connection connection) throws SQLException {
        List<V1RoomRow> rooms = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, name, creator_id, created_at FROM rooms ORDER BY id")) {
            while (result.next()) {
                rooms.add(new V1RoomRow(
                        result.getLong("id"),
                        result.getString("name"),
                        result.getLong("creator_id"),
                        parseTimestamp(result.getString("created_at"))));
            }
        }
        return List.copyOf(rooms);
    }

    private static List<V1RoomMembershipRow> readMemberships(Connection connection)
            throws SQLException {
        List<V1RoomMembershipRow> memberships = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT room_id, user_id, joined_at, "
                                + "COALESCE(last_read_msg_id, 0) AS last_read_msg_id "
                                + "FROM room_members ORDER BY room_id, user_id")) {
            while (result.next()) {
                memberships.add(new V1RoomMembershipRow(
                        result.getLong("room_id"),
                        result.getLong("user_id"),
                        parseTimestamp(result.getString("joined_at")),
                        result.getLong("last_read_msg_id")));
            }
        }
        return List.copyOf(memberships);
    }

    private static Set<V1RoomAdministrator> readAdministrators(Connection connection)
            throws SQLException {
        Set<V1RoomAdministrator> administrators = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT room_id, user_id FROM room_admins ORDER BY room_id, user_id")) {
            while (result.next()) {
                administrators.add(new V1RoomAdministrator(
                        result.getLong("room_id"), result.getLong("user_id")));
            }
        }
        return Set.copyOf(administrators);
    }

    private static List<V1FriendshipRow> readFriendships(Connection connection)
            throws SQLException {
        List<V1FriendshipRow> friendships = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, user_id1, user_id2, created_at, "
                                + "COALESCE(user1_last_read_msg_id, 0) "
                                + "AS user1_last_read_msg_id, "
                                + "COALESCE(user2_last_read_msg_id, 0) "
                                + "AS user2_last_read_msg_id "
                                + "FROM friendships ORDER BY id")) {
            while (result.next()) {
                friendships.add(new V1FriendshipRow(
                        result.getLong("id"),
                        result.getLong("user_id1"),
                        result.getLong("user_id2"),
                        parseTimestamp(result.getString("created_at")),
                        result.getLong("user1_last_read_msg_id"),
                        result.getLong("user2_last_read_msg_id")));
            }
        }
        return List.copyOf(friendships);
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
                throw new V1ConversationSourceException(
                        "V1 SQLite source must be a readable file");
            }
            return real;
        } catch (java.io.IOException exception) {
            throw new V1ConversationSourceException(
                    "V1 SQLite source is not readable", exception);
        }
    }

    private static String readOnlyUrl(Path database) {
        return "jdbc:sqlite:" + database.toUri() + "?mode=ro";
    }
}
